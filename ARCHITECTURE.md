# Unlucky Client — Architecture & Feature Map

> **Orientation doc for contributors and AI assistants.** Read this before touching the
> codebase. It explains what exists, what each mixin hooks, and the 26.2-specific API
> traps that will otherwise cost you an hour each.
>
> **Last synced:** v2.1 + NewModules Phase 0/1 through NewChunks
> (`ServerVisibility`/Panic, the inventory/offhand/damage owners, survival safety, BetterChat,
> VeinMiner, Scaffold, `TargetingUtil`, `ProjectilePathUtil`, `ProjectileAimSolver`, LegitAimbot,
> Trajectories, NewChunks)
> / MC 26.2 / Fabric Loader 0.19.3 / Java 25
> **Keep it current:** see [Version bump checklist](#version-bump-checklist).

---

## 1. What this client is

A **visuals-first** Minecraft utility client for Fabric. Mod id `unlucky`, maven group
`unlucky.utility`. Everything is client-side; there is no server component and no common
source set worth speaking of — code lives in `src/client/java/unlucky/utility/client/`.

Design bias, in priority order: **looks good → feels good → does something useful.**
When a change trades visual quality for performance, that is a regression unless the user
says otherwise. (This has been an explicit standing constraint, e.g. the StorageESP
optimization pass was required to be pixel-identical.)

---

## 2. Entry points

| Class | Role |
| --- | --- |
| `UnluckyClientMod` | Fabric `ClientModInitializer`. Owns `id(path)` → `Identifier`. |
| `UnluckyClient` | Singleton holding every manager. `INSTANCE`, `init()`, `tick()`, `renderHud()`, `onKeyPress()`. |
| `ModuleManager` | Registers all 122 modules in one `init()` block. `get(Class)` is an `IdentityHashMap` lookup — it sits on per-entity-per-frame render paths (chams/glow/nametag mixins), so keep it O(1). **`register()` also appends every module's `Hidden` setting** — deliberately here and not in the `Module` constructor, because `register` runs *after* the subclass constructor, so the toggle lands after each module's own settings instead of jumping ahead of all of them. A setting added in a base constructor always sorts first; that's the trap. |
| `PerfDebug` | Frame/tick profiler behind `-Dunlucky.perfDebug` (or env `UNLUCKY_PERF_DEBUG=true`): rolling avg/max per section logged once a second. `static final` flag → zero cost when off. Sections: `overlay.*` (ESP/NameTags), `hud.*` (per widget + avoidance), `tick.<Module>`. |
| `HudManager` | Registers all 23 HUD widgets, and rebuilds persisted widget **copies** before settings are applied (`restoreDuplicate`). |
| `ConfigManager` | Gson → `config/unlucky/config.json` (everything client-side lives under `config/unlucky/`: config, `friends.json`, cape cache; the pre-2026-07 `config/unlucky.json` is auto-migrated via `Files.move` on first load). Saved on a JVM shutdown hook. Split into `toJson()` / `apply(JsonObject)` halves so **named profiles** (`config/unlucky/configs/*.json`, managed by `gui/configs/ConfigsScreen` behind the toolbar's Configs button) reuse the exact same round-trip: `saveProfile` (filename-sanitised), `loadProfile` (applies *and* saves as the active config, so it survives restart), `listProfiles` (newest first). Import/Export = native tinyfd dialogs (off-thread, they block — same pattern as the skin picker); Open folder via `Util.getPlatform().openPath` (`net.minecraft.util.Util`, not `net.minecraft.Util`). |

Default keys (rebindable in-GUI): `Right Shift` ClickGUI, `Right Ctrl` HUD editor.

`UnluckyClient.onKeyPress` returns `true` to **swallow** the key. This is load-bearing: if
it didn't, the same press that opens the ClickGUI would immediately reach the new screen
and close it.

---

## 3. Mixin map

76 entries in `unlucky.client.mixins.json`, all `client`-side, `compatibilityLevel: JAVA_25`,
`defaultRequire: 1`. Every injected method is prefixed `unlucky$`. (Two entries —
`ItemStackTooltipMixin`, `ItemContainerContentsMixin` — target *common* classes
(`ItemStack`, `ItemContainerContents`) from the client config; that's fine because tooltips
are client-only.)

### 3.1 The XRay subsystem (7 mixins — read this as one unit)

XRay is by far the most invasive feature. Making ores *visible* is easy; making them look
**flat and evenly lit** required defeating four separate lighting paths. Do not touch one
of these without understanding the others.

| Mixin | Target | Hook | Why |
| --- | --- | --- | --- |
| `SectionCompilerMixin` | `SectionCompiler` | `@Redirect` ×3 + begin/end `@Inject` | Skips hidden blocks/fluids while meshing; opens up section occlusion so you can see through walls. |
| `BlockMixin` | `Block` (static) | `shouldRenderFace` HEAD | Faces against hidden blocks aren't really covered — render them. Static root covers both vanilla **and** the Fabric renderer pipeline. |
| `FluidRendererMixin` | `FluidRenderer` | `isFaceOccludedByNeighbor`, `getLightCoords` | Without this, water is a floating 1-block-thick sheet instead of a volume. |
| `BlockModelLighterCacheMixin` | `BlockModelLighter.Cache` | `getLightCoords` HEAD | Forces full-bright light coords. Only shown blocks are tesselated, so every lookup here belongs to an ore. |
| `CardinalLightingMixin` | `CardinalLighting` | `byFace` + `up/down/north/south/east/west` | Kills directional face shading (top bright, sides dark). |
| `ModelBlockRendererMixin` | `ModelBlockRenderer` | `@Redirect` in `tesselateBlock` | Forces the **non-AO** tesselation path — no corner darkening. |
| `AoCalculatorMixin` | `AoCalculator` (`remap = false`) | `compute` TAIL | **Fabric Indigo** is the default block renderer and uses its *own* AO calculator, so the vanilla hooks above never reach it. Flattens AO + light after the fact. |

The `remap = false` on `AoCalculatorMixin` is mandatory — Indigo is Fabric's class, not
Mojang's, so it has no intermediary mapping.

### 3.2 Rendering & ESP

| Mixin | Target | Hook | Serves |
| --- | --- | --- | --- |
| `LivingEntityRenderStateMixin` | `LivingEntityRenderState` | implements `ChamsRenderState` | **The 26.2 deferred-render bridge.** Carries chams tint + spin-outline from `extractRenderState` (which has the entity) to `submit` (which has the model). In 26.2 these are separate phases; you cannot read the entity at submit time. |
| `LivingEntityRendererMixin` | `LivingEntityRenderer` | `getRenderType` RETURN (Image/Portal); `submit` @ `popPose` INVOKE (Flat/CS:GO) | Chams, two strategies. **Image / Portal** = Meteor-style **in-place render swap**: `getRenderType` returns our screen-space type so the model draws **once** — no coincident re-draw, no z-fighting, pixel-perfect 1:1 silhouette (`Chams.inPlaceMode()`). **Image** samples `chams.png` by per-fragment screen position (`unlucky:core/chams_screen`, fixed-background effect, fullbright). **Portal** (`unlucky:core/chams_portal`, shares the screen vsh) reproduces vanilla `rendertype_end_portal` verbatim — COLORS table, 15 GameTime-animated layers — but single-sampler (`textures/entity/end_portal/end_portal.png` — 26.2 moved it into a subfolder, the flat path renders magenta; the end-sky base layer is a constant = end_sky.png's measured average (0.45, 0.34, 0.61) — it is NOT dark, its COLORS[0] product is the portal's ambient glow) and sampled by screen position; GameTime works because ENTITY_SNIPPET chains the GLOBALS bind group. **CS:GO** two-tone replaces the skin with a solid flat colour: `getRenderType` returns **null** (skips the real model — `submit` still reaches `popPose`, verified in bytecode) and the re-submit draws in-sight + behind-wall passes as flat colour via a 4×4 `white.png` (white × tint = solid). **Flat** tint still *overlays* the skin (a second tinted pass at `popPose`). Through-walls = custom no-depth `RenderPipeline` (`ChamsRenderType`). Pipeline GLSL compiles at **resource load**, not lazily — compile errors show at boot; "does not use sampler Sampler1/2" warnings from these pipelines are benign linker dead-code elimination (fullbright fsh ignores lightmap/overlay). |
| `EntityRendererMixin` | `EntityRenderer` | `extractRenderState` | Stashes the ESP outline colour on the render state; also nulls `state.nameTag` **and `state.scoreText`** for players when **NameTags** is on (`NameTags.hidesVanilla`) — the below_name scoreboard line is a *separate* render-state field, and NameTags re-renders it via `player.belowNameDisplay()` as its own styled score row. |
| `MinecraftMixin` | `Minecraft` | `shouldEntityAppearGlowing` RETURN, `startUseItem` HEAD (cancellable) + RETURN, `pickBlockOrEntity` HEAD | ESP glow pass; right-click actions (ClickTP, TridentFly) in **one shared handler**, FastUse's `rightClickDelay`, middle-click ClickTP. **See §6.** |
| `AbstractClientPlayerMixin` | `AbstractClientPlayer` | `getSkin` RETURN | Swaps cape/elytra on your own skin so vanilla layers render it 1:1. |
| `WingsLayerMixin` | `WingsLayer` | `submit` HEAD+RETURN | ElytraPhysics sway: push/transform/pop the PoseStack around the elytra layer — rigid-unit rotation. **See the trap in §6.** |
| `AvatarRendererMixin` | `AvatarRenderer` | `extractRenderState` TAIL; `<init>` TAIL | ElytraPhysics wing spread via `state.elytraRotZ`; the **silent-aim pose** on the local model (`bodyRot` + `yRot` + `xRot`) while `RotationManager.hasVisualPose()` (**see §6**); and attaches `SkinLayer3DFeature` (3DSkinLayers) via `LivingEntityRendererInvoker.addLayer`. |
| `LivingEntityRendererInvoker` | `LivingEntityRenderer` | `@Invoker addLayer` | Exposes the protected inherited `addLayer` so `AvatarRendererMixin` can attach the 3D skin layer — `@Shadow` can't reach a superclass-declared method. |
| `PlayerModelMixin` | `PlayerModel` | `setupAnim(AvatarRenderState)` TAIL | 3DSkinLayers: hides the flat overlay parts (hat/jacket/sleeves/pants `visible=false`) under the module's gate so the voxel layer replaces them, never doubles. |
| `MinecraftAccessor` | `Minecraft` | `@Mutable @Accessor user`, `profileFuture`, `userApiService`, `userPropertiesFuture`, `profileKeyPairManager` | Alt switcher: swap the live session with no restart. `user`+`profileFuture` alone aren't enough — `getGameProfile()` reads `profileFuture` first (else new token + old uuid), **and** the other three are account-bound services the constructor builds once. Leave `userApiService` stale and anything that verifies against Mojang (Realms, the registry) reads the switched session as "invalid" while offline-mode servers look fine. See `AccountSwitcher.rebuildSession`. |
| `EntityRenderDispatcherMixin` | `EntityRenderDispatcher` | `shouldRender` HEAD cancellable | ItemFrames distance cull. Earliest possible bail — say no here and no render state is extracted, so the whole per-frame item-frame cost (block model, item model, map render) is skipped rather than drawn and discarded. |
| `ItemEntityRendererMixin` | `ItemEntityRenderer` | `extractRenderState` TAIL, two `@Redirect`s at the bob `translate(FFF)` and Y-spin `mulPose(Quaternionfc)` in `submit` | ItemPhysics. Stashes on-ground/speed/seed onto the render state in extract (entity only in scope there), then rewrites just the bob and spin in submit — model/bundle/stack pipeline untouched. |
| `ItemEntityRenderStateMixin` | `ItemEntityRenderState` | duck-interface for `ItemPhysicsData` | Carries the per-item physics data (on-ground, speed, stable per-entity seed) across the 26.2 extract→submit split, where the entity is gone by submit. |
| `ClientAvatarStateMixin` | `ClientAvatarState` | `moveCloak` HEAD (cancellable) | ElytraPhysics "Smooth cape sim": replaces vanilla's 10-block cloak snap with a smooth 9.5-block clamp so cape/elytra don't jerk at ElytraFly speeds. Vanilla path untouched when off. |
| `FogRendererMixin` | `FogRenderer` | `setupFog` RETURN, **`priority = 500`** | Fog for **both** NoFog (distance, Nether, End) and NoRender (water, lava, powder snow, blindness, darkness). Clears the two `FogData` channels **independently** — see §6. **Priority is load-bearing:** Sodium injects at the same RETURN and snapshots `FogData` into its own `FogParameters` for the terrain shaders; at equal priority the tie is undefined and Sodium was capturing fog before we cleared it (terrain stayed foggy, everything else cleared). Lower priority = applied first = runs first. |
| `GameRendererMixin` | `GameRenderer` | `bobHurt` HEAD | NoHurtCam. |
| `LevelMixin` | `Level` | `getRainLevel` / `getThunderLevel` RETURN, `setSkyFlashTime` HEAD | `WeatherOverrideManager`, currently requested by NoWeather. **`Level` is common — every hook is gated on "is this the client's level"**, or we'd lie to the integrated server. |
| `ClientLevelMixin` | `ClientLevel` | `tickWeatherEffects` HEAD, `addDestroyBlockEffect` HEAD | `WeatherOverrideManager` (rain particles + ambient sound), NoRender (block-break particles). The Weather expansion must extend these hooks, not add another mixin to the methods. |
| `ScreenEffectRendererMixin` | `ScreenEffectRenderer` | `submitFire` / `submitBlockSprite` / `submitWater` HEAD (all **static**), `displayItemActivation` HEAD | NoRender: fire / in-block / water overlays + totem animation. |
| `BossHealthOverlayMixin` | `BossHealthOverlay` | `extractRenderState` HEAD | NoRender boss bars. |
| `GuiMixin` | `Gui` | `setScreen` HEAD cancellable | **Silent containers** for AutoBrew. Two halves. (1) The window: cancels the window for a container screen while AutoBrew is mid-cycle on an open *it* requested. Works because `MenuScreens.ScreenConstructor.fromPacket` assigns `player.containerMenu = screen.getMenu()` **before** calling `gui.setScreen(screen)` — drop the second and the menu is live with no window. Narrow on purpose: a chest the player opens by hand must still show, since that's how AutoBrew learns about it. The `instanceof AbstractContainerScreen` test short-circuits before `modules.get`, so this costs nothing on ordinary screens. (2) **The close.** `LocalPlayer.closeContainer()` -> `clientSideCloseContainer()` -> **`gui.setScreen(null)`** — vanilla's close path clears the screen and can't tell that the screen it's clearing is the player's, not the container's. AutoBrew closes a container every few ticks, so chat/ESC/ClickGUI were being slammed shut a tick after opening. `AutoBrew.closeMenu()` flags the call and this drops that one `setScreen(null)`. There is no vanilla "close the menu, leave my GUI alone" — the plain `Player.closeContainer()` under it is protected. |
| — | — | — | **`BrewingWidget`** (HUD): live read-out of AutoBrew — order + progress, current job, next order, every stand (idle / `12s` / bottles in) and chest (with remembered contents). Reads AutoBrew's own `status()`/getters, so the screen shows what the *machine believes*; when the two disagree, the belief is the bug. Exists because a state machine spread across containers you can't see into fails as "nothing is happening", which looks identical whether the queue is empty, a chest is out of reach, or a reagent ran out. |
| `HudMixin` | `Hud` | `extractTextureOverlay` HEAD; `extractHotbarAndDecorations` HEAD push+translate / RETURN pop; `extractFood` HEAD+TAIL; `extractHearts` TAIL | NoRender pumpkin overlay (the head-equippable camera overlay; **not** the in-block one, that's `submitBlockSprite`); the chat-clear shift that eases the whole bottom HUD cluster up while chat is open (§6); and FoodOverlay: exhaustion dither behind the pips (HEAD), saturation arcs + hunger/saturation restore preview over them (TAIL, vanilla's own pip coords `x = rightX - i*8 - 9`), health restore preview on the hearts row (`rowHeight = max(10-(rows-2), 3)`). |
| `FoodDataAccessor` | `FoodData` | `@Accessor exhaustionLevel` | Vanilla has no exhaustion getter; FoodOverlay reads the integrated-server player's value (never synced to clients — AppleSkin ships a server mod for that). |
| `ChatSlideMixin` | `ChatComponent` | `extractRenderState` (7-arg) HEAD push+translate / RETURN pop | Message-log slide-in from the left on open (one-shot; log + focused text share this method). Does not push the HUD. |
| `ChatInputSlideMixin` | `ChatScreen` | `extractRenderState` HEAD/RETURN + before/after the `ChatComponent` INVOKE | Input-bar slide-up from the bottom; brackets its pose translate around the middle FOREGROUND-log call. |
| `LightmapRenderStateExtractorMixin` | `LightmapRenderStateExtractor` | `extract` TAIL | Fullbright (the *global* one, distinct from XRay's). |
| `ItemStackTooltipMixin` | `ItemStack` | `getTooltipImage` RETURN; `getTooltipLines` RETURN | InventoryInfo: returns a `ContainerTooltipData` for `CONTAINER` stacks (rendered as a grid via the Fabric `ClientTooltipComponentCallback` registered in `UnluckyClient.init`), and appends the byte-size line. |
| `ItemContainerContentsMixin` | `ItemContainerContents` | `addToTooltip` HEAD (cancellable) | InventoryInfo: cancels the vanilla "x N ItemName" text lines when the container-grid preview is on, so text + grid don't double up. |
| `PlayerTabOverlayMixin` | `PlayerTabOverlay` | `getNameForDisplay` RETURN | Both marks: the friend mark (`•`/`ꜰ` per `Friends.markerText()`, friend blue / self green) leads the name, the Unlucky mark (`UnluckyUsers.markerText()` — a 13-glyph Style dropdown, ★ default; the viewer's pick, in that user's registered colour) trails it. The friend prefix carries a **leading space**: the vanilla skin face sits immediately left of this string, and a mark flush against it reads as part of the face. Font note (from the 26.2 jar's font json): ᴜ ʟ ꜰ • are on the crisp `nonlatin_european` page; **✦ only exists in the unifont fallback**, which is why the star looks blockier than everything around it. `getNameForDisplay` is the single source for the shown name (measured and drawn), so layout stays consistent. |
| `ToastManagerAccessor` | `ToastManager` | `@Invoker freeSlotCount` | HUD toast avoidance: top-right widgets slide down while toasts occupy slots (5 × 32px, 160 wide; merged with the potion band in `HudManager.avoidTopRight` so nothing double-pushes). |
| `SodiumBlockRenderContextMixin` | sodium `AbstractBlockRenderContext` (string target) | `shouldDrawSide` + `isFaceCulled` HEAD, `require = 0` | XRay under Sodium: its mesher skips every vanilla path our other XRay hooks use. Uses the `*At(pos)` XRay checks — the plain `active()/hides()` gate on a ThreadLocal only the vanilla section compiler sets (permanently false on Sodium threads, the reason two working-hook rounds still hid nothing). |
| `SodiumBlockRendererMixin` | sodium `BlockRenderer` (string target) | `renderModel` HEAD, `require = 0` | XRay terrain hide: cancels meshing hidden states outright (`XRay.hidesAt`). `isFaceCulled` is declared on the parent context, NOT here — targeting it here silently aborted the whole mixin (one invalid injection kills all injects; require 0 hid it). |
| `SodiumLightDataAccessMixin` | sodium `LightDataAccess` (string target) | `@ModifyReturnValue compute(III)I`, `require = 0` | XRay fullbright under Sodium: rebuilds the packed light word (full block+sky light, flat AO, no emissive; opacity/full-cube flags preserved via shadowed `pack*`/`unpack*`) when `XRay.fullbrightAt(pos)`. Replaces the bypassed vanilla flat-shade path (CardinalLighting/BlockModelLighterCache/etc). |
| `GuiBlurMixin` | `Gui` | `extractRenderState` HEAD | Reopens the frame's single blur claim (`FrameBlur.beginFrame`). **This hook location is the whole point:** it is the one call that runs every frame whatever is on screen. Clearing the claim on the way out of `GuiRendererMixin` does not work — those injections sit around `processBlurEffect`, which vanilla skips entirely on frames where nothing blurred, so the claim would stick and no menu would ever blur again. The HUD element won't do either: F1 skips it. |
| `GuiRendererMixin` | `GuiRenderer` | `draw`, before/after the `GameRenderer.processBlurEffect()` INVOKE | Future ClickGUI's panel-clipped blur: snapshot the sharp world before vanilla's one blur, keep the blurred result, restore the sharp copy, then replay the blurred one through a scissor per registered panel (`FuturePanelBlur`). |
| `LevelExtractorMixin` | `LevelExtractor` | `isEntityVisible` HEAD cancellable | Freecam's F5 spectator-head proxy. The real player is kept extracted at its true world position (it can be far outside the freecam frustum, which would otherwise cull the body); the translucent head near the camera is a second, independent extraction marked by `FreecamRenderProxy`. |
| `VisGraphMixin` | `VisGraph` | `setOpaque` HEAD, cancellable | XRay: nothing is opaque to the section-visibility graph while enabled, so enclosed caves stay renderable. Engine-agnostic root — Sodium's occlusion culler reuses vanilla VisGraph, so this one hook opens both pipelines. Gated on `XRay.enabled()` (no range/ThreadLocal). |

Note `MinecraftMixin` and `MinecraftTitleMixin` **both target `Minecraft.class`** — split
purely for readability (`createTitle` → window title branding).

### Adding a hook: three rules, in order

A mixin is bound to its target class and can never span two, so "fewer mixins" is not the
lever — `NoRender` is hooked from five of them because it touches five vanilla classes, and
that is not junk. What keeps that sane is that all five call into **one** module with
toggles. As measured 2026-08-04: 19 mixins serve 2–6 features each, 33 serve one (each the
only possible hook for its class), 18 are infrastructure; seven classes carry more than one
mixin and **no two of them hook the same method**.

1. **Does a mixin already target this class?** Add to it. Don't make a second.
2. **Does one already hook this *method*?** You must extend that handler — Mixin will not
   order two injections into the same method. Three scars in this file came from learning
   that: AntiToS and ChatTag chain inside one `@ModifyVariable`; Criticals and SessionTracker
   share one `attack` handler; `ChatCommandMixin` and Greentext sit at the same `sendChat`
   HEAD and were fixed by removing the ordering dependency, not by forcing an order.
3. **Is the behaviour shared with an existing feature?** It belongs in `util/` or the module,
   not in the mixin. Mixins here average ~2.5 injections and translate-then-delegate; that is
   what keeps them cheap. Rules 1 and 2 Mixin enforces itself — **this is the only one
   judgement can violate silently.**

### 3.3 Input & camera

| Mixin | Target | Hook | Serves |
| --- | --- | --- | --- |
| `KeyboardHandlerMixin` | `KeyboardHandler` | `keyPress` HEAD, cancellable | Routes raw keys to `UnluckyClient.onKeyPress`; cancels when swallowed. |
| `KeyboardInputMixin` | `KeyboardInput` | `tick` TAIL; `@Redirect KeyMapping.isDown()` in `tick` | Freezes player movement while Freecam flies the camera. InventoryMove: `tick` builds its `Input` from seven `isDown()` calls that all read false while a screen is open (vanilla releases every mapping on open) — one redirect polls the hardware instead and covers all seven. |
| `KeyMappingAccessor` | `KeyMapping` | `@Accessor("key")` | InventoryMove needs the bound `InputConstants.Key` to poll GLFW directly; `KeyMapping` exposes `isDown()` but no getter for the key itself. |
| `MouseHandlerMixin` | `MouseHandler` | `@Redirect turnPlayer`; `onButton` HEAD; `onScroll` HEAD cancellable | Steers the freecam/freelook instead of the player; records the **same mouse deltas vanilla receives** for LegitAimbot's opposite-input gate; Friends middle-click toggle (crosshair player, in-game only — vanilla pick-block still proceeds); Zoom's mouse-wheel factor step (swallows the scroll so the hotbar doesn't move too). The recorder lives in this redirect instead of a second handler because two injections around the same `LocalPlayer.turn` call would have no ordering contract. |
| `CameraMixin` | `Camera` | `calculateFov` RETURN, `alignWithEntity` HEAD/TAIL, `@Inject` + `@ModifyArg` at the `getMaxZoom(F)` INVOKE in `alignWithEntity`, `getMaxZoom` HEAD | Zoom, freecam detach, ViewClip (distance + clip-through), Freelook. **Freelook ordering matters:** `alignWithEntity` has already pointed the camera at the player's rotation, so the free rotation must be set at the `getMaxZoom` INVOKE — *before* vanilla's `move()` pushes the camera back — or it orbits along the body's facing instead of the mouse's. |

### 3.4 Network, combat, chat

| Mixin | Target | Hook | Serves |
| --- | --- | --- | --- |
| `ClientCommonPacketListenerMixin` | `ClientCommonPacketListenerImpl` | `@ModifyVariable send` HEAD; `@Redirect Connection.send` | Rewrites outgoing rotation-bearing packets with the spoofed rotation (`RotationManager`) — movement packets AND `ServerboundUseItemPacket` (carries its own yaw/pitch since ~1.20.2, the server re-applies it before item use; without the rewrite, spoofed rotations are silently ignored for thrown items — AutoXPRepair's look-down bottles). The redirect then offers that already-rewritten packet to `PacketQueueManager`; flush writes the stored object to the underlying connection so a newer rotation cannot rewrite history. |
| `LocatorBarMixin` | `LocatorBar` | `@WrapOperation` on the 7-arg color `blitSprite` in the forEachWaypoint lambda (`method = "*"`; arrows use the 6-arg variant so the target is unambiguous) | Heads: player-UUID waypoints render the face (+friend dot) instead of the colored dot; string waypoints stay vanilla. `@Local TrackedWaypoint` for the UUID. |
| `ClientPacketListenerMixin` | `ClientPacketListener` | `handleSoundEvent`, `handleSetTime`, `handleTakeItemEntity`, `handlePlayerInfoUpdate` HEAD, `handleDamageEvent`, `handleAnimate`, `handleMovePlayer` TAIL; NewChunks TAIL on chunk load/forget + single/section block updates; `@Redirect handleSetEntityMotion`, `@ModifyExpressionValue handleExplosion` | SoundLocator, AutoFish (bobber-splash bite detection), TPS estimate, item-pickup HUD, GamemodeNotifier, Dodge (both triggers), Criticals' target-specific thorns correction, Velocity's attack/explosion scaling, NewChunks' packet evidence, and `PacketQueueManager`'s last server-confirmed position. The correction handler runs at TAIL, after vanilla has resolved relative coordinates and written the authoritative player position. The four NewChunks handlers extend this mixin rather than adding another owner for the same packet methods, and use TAIL so the network-thread pass has already thrown out through `ensureRunningOnSameThread`. **HEAD injects here run twice** — once on the netty thread before that reschedule, then on main. Guard HEAD work with `mc.isSameThread()` (pickup and GamemodeNotifier both do). |
| `MultiPlayerGameModeMixin` | `MultiPlayerGameMode` | `attack` HEAD cancellable, `attack` RETURN, `useItemOn` HEAD | The single funnel for **every** attack — manual clicks and Aura/TriggerBot alike, since `CombatUtil.attack` routes here. Criticals (may cancel, to replay at the top of a jump) and `SessionTracker` share **one handler**: mixin won't order two injections into the same method, and a swallowed jump-crit must not be counted now *and* again on replay. `useItemOn` feeds `AutoBrew.onBlockUsed` the clicked `BlockPos` — `ClientboundOpenScreen` carries **no position**, so the click is the only place a menu can be tied to a block (see §6). **Note the param types differ**: `attack` takes `Player`, `useItemOn` takes `LocalPlayer` — getting it wrong compiles and fails at apply time. Also carries InfiniteInteract's bracket: HEAD+RETURN pairs around `useItem`, `useItemOn`, `interact`, `startDestroyBlock` and `continueDestroyBlock`, so the packet-step is open for exactly the vanilla call and closed before anything else runs. |
| `MultiPlayerGameModeAccessor` | `MultiPlayerGameMode` | `@Invoker startPrediction` | Lets Nuker send START/STOP block-action packets with a valid prediction sequence ("packet mine", §6). |
| `LocalPlayerMixin` | `LocalPlayer` | `@Redirect onGround() in sendPosition`, `sendIsSprintingIfNeeded` HEAD, `moveTowardsClosestSpace` HEAD, `getJumpRidingScale` RETURN, `@Redirect itemUseSpeedMultiplier() in modifyInput`, `@Redirect Screen.isAllowedInPortal() in handlePortalTransitionEffect` | NoFall + AntiHunger — both lie about the same outgoing `onGround` flag (**see §6**). Velocity optionally cancels suffocation block-push; EntityControl exposes the mount's full jump charge. NoSlow: `modifyInput` scales the move vector by `itemUseSpeedMultiplier()` while an item is in use — return 1 and the slowdown never happens. InventoryMove: inside a portal `handlePortalTransitionEffect` force-closes every screen whose `isAllowedInPortal()` is false — and that method is literally just `isPauseScreen()`, which is why the portal kills the inventory and the ClickGUI. Answer the check "yes" and they survive, with the portal wobble and teleport untouched. |
| `PlayerMixin` | `Player` | `makeStuckInBlock` HEAD cancellable, `getBlockSpeedFactor` RETURN cancellable, `isStayingOnGroundSurface` RETURN cancellable | NoSlow's block-side penalties: cobwebs/berries/powder snow, and the soul sand / honey drag. Only factors **< 1** are lifted, so soul speed and other boosts still apply. Scaffold joins **vanilla's own edge-backoff decision**: Bridge can say "stay on the surface" without faking sneak; Descend says "allow the edge" only after its offset lower platform exists. One hook owns both answers — two handlers on the method would have no ordering contract. Self-only (`== mc.player`). |
| `EntityMixin` | `Entity` | `@ModifyVariable move` HEAD (`Vec3` argument only), `@WrapOperation push(DDD) in push(Entity)` | BoatFly and EntitySpeed replace the local ridden vehicle's requested movement immediately before vanilla resolves collisions. Velocity scales only collision pushes applied to the local player. Every other entity and every module-off call keeps vanilla behavior. |
| `EntityFluidInteractionMixin` | `EntityFluidInteraction` | `@ModifyExpressionValue FluidState.getFlow in update` | Velocity scales the fluid-current vector at its source without touching swimming input or gravity. |
| `FishingHookMixin` | `FishingHook` | `@WrapOperation pullEntity in handleEntityEvent` | Velocity optionally suppresses the client-side fishing-rod pull when its target is the local player. |
| `MobMixin` | `Mob` | `getControllingPassenger` RETURN, `isSaddled` RETURN | EntityControl supplies our already-mounted player when vanilla has no controller and opens saddle-gated mount jumping. It never edits the mob's equipment or item components. The integrated-server branch is UUID-limited to the local owner. |
| `LivingEntityMixin` | `LivingEntity` | `aiStep`, `@WrapOperation getRiddenInput in travelRidden`, `canGlide` RETURN, `updateFallFlyingMovement` RETURN, `handleEntityEvent`, `canStandOnFluid` RETURN, `@Redirect getEffect in travelInAir`, `@Redirect hasEffect in getEffectiveGravity` | NoJumpDelay, EntityControl's WASD input for pigs/striders, FakeFly, ElytraFly Static (**see §6**), totem-pop counter, Jesus (real fluid collision — **see §6**), AntiLevitation (levitation + optional slow-falling). |
| `ChatComponentMixin` | `ChatComponent` | `addMessage` HEAD + `@ModifyVariable` + `@Inject` at `addMessageToDisplayQueue` INVOKE (`@Local GuiMessage`) | AdBlocker (drop), AntiToS (censor), ChatTag (highlight), Heads (attach sender to the GuiMessage pre-split; HEAD also runs the cancel-safe `beginMessage()` handoff so blocked lines can't donate their head to the next one). **AntiToS and ChatTag chain inside one `@ModifyVariable`** (censor → highlight) rather than injecting twice — mixin does not order two handlers into one method. ChatTag's *ping* deliberately lives in the display-queue handler instead, which only runs for surviving messages, so a blocked ad that @'s you stays silent; it also peeks `Heads.currentSender()` there, before `tagMessage` consumes it. **BetterChat joins both chains** — the `@ModifyVariable` (last, so its timestamp is not part of the text the other two match against) and the display-queue handler (to stash the duplicate key on the message). It is the one member of the chain that also runs for `SYSTEM_CLIENT`: a timestamp with holes where your own command replies were is a bug, while censoring or highlighting your own output is not a thing anyone wants. |
| `ChatComponentAccessor` | `ChatComponent` | `@Accessor(allMessages)` + `@Invoker(refreshTrimmedMessages)` | BetterChat's duplicate compacting, the one feature that has to **remove** an already-shown message rather than change what the next one looks like. `trimmedMessages` is derived from `allMessages`, so dropping an entry and asking for a refresh is the whole operation — and vanilla's own re-split, chat-head indent included, runs as part of it. |
| `ChatListenerMixin` | `ChatListener` | `showMessageToPlayer` HEAD | Heads: the only spot where the signed sender UUID is in scope right before `addPlayerMessage` (synchronous — the delay queue wraps the whole call). |
| `GuiMessageMixin` | `GuiMessage` (record) | **two** duck fields + `splitLines` `@ModifyVariable` maxWidth / `@ModifyReturnValue` | Heads: carries the sender across re-flows; wraps 12px narrower and prepends a 3-space spacer per line so hover/click x-math stays native; registers the first line for the face draw. Re-split via `rescaleChat()` on toggle. Also carries BetterChat's duplicate key and repeat count (`ChatMessageKey`) — recomputing that from the displayed text would mean parsing our own timestamp and `×3` back off, and a line that genuinely ends in "×3" would compare equal to one that repeated three times. |
| `ChatGraphicsBackgroundMixin` / `ChatGraphicsFocusedMixin` | `ChatComponent$Drawing{Background,Focused}GraphicsAccess` | `handleMessage` HEAD | Heads: the funnel every visible chat line passes through with exact y + fade alpha — draws the 8px face in the reserved gap. |
| `ChatCommandMixin` | `ClientPacketListener` | `sendChat` HEAD cancellable **+** `sendChat` HEAD `@ModifyVariable(argsOnly)` | Client-side `.` commands (`.report`, `.friend`, …): a message starting `.` + a letter is routed to `CommandManager` and **cancelled**, so it never reaches the server. Registered before `ChatComponentMixin`. Safe on anarchy — nothing is sent. The second injection is Greentext. **Two injections at the same HEAD, and mixin does not order those** — if the rewrite won the race and prefixed `>` onto `.report`, the command hook would stop recognising it and every client command would go out as public chat. The fix is not to force an order but to remove the dependency: `Greentext.apply` skips anything the command hook would claim, so both sequences emit identical bytes. |
| `ClientCommandChatMixin` | `ChatScreen` | `keyPressed` / `mouseClicked` / `mouseScrolled` HEAD cancellable | Routes **only** dot-command input to `ClientCommandChatUi` (completion list: arrows, Tab, click, scroll). Regular messages and vanilla slash commands keep going through `CommandSuggestions` untouched — the suggestion popup never appears for syntax we don't own. The UI engages on a **bare `"."`**, not on `.`+letter like `ChatCommandMixin`'s claim rule: at one character every command is still a candidate, which is when the list is most useful. It disengages the moment the next character rules a command out (`".."`, `". hi"`), so a line the mixin would send to the server never wears the client-command accent. |
| `EntityMixin` | `Entity` | `move` HEAD `@ModifyVariable(argsOnly)` | Vehicle movement, rewritten immediately **before** vanilla resolves collisions so the ride still collides honestly: `AbstractBoat` goes to BoatFly, `LivingEntity` to EntitySpeed. One hook, dispatched by type — `move` is far too hot to mixin twice. |
| `MobMixin` | `Mob` | `getControllingPassenger` RETURN cancellable, `isSaddled` RETURN cancellable | EntityControl's two narrow vanilla gates. Vanilla only hands steering to a passenger it recognises and only lets a *saddled* mob jump; these answer both. Deliberately two tiny RETURN overrides rather than replacing the ride logic. |
| `FishingHookMixin` | `FishingHook` | `@WrapOperation pullEntity in handleEntityEvent` | Velocity: cancels **only** the local pull from entity event 31, and only client-side. Reeling someone in with a rod is a velocity source with no motion packet behind it, so it needs its own hook (§6). |
| `EntityFluidInteractionMixin` | `EntityFluidInteraction` | `@ModifyExpressionValue FluidState.getFlow in update` | Velocity: scales the flow vector *before* vanilla accumulates current, so liquid push is damped without touching swimming or buoyancy (§6). |
| `SignTextMixin` | `SignText` | `getMessages` RETURN | AntiToS on signs. |

### 3.5 Book screens

| Mixin | Target | Serves |
| --- | --- | --- |
| `BookEditScreenMixin` | `BookEditScreen` | BookTools: injects §-formatting buttons. |
| `BookViewScreenMixin` | `BookViewScreen` | PagePirate: adds a deobfuscate button. |
| `MultiLineEditBoxAccessor` | `MultiLineEditBox` | Accessor (not a mixin) — exposes internals for the above. |
| `TitleScreenMixin` | `TitleScreen` | `init` TAIL: the skin panel — live mouse-following `SkinPreviewWidget` + Edit (opens `SkinsScreen`) + NameMC buttons in the strip left of the menu column. |

---

## 4. Feature inventory

### 4.1 Modules — 122, registered in `ModuleManager.init()`

> **Trap:** the package layout is *not* the category. `Category` comes from the `Module`
> constructor. `Fullbright` lives in `modules/visuals/` but reports `RENDER`.

> **Every module declares a `ServerVisibility`** in the same constructor call, and there is
> no constructor that lets you skip it. See §4.1 "Panic and server visibility" below.

**Combat** — Aura, LegitAimbot (visible-only camera assistance; never attacks or silently
rotates), TriggerBot, AutoClicker, TargetStrafe, AutoTotem (asks
`OffhandManager` for a totem, never clicks a slot itself — see §4.1 below), AutoLog
(leaves before the thing that was going to kill you does; tells `AutoReconnect` it was
deliberate *before* going, since afterwards there is nothing left to ask), Criticals
(thorns-aware — see below), LegitMaceKill / BlatantMaceKill / MaceCombo (mace damage scales with fall distance,
so all three are about *fall*, not the swing: Legit amplifies only a genuine fall, Blatant
banks a server-side fall via `MaceKillPackets.prime`/restore while the client entity never
moves, Combo relaunches with wind charges to chain smashes)

**Movement** — ElytraFly (**two modes**: Boost adds to vanilla gliding from `onTick`;
Static replaces it outright by swapping the return of `updateFallFlyingMovement` — WASD
relative to yaw only, jump/sneak for height, nothing accumulates. See §6), BoatFly,
EntitySpeed, EntityControl, AutoSprint (omni), CreativeFlight, Jetpack, Speed, BunnyHop,
Velocity, NoJumpDelay, FakeFly, RocketMan, RocketJump, Updraft, RoadTrip (AFK travel
safeties), AFKVanillaFly, NoFall, AntiVoid (predictive Freeze / safe-position Return /
controlled Flight rescue), AntiLevitation, Yaw (hard yaw lock — a *real* rotation,
unlike `RotationManager`'s spoof), Jesus, TridentFly, ClickTP, EventlessFly (direct-packet
flight, so ordinary movement events never fire), WindChargeJump, Phase (through blocks, with
an optional deferred server teleport)

**Render** — PlayerESP (shader silhouette, CS-style 2D boxes w/ HP+armor bars, skeleton,
tracers), NameTags (billboard tags via the same world→screen 2D pass: gamemode/health
Number|Hearts (heart row scaled to the name width)/ping/distance, armor row with 3-letter
enchant chips in an even, uniform-width column grid (total capped by a slider);
Off/Custom/Vanilla backdrop; distance-falloff scale; cancels the vanilla tag), MobESP, StorageESP, Chams, XRay, Freecam, ElytraPhysics,
NoFog, AutoDrawDistance, Fullbright, Zoom, NoHurtCam, NoWeather, ViewClip, NoRender (screen-clutter toggles),
Heads (2D sender faces in chat — see the `ChatListener`/`GuiMessage`/`ChatGraphics*` mixin
cluster in §3.4; "Guess sender" matches plugin-formatted lines; toggling re-flows chat via
`rescaleChat()`), FoodOverlay (full AppleSkin recreation via `HudMixin`: saturation arcs +
hunger/saturation/health restore previews + exhaustion dither (integrated-server read —
singleplayer only) + food value tooltips (`FoodTooltipData`/`FoodValueComponent` through
the InventoryInfo tooltip pipeline); all sprites under
`assets/unlucky/textures/gui/sprites/food/` stitch into the vanilla GUI atlas — its
directory source scans `gui/sprites` across ALL namespaces — so resource packs can
restyle them; saturation syncs via `ClientboundSetHealthPacket`), Trajectories (individual
held/other-player/fired item gates, multishot/fishing paths, impact marker and hit-entity
highlight) and PearlChecker (both on `ProjectilePathUtil`: named 26.2 profiles, block/entity
segment collision and reusable result buffers shared between "where does what I'm holding
go", "where does that thrown pearl land" and the aim solver),
NBTTooltip (raw data components in the tooltip, copyable)

**Player** — AutoEat, AutoTool (best tool for the block, driven from
`MultiPlayerGameMode`'s destroy hooks rather than a tick — see §4.1 below),
AutoReplenish (**swaps rather than merges** — one atomic click that cannot strand an
item on the cursor beats three that can; only ever swaps in a *larger* stack, so it
cannot make a slot worse), AutoFish,
AutoXPRepair, AutoExtinguish, AntiHunger, FastUse, Capes, Honker, HotbarLoadout,
DonkeyRitual, InfiniteInteract, PagePirate

**Misc** — Panic (§4.1 below), BetterChat (timestamps, duplicate collapsing and filtering
as **one pass** that joins the existing AntiToS/ChatTag chain rather than adding a second
handler — mixin does not order two injections into one method; **never stringifies a
message**, every step appends the original so click events and hover text survive; the
duplicate key rides on the `GuiMessage` via a second duck interface beside `GuiMessageSender`,
because by comparison time the earlier line is wearing a timestamp and an `×3` this module put
there), AutoReconnect (classifies *why* you were disconnected —
the deliberate/not split is structural and recorded at the point the disconnect is asked
for; the kick/timeout/ban split is a text match on the one message the protocol gives us,
and says so), Friends, UnluckyUsers, ChatTag, AdBlocker, AntiToS,
Greentext, Spam, BibleBot, BookTools, InventoryInfo, SoundLocator, Spinbot,
GamemodeNotifier, DiscordRPC, Theme

**World** — NewChunks (session-scoped UNKNOWN/OLD/NEW packet-evidence overlay), ChatSigns,
WaxAura, AutoDoors (close-behind), BannerData, TreasureESP,
Search, Nuker, Scaffold (**Bridge / Tower / Descend**, with vanilla SafeWalk and a lower
offset landing rather than a block forced into the player's fall — see below), VeinMiner
(**seeded by a break you made yourself** — `destroyBlock`, not
`startDestroyBlock`, because "the player committed to this" is a completed break and not a
swing; mines the vanilla way precisely so **AutoTool's hooks fire for free**, and shares the
Printer's `continueAttack` guard in `MinecraftMixin` — vanilla calls `stopDestroyBlock()` every
tick the attack key is not held, which resets a module-driven break to zero while every call it
makes returns success), Archaeology, AutoFarm, AutoWither, ObsidianFarm, BlockAirPlace, VanityESP,
AutoBrew (multi-chest, multi-stand, parallel orders, hopper-fed storage, self-discovering — see `BrewingSolver`),
Printer (**builds Litematica schematics** — reads the ghost world via `LitematicaBridge`,
honours Litematica's own layer slider, sorts candidates 4 ways, randomised delay + jitter,
recently-placed blacklist so a laggy server doesn't get duplicate clicks, hotbar/inventory
switch with creative-packet restock, fade boxes on placed blocks. Orientation and stacking
are solved by `PlacementSolver`, so stairs/logs/slabs/snow-layers come out right; blocks
already placed the wrong way still need breaking first — the one case left, plan.md.
**Survival is a second, separate planner** — see §4.1), VillagerRoller (librarian book
rerolling, after FlexCoral's — see the recreate-from-references rule in §7)

### 4.1 Panic and server visibility

**One key that stops the client being interesting.** `Panic` (Category.MISC) is not a module
you toggle — `isToggleable()` is false and the ClickGUI draws "Always enabled" instead of a
checkbox. It holds settings; `onKeyBind()` holds the behaviour. There is also a **Panic now**
`ActionSetting` for running it from the box itself.

**`ServerVisibility` is the whole point, and it is a constructor argument.** Every module
answers `CLIENT_ONLY`, `SERVER_OBSERVABLE` or `CONDITIONAL` in its `super(...)` call, and the
3-arg and 4-arg `Module` constructors were **removed** so there is no way to add a module
without answering. That is the load-bearing decision here: the obvious implementation of
"turn off the incriminating half" is a list of module names inside `Panic`, and such a list
is wrong the first time somebody forgets to update it — silently, on a server, which is the
worst possible place to find out.

| Mode | What it disables |
| --- | --- |
| **Minimal** (default) | every enabled module where `isServerObservableNow()` — so `SERVER_OBSERVABLE` always, and `CONDITIONAL` only while it is actually doing something. ESP, chat, HUD, XRay and the rest keep running. |
| **All** | every enabled module that `isToggleable()`. Theme and HUD survive because they must. |

**The question is narrower than "is this cheating".** It is: does this module change,
suppress or send gameplay movement, rotation, inventory, attack, interaction, respawn or
reconnect behaviour? XRay is an enormous advantage and completely invisible on the wire, so
it is `CLIENT_ONLY`. AutoSprint puts a sprint packet on the wire your hands did not, so it is
not. **Freecam and Freelook are classed observable despite living in `modules/render/`**,
which is worth defending because the obvious objection is right as far as it goes: the server
cannot tell "frozen by Freecam" from "player standing still". They are in anyway, on the
suppression half of the rule and on a practical one — the state you want after hitting panic
is holding your own character, and Freecam is the module most capable of leaving you watching
from forty blocks away while something happens to your body.

**`CONDITIONAL` is for reactive modules only**, where the client can check right now whether
the thing being reacted to is happening. Two exist: `InventoryMove` (inert with no screen
open) and `AutoEat` (inert until it has claimed the hotbar). An automation that merely
happens to be idle — AutoFish between bites — is **not** conditional; it is going to fire on
its own in a moment, so it is `SERVER_OBSERVABLE`. A module declaring `CONDITIONAL` **must**
override `isServerObservableNow()`, and `ModuleSmokeTest` fails the build via reflection if
it does not: one that forgets is indistinguishable at runtime from `SERVER_OBSERVABLE`, so
the mistake would otherwise be invisible for ever.

**Order inside `fire()` is not arbitrary.** Modules first (`Module.panic()` → `onPanic()` →
`setEnabledSilently(false)`, silent so thirty modules do not queue thirty toasts), because a
module's own shutdown is the only code that knows what it was in the middle of. Then the
shared owners — `RotationManager.cancel()`, `MovementActionCoordinator.reset()`,
`PacketQueueManager.discardAll()`, `InventoryActionCoordinator.panic()`,
`OffhandManager.reset()` — as the backstop. Then keys,
**last**, because a module ticking one
more time could otherwise press one back down. The whole sequence runs while the world is
still there: a cursor stack put back after the menu closes is a cursor stack on the floor.

`Module.onPanic()` is the hook for modules whose ordinary disable is the wrong thing to do in
a hurry. It exists for Blink, whose normal disable *flushes* the packets it has been holding
— under a panic that is a burst of everything you were hiding, sent at the exact moment you
wanted to stop being interesting.

**Two smaller traps.** Client keybinds are normally dropped while any screen has focus, or
every letter typed into a search box would toggle modules; Panic is the one exception, taken
in `UnluckyClient.onKeyPress` and gated on the ClickGUI's own `isTyping()`. And "close client
screens" tests `instanceof BlursBackground` — that interface is every screen this client owns
and nothing else, which beats a list of screen classes that would go stale. Vanilla screens
are deliberately left alone.

### 4.1 The survival-safety group

Five modules that only work because they are wired to shared safety state, and would each be subtly
broken alone.

**AutoTotem and AutoReplenish share one slot.** Both want to write the offhand; neither does.
They call `OffhandManager.request(...)` every tick they want something there, and the manager
decides. Without that, the failure is not "they disagree once" — it is a swap every tick,
for ever, so the slot is empty at the exact moment the crystal lands. AutoTotem asks at
`PRIORITY_TOTEM`, AutoReplenish at `PRIORITY_REPLENISH`, and the arbitration is a comparison
rather than a convention.

**"Smart" AutoTotem is not "low health".** Health is the trigger that arrives too late — by
the time it reads six hearts the next crystal is already placed. The value is in the
predictions, which all come from `DamageForecast` so AutoTotem and AutoLog cannot disagree
about whether you were going to survive. Its **Preferred fallback = Previous item** is
implemented as the *absence* of a request: the manager restores what it displaced when nobody
is asking, which is a better answer than the module could reconstruct.

**AutoLog tells AutoReconnect what it did, before it does it.** A safety logout politely
undone four seconds later is worse than no logout — it puts you back in the fight with fewer
totems and no warning. So `AutoReconnect.markDeliberate(...)` is called *before* the
disconnect; afterwards there is nothing left to ask. The same call is in RoadTrip, whose
disconnects are the same shape.

**How AutoReconnect knows why you left.** Two of the five causes are structural and always
right: a module recording that it asked for this (`markDeliberate`), and `MinecraftMixin`'s
hook on `disconnectFromWorld` recording that a departure came from our side at all — every
local one passes through that method and no remote one does. The other three (kick, timeout,
ban/auth) are a **text match on the disconnect message**, because the protocol hands over one
`Component` and no code. Those three switches will miss a server that words it differently,
and the doc for the module says so rather than implying a precision that is not there.

**AntiVoid predicts a footprint, not just a Y number.** Predictive mode advances the current
bounding box and velocity for a bounded number of ticks. It asks the footprint overload of
`DamageForecast.distanceToGround` whether the future centre or any inset corner still has
solid support; this is the sideways-over-a-ledge case the older centre-column forecast
deliberately does not model. `Only true void` therefore leaves a survivable cliff alone even
when its landing is outside the look-ahead window. Simple Y remains the cheap late fallback,
measured as a margin above each dimension's minimum build height rather than a hard-coded
Overworld coordinate.

Rescue never writes position. Freeze removes horizontal and downward velocity, Return applies
ordinary controlled motion toward a recent supported position, and Flight applies controlled
lift plus the player's input. All three submit a per-tick request to
`MovementActionCoordinator` at safety priority. The manager resolves after every module tick,
so an ArrowDodge/LongJump-style movement decision cannot put the fall back merely because its
class sorted later. The trap is applying rescue inside `AntiVoid.onTick`: registration order
would then be the safety policy, and adding an alphabetically later movement module could
silently undo it.

### 4.1 Scaffold: down is not under

Bridge and Tower are the obvious halves: predict the next feet block, ask `PlacementSolver`
for a real support click, turn through `RotationManager`, then hold the inventory lease only
for the tick that sends the click. SafeWalk does **not** zero input or invent collision; the
existing `PlayerMixin` answers vanilla's `isStayingOnGroundSurface()` gate and lets vanilla's
own `maybeBackOffFromEdge` do the exact collision-aware retreat. Tower applies lift only while
the underfoot block is already solid or a placement was actually sent — no block, no rise.

**The Descend trap is treating "below" as "down".** A block directly below the player is an
obstacle, not a lower platform; placing it and then forcing negative Y drives the player into
the thing Scaffold just made. Descend therefore builds a two-block stair: an anchor below the
current platform, then an **offset** lower platform one block along the movement direction.
Sneak's ordinary edge lock remains in force while either piece is missing. Only after the
offset platform is present does the same `PlayerMixin` answer false and allow controlled
down-and-out movement. Losing the support click means waiting at the edge, never gambling the
player on a packet that might be accepted later.

Material selection rejects falling blocks and block entities even under "Any full block".
Both can present a full collision shape in the picker, but one can disappear as soon as it is
placed and the other can turn a support click into a menu; neither is automatic floor
material. Main-inventory swaps and hotbar selections go through `InventoryActionCoordinator`
at `PRIORITY_PLACEMENT`, and the lease is released immediately after the click so a configured
delay cannot starve AutoTool or AutoReplenish.

### 4.1 LegitAimbot: assist the hand, do not replace it

LegitAimbot is a **visible camera correction**, not a low-speed Aura. Target choice goes
through `TargetingUtil`, but the chosen point is a stable random point inside the configured
body region and is held for 350–700 ms. Randomizing every tick is not human imperfection; it
is high-frequency jitter, and it makes the camera look less like a hand rather than more.
The small sinusoidal drift is deliberately low-frequency for the same reason.

Vanilla mouse movement happens first. The existing `MouseHandlerMixin` redirect records the
exact deltas passed to `LocalPlayer.turn`, then the module applies deadzone, strength,
velocity limits and acceleration. If a recorded axis points opposite the wanted correction,
that axis yields for the tick. Looking away is therefore an instruction, not noise the
assist fights. Target stickiness is time-based and only applies while the target remains
valid; a dead, hidden or out-of-range target is released immediately.

The final correction calls `RotationManager.assistVisible`. That method intentionally does
**not** claim silent rotation: if Aura or placement already owns the server angle, its packet
continues to win while the user's camera can still move underneath it. Writing this as a
normal rotation request would either turn a visible-only module into a spoof or make it fight
the action whose hit depends on the server angle. The module never clicks, and it makes no
"undetectable" claim — visible camera assistance is still server-observable movement.

### 4.1 Trajectories: one path, three origins

Trajectories now distinguishes **held local**, **held other-player** and **already fired**
paths. That distinction is not just UI: `Ignore first ticks` belongs only to a projectile
whose first points have already happened. Applying it to a held pearl hides the part of the
prediction closest to the player's hand, which is exactly where short throws need the most
clarity. Other-player and fired paths default off because their cost scales with loaded
entities; the local held path remains the cheap default.

Item toggles cover bows, crossbows, tridents, snowballs, eggs, pearls, XP bottles, potions,
fishing rods and wind charges. A fired `AbstractArrow` is classified from its weapon stack,
not assumed to be a bow arrow; otherwise disabling bow paths would also hide crossbow bolts,
or enabling crossbows would silently show every bow shot. Thrown tridents are checked before
the general arrow branch for the same inheritance reason. Multishot rotates the launch vector
through `ProjectilePathUtil.multishot` and adds shooter movement afterwards — rotating the
already-inherited player velocity would bend all three paths around a moving shooter.

`Accurate simulation` controls the entity-AABB broadphase only. Profile constants, fluid
drag, tick order and block clipping never have a cheaper second implementation; turning a
setting off must not create a second physics model. Impact boxes and entity highlights read
the same `HitResult` that ended the shared path, so the line cannot name a different impact
than the marker it draws.

### 4.1 NewChunks: evidence, not provenance

NewChunks never scans terrain to guess its age. The existing `ClientPacketListenerMixin`
feeds it four authoritative events: complete chunk arrival, unload, single block update and
section block update. A complete arrival begins as `UNKNOWN`; after a 20-tick quiet grace it
becomes weakly `OLD`. A post-load block state containing a **non-source fluid** promotes the
chunk to `NEW`, because flowing terrain immediately after load is evidence of generation or
settling. It is still only evidence: pregeneration, server plugins and player-made fluid can
all produce false answers, so the module and setting descriptions never say 100% accurate.
The predicate is pinned by the client gametest against flowing water versus a source block.

The map key is `(dimension id, packed ChunkPos)` and the value carries evidence version 1.
It is a `ConcurrentHashMap` even though the four mixin hooks deliberately run at TAIL on the
main-thread pass; that makes the ownership explicit and prevents a future packet hook from
quietly turning render iteration into a race. A new connection always clears it. Dimension
changes clear by default, while the setting can retain the other dimension's separately
keyed session data. No server identity or chunk classification is persisted to disk.

Keeping unloaded chunks must not make cost grow with exploration history. Rendering loops a
bounded circle around the player's chunk and performs map lookups; it does **not** iterate the
whole history and filter it afterward. UNKNOWN→OLD settling is lazy inside that same bounded
loop. `Keep unloaded = Off` removes on the forget packet; On keeps only the map entry. Smooth
color samples a fixed 5×5 neighborhood, and plane/border both consume the same classification.

### 4.1 Printer: survival supply (v1.9.2)

Creative flies one route over everything the band wants and refills from the creative
packet. Survival cannot: it has to *fetch* material, and fetching is a different problem
that got its own planner rather than more conditions inside the creative one.

**Four rules, and they are the whole policy.** Count what the active layers still need;
rank it; build one material at a time, commonest first; place a block's floor before the
block that stands on it.

- **The count is exact and taken on demand.** `Printer.exactBandNeed()` walks the band and
  compares every position against the world when a trip decides what to fetch — no
  snapshot, no rolling window. It exists because the three cheaper counts that preceded it
  (the per-pass scan, the background tally, the route forecast) each went stale in a
  different way, and every restock bug in the v1.9.2 cycle was one of them being trusted at
  the wrong moment. Capped at 150k cells; a two-layer map art is ~32k, one tick's work.
- **Ranking is support-depth first, then count.** Count alone only looks right on a map art,
  where the floor is also the bulk. A rare support under a common block ranks the wrong one
  first and the pass finds nowhere to put anything. Depth is read off the schematic by
  `noteSupport()`, which asks each block whether it survives *with nothing under it* (tested
  at a sky position, cached per material) — so it is a fact about the block, not about
  whether one particular spot's floor happens to exist yet.
- **A group is what fits in one bag**, taken greedily down the ranking. It can never mix a
  support with what stands on it, because `waitingOnSupport()` skips anything whose floor is
  not yet retired.
- **The trigger is "am I out", not "will I be".** Prediction earns its keep on a mixed
  creative route; a pass carries one material and leaves with all of it, so there is nothing
  to predict. The coverage threshold it replaced (`coverage < min(restockAt, routeLength)`)
  is *unsatisfiable* once the route is shorter than the margin — which is how the printer
  flew to the stash and back for ever over blocks it was already carrying.

`MaterialForecast` (bisection allocation, coverage, route-ordered runs) is still what
**creative** uses and is the right tool there. Survival only borrows it as a carrier for
"here is exactly what to bring".

**Player** — Capes, Honker, PagePirate, AutoExtinguish, AutoXPRepair, AntiHunger, FastUse,
AutoEat (exposes `busy()` — interact modules must yield to it; scores food across the hotbar
**and offhand**, and clears the main hand to an empty slot when eating offhand so the held
right-click can't mis-eat or place a block. **"Skip harmful" replaced the hand-written
blacklist** (2026-08-04): `AutoEat.harmful(stack)` asks whether eating applies a
`MobEffectCategory.HARMFUL` effect or teleports you, so every modded food is covered and no
list can go stale. It takes the **stack**, not the item, and runs at eat time — forced,
because item components are unbound at client init and computing a default there crashes the
client (§6); better, because suspicious stew carries its effects on the stack, so an
effectless bowl is correctly judged safe instead of blanket-banned as it used to be. The
Blacklist setting survives, now empty by default, as the user's own additions), AutoFish,
HotbarLoadout, DonkeyRitual (both restore a saved creative hotbar into survival — see §4.1),
InfiniteInteract (packet-steps into range for the duration of one action and steps back —
brackets `useItem`/`useItemOn`/`interact`/`startDestroyBlock`/`continueDestroyBlock` in
`MultiPlayerGameModeMixin`, HEAD and RETURN, so the step covers exactly the vanilla call)

**Misc** — HudModule, ThemeModule (live accent recolor + menu blur + the global color-picker
input style), AdBlocker,
AntiToS (blacklist: `config/unlucky-antitos.txt`), BookTools, SoundLocator, Spinbot,
Spam (timed chat, presets or custom, rotation + random unique tag to beat duplicate
filters, 0.05s–30s — 0.05 is one tick, the real floor since it's driven from `onTick`),
BibleBot (random verse from `bible-api.com/data/{web|kjv}/random`, fetched off-thread and
sent back via `Minecraft.execute`, one request in flight at a time),
Greentext (prefixes outgoing chat with `>`; the **server** paints it — see §3.4 for the
injection-ordering trap this creates),
InventoryInfo (tooltip suite via a Fabric `ClientTooltipComponentCallback`:
container/shulker grid (`CONTAINER`) + ender-chest grid (client `getEnderChestInventory`
cache) — Slot cells (`slot.png`) or GUI panels (`container.png`/`enderchest.png`, 176×68
9×3); map image on the `map.png` parchment frame (`MapTextureManager.prepareMapTexture`
blit); banner (scaled item render); written-book first page on the `book.png` parchment;
byte-size text line),
Friends (**enabled by default** — `setEnabledSilently(true)` in the constructor, config
overrides; middle-click a player to add/remove, blue `•` before friend names in tablist +
NameTags; backed by `FriendManager`)

*Deliberately absent:* **NoSlow** — deferred by the user; AutoSprint only stops sprint,
it does not implement no-slow. Do not add it opportunistically.

### 4.1 Saved hotbars: HotbarLoadout & DonkeyRitual (v2.1)

Vanilla's Ctrl+1..9 in the creative inventory writes `hotbar.nbt` in the game directory,
and it keeps the **whole** stack — every data component, including ones only a command can
produce. Reading that file back is `HotbarVault`. Getting the stacks onto a server is the
rest of the job, and it is the part vanilla will not do: `handleSetCreativeModeSlot` is the
only handler that takes a client-authored `ItemStack`, and it is gated on
`abilities.instabuild`.

`HotbarLoadout` restores one saved hotbar, three ways:

| Mode | Needs | Notes |
| --- | --- | --- |
| **Creative spoof** | a server that negotiates the bridge channel at handshake (`CarpetBridge.available()`) | One write per stack, no size cap, no chunking, nothing in the command log. Refuses outright when the channel isn't there. |
| **Script run** | `commandScriptACE 0` in `carpet.conf` | `/script run inventory_set(...)`, nothing to install. Note what that setting really grants: *all* of Scarpet to *every* player, not just this. |
| **App command** | `server/scarpet/spawnart.sc` in the world folder | Keeps the blast radius to one command, at the price of a file upload. |

The two Scarpet routes are size-bound and the spoof route is not, which is most of why the
spoof route exists. Commands go out through `sendCommand`, not chat — the command packet is
a bare `readUtf()` (32767) where `MAX_CHAT` is 256, and a component-heavy stack needs far
more than 256 characters. Past 32767 the payload is split: Script run accumulates into a
Scarpet global and joins once, App command uses `begin`/`chunk`/`commit` and rides raw
because the app takes `text` arguments greedily. `HotbarVault.safeDelay` paces the queue
under the server's command spam kick.

`DonkeyRitual` is the same restore dressed as an event. You ride a chested donkey, feed it
filler one block per chest slot, and beat it down; once a full-charge hit is about to be
lethal the real stacks replace the filler and the donkey's own death scatters them.
`AbstractHorse.dropEquipment` loops the whole inventory on death, and `getInventoryColumns`
is 5 with a chest (×3 rows = 15 slots for a hotbar's 9), so the drop is genuine — the items
really are in the chest when it dies.

**The timing is the whole trick.** A container write lands a tick late (it hops to the main
thread), so the killing swing is held `SWAP_SETTLE` ticks after the window opens.
`LETHAL_MARGIN` is 1.5 on purpose: `ATTACK_DAMAGE` knows the weapon but not its
enchantments, so the estimate can run low, and the margin biases the swap a hit early rather
than a hit late. Early costs a slightly longer flash of the real items in a chest only you
can see; late drops cobblestone. No crit term — a mounted attacker never crits.

Spoof-only, no command fallback: the swap has to be instant and atomic, and neither Scarpet
route is. `ChatComponentMixin` drops `SYSTEM_SERVER` lines while the ritual runs (player
chat is a different source and is never touched).

### 4.2 HUD widgets — 23, registered in `HudManager.init()`

Watermark, ArrayList, Coords, Speedometer, Keystrokes, ArmorHud, PotionHud, TargetHud,
Radar, CompassBar (cardinal strip scrolling with yaw; nearby players projected by bearing
as `HeadRenderer` faces, friend dot, distance fade — all in MC yaw space, bearing =
`atan2(-dx, dz)`), InventoryViewer, ItemCounter, ItemPickup, PopCounter, SessionInfo, Info,
PlayerModel, CustomText, **Greeter**, **Brewing** (AutoBrew's read-out, §3.2), **Printer**
(status, placed, missing, rate, ETA,
elapsed; rows word-wrap to a set max width rather than clipping, because a truncated status
line is the one that tells you nothing), **Materials** (what the schematic still needs,
largest first, with icons) and **Layers** (the same, narrowed to the band being built, each
count shown as `left/total` so a small number can be told from a wrong one, with the
materials this pass is committed to highlighted).

Widgets are positioned by fractional screen coords (`setFractions(x, y)`) so they survive
resolution changes. `Greeter` is intentionally **not user-editable** — its text is derived
from time-of-day + username.

**A widget owns its settings** (2026-07-30). They are declared in the widget class via
`HudWidget.add(...)`, and the editor's right-click popup is generated from
`widget.settings()` — so adding a widget is **2 edits**: the class and `HudManager.init()`.
It used to take a third, a hand-written row in `HudEditorScreen`'s `switch`, and the whole
class of bug that removes is options that exist but are unreachable: ArmorHUD's "Armor
offhand" and "Armor vanilla bar" were in no menu at all until the switch was deleted.
`HudModule` keeps only what is genuinely global — the accent gradient and the toast
notifications, which are not a widget.

By convention the **first setting a widget declares is its on/off toggle** (`toggle()`),
which is what the editor's widget list flips on a left click. Settings persist under
`hud.<Widget>.settings` in the config; a pre-move config is still read by name out of the
old `modules.HUD.settings` block, so nothing resets.

**Every widget also inherits a common block** from `HudWidget` (v2.0), appended *after* its
own settings so the toggle-first convention survives: scale, padding, opacity, anchor +
anchor margin, a Fade/Slide/Scale transition with direction and speed, and Background /
Border modes that opt the widget into or out of the shared panel. The panel treatment
itself — opacity, corner radius, border, and the animated accent bar's speed and direction
— lives on `HudModule` and is mirrored into static fields on `Theme`, because the draw
helpers (`Render2D.hudPanel`, `hudAccentBar`) are called from paths that have no widget in
scope.

**Widget copies** (v2.0). `HudManager.duplicate` builds an independent instance of a
built-in widget: settings are copied by name *and* concrete type
(`ConfigManager.copyCompatibleWidgetSettings`), and the copy gets a generated
`duplicate:<uuid>` instance id which becomes its config key — the primary keeps its legacy
name key, so several instances never overwrite each other in the JSON. Restoring is
deliberately narrow: `restoreDuplicate` only reconstructs types that are already registered
as primaries, so config data can never name an arbitrary class to instantiate. `ItemPickup`
is excluded because pickup packets feed the one service instance, and a second would be an
empty shell.

The **editor** (`HudEditorScreen`) draws the real widgets, so anything world-dependent is
gated by `requiresPlayer()` and falls back to a draggable name placeholder — which is what
lets the whole HUD be laid out from the title screen. Around that: a placement grid (one
tiled sprite, never per-dot fills — see §8), safe-area guides, edge/centre/stack snapping
with guide lines (Ctrl for pixel placement), a tool rail (align, lock, hide, reset,
duplicate, preview data), and a draggable widget list. **Preview data**
(`HudManager.isPreviewData`) is a global flag widgets read to fake content, so a widget
that is empty at rest can still be positioned.

### 4.3 Settings & GUI components

Each `Setting<T>` has a matching `GuiComponent`:

`BooleanSetting` · `NumberSetting` · `ModeSetting` · `ColorSetting` · `KeybindSetting` ·
`StringSetting` · `BlockListSetting` · `EntityListSetting` · `ActionSetting`

**`ActionSetting` is the odd one out** — a `Setting<Void>` wrapping a `Runnable`, drawn by
`ActionComponent` as a one-click button. It has no persistent value, so `ConfigManager`
skips it; it exists for the things that are a *verb* rather than a state (DonkeyRitual's
"Preload hotbar.nbt"). Anything that needs to survive a restart is not an action.

**Conditional visibility** — `add(setting, () -> mode.is("X"))` hides a row while the
condition is false, in both the ClickGUI and the HUD editor popup. **Display only**: the
value stays live, stays saved and is still read by the module, so hiding can never change
behaviour. `GroupBox` calls `component.owns(setting)` on every row it builds, which is the
single place that wires it — new component types get it for free. Every loop over
`components` must skip hidden rows, keyboard routing included: a hidden text field would
otherwise keep swallowing keys invisibly.

**`ModeSetting.withLabels(op)`** draws each option through a transform without touching the
stored value (the font pickers show every style written in itself). The value compared by
`is()` and written to config is always the plain mode name, so a label change can't orphan
a saved setting.

**`ui/ColorPicker`** is the one expanded color body, shared by `ColorComponent` and
`HudEditorScreen` — a tab strip picking Picker / HEX / RGB, the choice stored globally in
`ThemeModule.colorMode`. Two traps it exists to hold: HSB is **cached** and only re-derived
when the stored ARGB changes underneath it, because at saturation or value 0 the hue is not
recoverable from the color (dragging Val to the bottom and back would otherwise snap to
red); and alpha is deliberately not editable, matching what the bars always did — a typed
code keeps the setting's existing alpha, though an 8-digit AARRGGBB is accepted.

**Rows that slide open unfold the box.** `GuiComponent.isExpanded()` (overridden by
`ModeComponent` and `ColorComponent`, the only two that grow inline) stops `GroupBox`
applying its fold limit. Without it a dropdown opened near the bottom of a long module grew
the content past the limit and vanished behind the expander dots — the click registered and
the setting was reachable blind, but nothing appeared to happen. It keys off the
**animation**, not the open flag, so the box keeps its room while the list slides shut.

`BlockListSetting` / `EntityListSetting` / `ItemListSetting` open the `BlockPickerPopup` /
`MobPickerPopup` / `ItemPickerPopup`.

**`BlockPickerPopup` is the whole block registry** (2026-08-04), behind five tabs — All,
Ores, Storage, Valuables, **Tags** — plus a `TextBox` search over both the display name and the
registry id, so `diamond` and `minecraft:deepslate_diamond_ore` both find the block. The
three preset tabs are filters over the catalog and their contents are derived, not written
down (§5 `BlockGroups`).

**The Tags tab** lists every block tag the client currently holds (392 in a vanilla world),
with member counts, and a click selects or clears the whole tag. It is what lets the picker
handle content nobody wrote code for — a modded ore in `#c:ores`, a server's own
`#shop:sellable`. Two things it does deliberately:

- **Rebuilt on every open**, unlike the block catalog. Tags are *not* static registry data;
  they arrive over the wire (`ClientboundUpdateTagsPacket`), so the answer differs between
  the title screen (none), singleplayer and each server. Caching would show one world's tags
  in another's. The empty state says where tags come from rather than "No matches" — a menu
  that is blank for a reason still reads as broken.
- **Members are expanded into the selection**, not stored as a live `#tag` reference. The
  setting is a set of block ids a worker thread reads every section compile
  (`XRay.visibleBlocks`); a stored tag would resolve against datapack state that is absent on
  the title screen and different on the next server, so one saved config would mean different
  things in different worlds. Re-click a tag to pick up changes.

It used to offer *only* the three XRay presets plus whatever was already selected, which
meant any block nobody had anticipated could not be added from the GUI at all; the presets
are now filters over the one catalog rather than the catalog itself, and **Add all** adds
everything currently listed (preset tab + empty search reproduces the old preset button,
except additive, so ores + storage is finally expressible). Two details worth keeping:

- The catalog is built once and reused across opens (~1.1k blocks), rebuilt only when
  `ItemUtil.componentsBound()` flips — icons are empty with no world, and a catalog built
  on the title screen would otherwise keep blank icons after joining. Names come off the
  block, so the list is fully usable from the main menu either way.
- **Display names are not unique**: 51 of them cover 102 blocks, every group being
  `{x, x_wall_*}` (`acacia_sign` and `acacia_wall_sign` are both "Acacia Sign"). The wall
  variant is marked `(wall)` — one of the pair is enough — and row labels are clipped with
  `Font.plainSubstrByWidth`, since modded block names are not bounded by vanilla's.

**`ItemListSetting` carries a `Predicate<Item>` filter**, so one popup serves every
purpose — AutoEat's blacklist lists only food, FastUse's custom list lists everything.
`ItemPickerPopup` builds its catalog from the whole item registry on open (skipping items
whose default stack is empty, e.g. air) and has its own `TextBox` search, because even a
filtered registry is long. Any new list-of-items setting needs **no new popup**: pass a
filter. Adding a picker means wiring render/click/drag/release/scroll **and** char/key
routing in `ClickGuiScreen`, plus a `case` in `GroupBox` and both `ConfigManager` switches.

**Text input goes through `ui/TextBox`** — one shared editing engine (caret, selection
via shift+arrows/ctrl+A/click/drag/double-click, ctrl+C/X/V clipboard, ctrl word
jumps/deletes, caret-following horizontal scroll). Users: `StringComponent`, the
ClickGUI search field, `HudEditorScreen`'s text rows. Call sites draw the field chrome
and translate mouse X to text-relative coords; never hand-roll append-only input again.

---

## 5. Support infrastructure (`util/`)

| Class | Notes |
| --- | --- |
| `Render2D` / `Render3D` | Drawing primitives. `Render3D` holds the allocation-free slab math and the `BoxGeom` cache used by the ESPs — **see §6**. |
| `BlockGroups` | The XRay/Search preset categories, **asked of the registry rather than written down** (2026-08-04). A hand-written id list is wrong the moment the game ships a block nobody anticipated, and wrong *silently* — the old `PRESET_STORAGE` named `minecraft:shulker_box` and so covered 1 of 17 shulker boxes and 0 of the 8 copper chests 26.x added. **Ores** = the `_ore` suffix, plus `ancient_debris` by name (no shape to appeal to). Explicitly **not** `DropExperienceBlock`, which is a behaviour and not a category: `SculkBlock` extends it, which put sculk in XRay's default visible set and left ancient cities opaque while X-raying — a bad list presenting as a rendering bug. **Storage** = the block's own block entity is a `Container`, which picks up every modded chest for free. **Valuables** stays curated on purpose — "worth flying across a world for" is a judgement, not a property the registry has — but expands dyed variants. Presets are allowed to be approximate because the picker is the whole registry with a search box, so a miss costs a search, not a release. **Not tags:** tags are datapack state synced from the server, unbound on the title screen, and `c:` conventional tags exist only if the *server* runs Fabric API — which an anarchy server does not. **`storage()` refuses to answer until item components bind** (§6). |
| `MixinAudit` | Asks every mixin in `unlucky.client.mixins.json` whether it reached its target class: ASM reads each `@Mixin` annotation straight from the class bytes (not by loading the mixin — those are the transformer's *input*), then the target is force-loaded (`initialize = false`) and checked for a method carrying Mixin's own `@MixinMerged` naming that mixin. Behind `-Dunlucky.mixinAudit` / `UNLUCKY_MIXIN_AUDIT=true`, plus unconditionally in `ModuleSmokeTest`. **Scope, precisely, because the obvious reading is wrong:** it answers "did this mixin apply to this class", *not* "did each injection find its injection point" — Mixin merges a handler method whether or not the injector bound, so an `@Inject` with `require = 0` pointed at a renamed method leaves a merged, never-called method and this passes. Measured, not assumed. What makes it worth a file is the **three Sodium mixins**: they name targets as *strings*, so those are the only references in the codebase with no compile-time checking at all — Sodium renames a package and XRay-under-Sodium dies silently and forever. Everything else is covered by `defaultRequire: 1`. Baseline on 26.2: **76 targets audited, none dropped** in both vanilla and Sodium client gametests. |
| `TargetingUtil` | The one group/filter/ranker for aim and combat modules. Players/hostiles/passives and the existing entity-type lists feed the same classifier (including 26.2's Mannequin-as-player exception); dead, spectator, invisible, range, FOV and line-of-sight filters then run in one order. **Friends are ignored by default** — safety belongs in the builder default, because requiring every future aura/aim module to remember an opt-out guarantees one eventually will not. Rankings are closest, lowest health, smallest angle, lowest armour, or normalized distance+angle; entity id is the deterministic tie-break so two equal candidates do not flicker with render iteration order. Aura, TargetStrafe and LegitAimbot consume it. |
| `ProjectilePathUtil` | The **only projectile physics implementation**. Named 26.2 profiles carry launch speed/charge, gravity, air/fluid drag, radius and — critically — tick order. Throwable projectiles do gravity → inertia → move; arrows do move → inertia → gravity. Treating both as the same recurrence shifts an arrow by a whole gravity/drag step on tick one, which is why a visually plausible line becomes an aimbot miss. Block clips truncate the entity-AABB query, so something behind a wall cannot win the same segment. `ResultBuffer` is reused because Trajectories can calculate hundreds of points for several players every frame; its Accurate-simulation switch only skips the entity broadphase, never substitutes another recurrence. The old terrain-only `Path` overload remains as a compatibility snapshot. Multishot yaw offsets and the exact bow charge curve live here too, not in consumers. |
| `ProjectileAimSolver` | Ballistic yaw/pitch against a moving target AABB, built **on** `ProjectilePathUtil`. The closed-form parabola only seeds a narrow pitch search: it omits per-tick drag and cannot be the final answer. Every candidate is run through the shared simulator, its segments tested against the target box moved to that tick, then yaw is recomputed once from the simulated flight time for transverse motion. Returns impact, seconds-to-impact, miss distance, obstruction and direct visibility; a caller may require direct line-of-sight independently from the clear ballistic arc. `Workspace` reuses the path buffer across candidates. The trap is copying constants or a second recurrence into BowAimbot — the rendered line and the aim would then disagree while both looked internally reasonable. |
| `RotationManager` | Server-side rotation spoofing, flushed in `onTickEnd()`. **`rotate`/`lookAt` snap** (right for anything that must land this tick, e.g. Aura mid-swing); **`face(target, speed)` walks there** over several ticks and returns true once aimed — call every tick and gate the action on it (**do not set a cooldown while turning**, or the turn stalls halfway). The priority-aware `face(..., priority)` and `rotateIfAllowed(...)` are for actions such as Scaffold that must know whether their angle actually won before clicking; advancing a losing turn locally would report "aimed" while the server still carried Aura's yaw. `assistVisible` is the opposite contract: it adds a clamped camera delta without taking the silent lease, so LegitAimbot can preserve visible input underneath a functional Aura/placement angle. A snap is invisible: one tick is ~3 frames, so nobody sees it, including you in F5 — and an instant 180° is not a thing a hand does. Yaw is visible because `yHeadRot`/`yBodyRot` are written directly; **pitch cannot be**, because a model's pitch and the camera's pitch are the same field (`xRot`) — `AvatarRendererMixin` overrides `state.xRot` at render time instead, which is why the spoof shows without moving the camera. Adopters: AutoBrew faces chests/stand/water; Scaffold turns at `PRIORITY_PLACEMENT`; Aura, AutoXPRepair, Nuker, ObsidianFarm and Spinbot still snap; LegitAimbot alone uses the visible-assist path. **The renderer asks `hasVisualPose()`, not `isSpoofing()`** — a wall-clock 250 ms window stamped at request time, because `spoofing`/`holdTicks` are tick-loop bookkeeping written at end of tick and a frame can land anywhere in that cycle. **A pose is only as visible as it is frequent:** see §6, "A rotation nobody re-asserts is a flicker". **`cancel()`** drops the spoof this instant and hands the camera's real rotation back, for Panic — letting the normal `POSE_HOLD_TICKS` run out would leave a fifth of a second of still-spoofed aim after the key was pressed. |
| `MaterialForecast` | What a route is about to spend, **in the order it spends it** — runs (item, count, waypoint), `coverage`, `firstShortfall`, and `fill()`, which bisects on route distance to answer "given N slots, what mix carries me furthest". Built for the **creative** case: one route through a mixed schematic, where which colour runs out first is a real question. Survival does not have that question (a pass carries one material) and only uses it as a carrier — see §4.1. Two traps live here: `fill` treats anything `obtainable` rejects as **free**, so a material the current chest lacks silently drops out of the costing and the slots go to whatever is behind it; and `topUp` rounds each entry up to the slots it is *already being charged for*, capped by real demand — free capacity, not padding. Speculative padding was removed after a route wanting its last 109 cobblestone came home with 2,029. |
| `ShulkerRestock` | The on-site box cycle: pick a safe spot, land, place, open, pull, close, mine, collect, get the box back. **Landing is not cosmetic** — vanilla multiplies mining time by five off the ground — but flight is only ever cut with solid ground inside ~1.25 blocks and dead centre of the stand spot (`settleAt`; 0.6 tolerance left a shoulder inside the box's space, and a landed player is frozen). Doubles as the at-chest unloader for stash-only mode, driven by `ChestStash` so borrowed boxes never leave the chest's side. `stowTick()` packs surplus into a spare box when a chest refuses it. |
| `ChestStash` | The supply run: fly to chests marked with `.stash`, put back what the print has no use for, come back with what it needs. TRAVEL → OPEN → DEPOSIT → WITHDRAW → CLOSE → UNLOAD → RETURN, with a **borrow-and-return loop** for stash-only — a box in the bag occupies the very slot the unload wants to pour it into, so a round takes about half the free space in boxes, empties them, gives them back, and goes again. Chest contents are remembered per chest and **expire after five minutes**: "one wasted trip corrects it forever" was half right, and the half that was wrong meant refilling a chest mid-print had no effect at all. `beginSurvey()` reads every chest before the first shortage, so the first trip is a fact instead of a guess. Two distinctions this file learned expensively: a trip is judged on **whether it cleared its list**, not on net bag change (it deposits before it withdraws, so a successful run scored 11 and earned a 60-second lockout); and `wanted` (this trip's list) is not `keep` (what the print still needs), or a trip deposits exactly what the last one fetched — cobblestone, carpets, cobblestone, carpets, forever. |
| `ContainerUtil` | The container primitives the modules share: `click`, `takeExactly` (exact counts out of a slot, assembled from the clicks that exist), and `closeMenu()` — "close the menu but leave my GUI alone", which vanilla has no call for, so the close is flagged and `GuiMixin` drops that one `setScreen(null)`. |
| `InventoryActionCoordinator` | **One owner at a time for automated inventory clicks and hotbar switches**, with priorities (`PRIORITY_TOTEM` 100 → `PRIORITY_FARMING` 30). The contract is **check every tick, not acquire once**: a lease is taken from you by anything that outranks you and you are told by `owns()` answering false, never by a callback. Equal priority does *not* evict — two modules at the same rank would otherwise trade the lease every tick and each land one click. **The menu is passed in, never assumed:** every click takes the `AbstractContainerMenu` the caller planned against and is dropped if `isOpen()` says that is no longer the open one, because a click aimed at slot 13 of a chest that closed a tick ago lands on slot 13 of whatever replaced it. `selectHotbar` remembers only the *first* slot of a lease, so a module walking three tools still ends where the player left it. Scaffold acquires `PRIORITY_PLACEMENT` only after the support click and rotation are ready, checks `owns()` again, then releases after the use packet; holding that lease through its place delay would block lower-priority replenishment while Scaffold is doing nothing. **`returnCursor()` only ever puts back a stack we lifted ourselves** — `cursorSource` is written by `click()` and nothing else, so a player mid-drag is invisible to it; without that test the tidy-up would rip the item out of their hand every tick. World/connection identity is held in `WeakReference`s purely to notice a change: a strong one would pin a dead `ClientLevel` alive. Resolved from `UnluckyClient.tick()`. |
| `ExplosionDamageUtil` | **What an explosion at a point would actually do to somebody** — one estimate, because an aura that disagrees with the server about self-damage kills you. **The exposure sampling is vanilla's own:** `ServerExplosion.getSeenPercent` is public, static and touches nothing but `Entity.level()` and `Level.clip`, so the client calls the exact method the server will and the ray sampling can never drift. Two pieces cannot be borrowed and are reproduced with citations: the damage curve lives on `ExplosionDamageCalculator`, whose methods want an `Explosion` whose `level()` is a `ServerLevel` we do not have (`(impact² + impact)/2 · 7 · radius·2 + 1`); and protection goes through `EnchantmentHelper.getDamageProtection(ServerLevel, …)`, same problem, so Protection and Blast Protection are read off the armour by registry key. **That last one is the only genuinely hardcoded rule here and the one most likely to age** — 1 point per Protection level and 2 per Blast Protection has been true for a decade but is data-driven since 1.20.5 and could stop being true without a compile error. Reductions apply in vanilla's order, which is not the intuitive one: difficulty (players only) → armour+toughness → Resistance → enchantment protection. |
| `DamageForecast` | **Damage that has not happened yet but is already decided** — the fall you are committed to, the drop with nothing under it, the crystal in range. Exists because safety modules must not answer those questions differently. The cheap `distanceToGround(entity)` is a centre-column scan for AutoTotem/AutoLog. AntiVoid supplies a predicted AABB to the footprint overload, which casts centre + four inset-corner collider rays; a toe over the ledge still counts as support, but future sideways motion does not inherit support from the player's old column. Fall damage deliberately skips armour (vanilla's fall damage bypasses it) but counts Feather Falling at 3 points a level and Protection at 1. Finds what is going to hurt; asks `ExplosionDamageUtil` how much. |
| `MovementActionCoordinator` | **One final synthetic player-velocity decision per tick.** Callers submit a transform every tick; AntiVoid's safety priority outranks dodge/travel, equal priority keeps the first owner, and the winner is applied to the velocity left after all ordinary module ticks. Applying it earlier would let an alphabetically later movement module restore the dangerous velocity. Requests expire after resolution and Panic resets the pending owner. |
| `PacketQueueManager` | **The only owner of buffered outgoing gameplay packets.** Its allowlist names movement and seven action packet classes; everything else stays live by default, so keepalive, teleport-confirm, chat/signing, login/configuration, inventory and resource-pack traffic cannot be captured by an over-broad package/name test. One lease owns the queue, with hard tick/size caps and callbacks for limits/server correction. The outgoing redirect sits after `RotationManager`'s variable rewrite, then flush uses the underlying `Connection` so the stored rotation/sequence is not transformed a second time. World/connection identity changes discard, never flush. The last server-confirmed position is recorded at TAIL of vanilla's correction handler, after relative coordinates have been resolved. Panic also discards: flushing a burst of hidden actions is the opposite of panic. |
| `OffhandManager` | **Who decides what is in your offhand.** Unlike a hotbar switch the claim lasts — a totem sits there for a fight — so it is a per-tick *request* model (`request(holder, priority, predicate, label, restore)`), resolved at end of tick like `RotationManager` so "highest priority wins" holds regardless of registration order. Stop asking and you are done; whatever you displaced goes back, which makes the common case one unconditional call inside an `if` with no release path to forget. **Only the first displacement is remembered:** hand the offhand from AutoReplenish to AutoTotem mid-fight and unwinding the *later* one gives you back what AutoReplenish put there, while unwinding the first gives you back the shield you were actually carrying. Wanted items are a `Predicate<ItemStack>`, not an `Item`, so a caller can insist on components too. **A foreign container blocks everything** — the swap is a click on the player's own inventory menu, and while a chest is open that is not the menu the server has us in (the desync `AutoXPRepair.restore()` already guards against); `isBlocked()` says so out loud so a caller that cannot wait can close the container itself. |
| `WeatherOverrideManager` | **One owner for the existing weather hooks.** `SERVER`, `CLEAR`, `RAIN`, `THUNDER` and `SNOW` state includes independent rain/thunder levels plus precipitation/effect/sound/flash policy. NoWeather is the transitional requester and can still hide its two channels independently. The trap is adding Weather hooks beside it: two RETURN writers on `getRainLevel` have no useful ordering, and a common `Level` hook that forgets the client-level identity check also rewrites the integrated server's weather. |
| `LogSpam` | Drops Litematica's `[WorldRenderer]` per-frame chunk logging. **Not our logging** — its own `debugLogging` is already off and these lines are unconditional in the 26.2 build — but a schematic chunk rebuilds on every block change, so *printing* writes two lines per placement batch, on the render thread. Scoped to that one prefix on that one logger; delete the class and its one call when Litematica stops. |
| `FlightPath` | Bounded 3D A* (6-connected, Manhattan heuristic, 4000-node budget with a best-effort partial path) plus `smooth()` and `fitsAt(Vec3)`. The Printer's detour finder. **Sample the body at the fractional position, never floored to a block** — flooring offsets the path down into the floor, which is what made the printer clip corners (Lucien diagnosed that one). |
| `CapeManager` | Cape packs for the Capes module. Streams Mojang capes + a **live GitHub pack** from `lucieneth/Capes`, cached to `config/unlucky/capes/`. Exposes `revision()` so the picker rebuilds when the async fetch lands. |
| `FriendManager` | The friends list: UUID → last-known name in `config/unlucky/friends.json`, lazy-loaded, saved on every change. UUID-keyed so friendships survive name changes. `COLOR`/`TEXT_COLOR`/`DOT` constants are the one source for the friend accent (0xFF4A9BFF). Local-only for now — capes ship via the registry; cross-server presence is still open (plan.md) but this file stays the source of truth. |
| `HeadRenderer` | 2D face+hat from just a UUID (`PlayerFaceExtractor` blit). Tablist skin fast path; otherwise vanilla `PlayerSkinRenderCache` + `ResolvableProfile.createUnresolved(uuid)` — async download, Steve/Alex until resolved, never blocks. ARGB-tintable. Used by chat heads, CompassBar, locator bar + sprite fallback. |
| `PlayerSprite` | **Exact clone of SkinSprite Studio's renderer** (recipe recovered via calibration skins — coordinate-encoded templates through the site, every pixel decoded; ~3/255 err vs ground truth). 24x33 yaw-ortho face rects + box-filter/coverage-blend + the site's signature **12% luma desaturation** + 1px outline (26x35 final). Async per UUID: `config/unlucky/sprites/` disk cache (1-day refresh, format check) or sessionserver → download → compose → `DynamicTexture`; `get()` null while cooking. Friends GUI row icons. Full recipe table in the class javadoc. |
| `PlacementSolver` | Decides **which click** yields a wanted `BlockState`, by asking vanilla instead of encoding rules. Enumerates plausible clicks (6 faces × 3 points up the face × the player's facing then all 4 compass dirs × level/up/down), runs each through a `BlockPlaceContext` subclass that answers for a *simulated* rotation, and keeps the click whose `Block.getStateForPlacement` matches. `distance(from, to)` counts disagreeing properties, with **numeric properties counting their difference** — that one detail is what makes "snow needs 2 more layers" register as progress instead of just wrong, and it's the loop guard: a click is only sent if it strictly reduces the distance. Consequence: orientation (stairs/logs/hoppers), stacking (snow/candles/sea pickles), slab→double, and Scaffold's support-face search all use the same vanilla answer with **zero per-block special cases** — the trap both reference printers fell into. The chosen rotation is spoofed via `RotationManager` so the server derives the same state. |
| `LitematicaBridge` | The **only** file that names a `fi.dy.masa` type, and the whole Litematica integration: `present()` (loader lookup), `hasSchematic()`, `required(pos)` (the state the schematic wants), `withinLayerRange(x,y,z)`. Litematica is `compileOnly`, so it may be missing at runtime — every method answers safely when it is, and the calls live in a nested `Impl` class so the JVM never resolves Litematica's classes unless `present()` is true. **See §6 for the init-order trap.** |
| `alts/` | **Alt account switcher** (PandoraLauncher-referenced, done.md Phase 14): `AltAccount` (Microsoft w/ MSA refresh token, or offline username), `AltManager` → `config/unlucky/alts.json` (**sensitive** — MS tokens; default Azure client id embedded, overridable), `MicrosoftAuth` (device-code OAuth → Xbox → XSTS → MC token → profile; user signs in on Microsoft's page, no passwords in-code; refresh-token silent re-auth), `AccountSwitcher` (swaps `user`+`profileFuture` **and rebuilds the account-bound services** — `userApiService`/`userPropertiesFuture`/`profileKeyPairManager` — via `MinecraftAccessor`, so Realms/registry see the switched session as authenticated; blocks mid-multiplayer). UI in `gui/alts/` — title-screen right panel (zombie/first-alt preview) + `AltsScreen` with a **⟳ per-account session refresh**. |

**`util/net/` — the Unlucky registry (done.md Phase 16).** A public, cosmetic directory: who runs Unlucky and their cape/marker colour. `UnluckyApi` publishes this client's own `{uuid, name, cape, color}` (`PUT /v1/profile`) and does the batched tab-list lookup (`GET /v1/users`); `RegistryUsers` caches the roster (20s user TTL, exponential miss-backoff for non-users, 15s isolate memo). No Mojang handshake — Mojang's WAF 403s that call from Cloudflare's IPs, so identity is **trusted, not verified** (cosmetic stakes; profile-key signing is the documented no-egress upgrade). Backend in `server/` (Cloudflare Worker + KV, `api.unlucky.life`, deploy via `server/DEPLOY.md`). The `UnluckyUsers` module (Category.MISC, on by default) drives publish + poll and renders the ✦ marker (tab + nametags, in each user's chosen colour) and other users' capes (resolved from mojang/GitHub by `CapeManager`, never hosted by the registry). **`UnluckyApi.writesAllowed()` blocks writes from a dev environment still pointed at production** (2026-08-04): the module is on by default and publishes every 5s while connected — and singleplayer counts as connected — so every `runClient` and *every CI gametest* was putting a fictional "Player0" into a directory of real players. There is no new flag, because the opt-in already existed: overriding `unlucky.api` / `UNLUCKY_API` counts as intent, so `run-local-api.bat` still publishes to its local Worker. Enforced inside `setProfile` so nothing can route around it, **and** checked in `publishOwnCape` — not for safety but for the retry loop, since `published` is only set on success and a refused write would re-fire every poll with a toast each time. Reads stay on; a dev client that cannot see other people's capes cannot test the feature. `ModuleSmokeTest` asserts writes are off, because a working guard is invisible in a passing log. |
| `skinlayers/` | **3DSkinLayers** (tr7zw/3d-skin-layers recreation, done.md Phase 13): `SolidPixelWrapper` turns each overlay region into per-pixel voxel cubes (neighbour face-hiding incl. around box edges, corner-triangle z-fight fix, solid-vs-translucent rules), `VoxelMesh` bakes them to a flat `float[]` and `writeTo(PoseStack.Pose,…)` streams to a VertexConsumer (deliberately not a ModelPart — Sodium/Iris-proof), `SkinLayerMeshes` caches six meshes per (skin, slim) (FAILED sentinel for HD, retry for not-yet-downloaded). `SkinLayer3DFeature` is the avatar render layer (poses each part off its animated base part + the mod's offset table, submits via `submitCustomGeometry`). Third-person only so far (module `SkinLayers3D`, default off pending visual check); first-person hands are 13.3. |
| `MinecraftServicesApi` | The real account skin/cape API (`api.minecraftservices.com`, bearer = in-game session token): GET profile/owned capes, POST skin (URL or multipart PNG), DELETE skin, PUT/DELETE active cape, sessionserver skin-of-player. Async, client-thread callbacks, Mojang `errorMessage` surfaced. Drives `gui/skins/SkinsScreen` (staged changes, Apply chains skin→cape→re-fetch) and the `TitleScreenMixin` panel; `SkinRender` is the shared look-at-mouse model draw. |
| `GuiMessageSender` | Duck interface stitched onto the `GuiMessage` record by `GuiMessageMixin` — carries the chat-head sender across re-flows. |
| `BrewQueueSetting` (+ `BrewQueuePopup`, `BrewQueueComponent`) | AutoBrew's ordered brew list. A **`List`, not a `TreeSet`** like the other list settings, because both things a set discards matter: queue order, and duplicates-as-counts. Entries are `container\|potion\|count` — the first two halves are exactly `BrewingSolver.key`, so an entry is a key with a count glued on. The popup's catalog is the solver's reachable set (so it can't offer what the stand would refuse) and each row's icon is the **real potion stack**, which vanilla tints for free — you pick by colour, not by reading names. Left-click +1, right-click −1. Follows the `ItemPickerPopup` shape; needs the same five wiring points (setting → `GroupBox` → component → `ClickGuiScreen` dispatch → `ConfigManager`). |
| `BrewingSolver` | Derives brewing chains for **AutoBrew** by BFS from a water bottle. Deliberately does **not** read `PotionBrewing`'s mix lists (they're private anyway) or model the rules — it calls the public `PotionBrewing.mix(reagent, input)`, *the same method the stand calls*, and reads what comes out. The oracle can't disagree with the stand, needs no accessor, and picks up datapack/mod mixes for free. Reagent universe comes from the public `isIngredient` over `BuiltInRegistries.ITEM`; ~2k `mix()` calls, cached per `PotionBrewing` instance (which is rebuilt per world). **Container-mix reagents are sorted last on purpose** — BFS ties break on insertion order, and "gunpowder first, then brew the splash water bottle" is exactly as short as the conventional chain, so without the sort every chain starts with gunpowder and ordinary Awkward Potions fall off-chain and can't be reused. Labels come from the **registry key**, not the display name: `strength` and `strong_strength` both render as "Potion of Strength". **Worked example of why the oracle earns its keep:** Turtle Master brews from `Items.TURTLE_HELMET` — the wearable helmet, whose display name is "Turtle Shell" — and *not* from turtle scute, which appears in no mix at all. A hand-written recipe table would have said scute and been wrong; the solver simply asks and gets it right. (Turtle helmets also don't stack, the only non-stackable reagent in play, so they take `takeExactly`'s `count <= n` fast path.) |
| `ChatFont` | Unicode letter substitution for chat (Small caps / Fullwidth / Bold / Script / Fraktur / Circled / Upside down) plus `fit()`, the surrogate-safe trim to the 256-char cap. `MODES` is varargs-ready like `PingSound`. **Every glyph was checked against 26.2's bundled `unifont_all_no_pua` before being listed** — see §6 for how, and for the two traps the tables exist to avoid. |
| `ChatUtil.say()` | Sends a line to the server as if typed: routes a leading `/` through `sendCommand` (`sendChat` would send the slash as literal text), and **refuses** a leading `.`+letter, which `ChatCommandMixin` would eat — an automated sender would otherwise look like it was working while silently running commands at itself. Returns false so callers can report it. |
| `PingSound` | The alert sounds modules ping with (ChatTag, GamemodeNotifier), so the option list and the lookup live in one place. `MODES` is varargs-ready for `new ModeSetting(…, PingSound.MODES)`. Exists mainly because `SoundEvents` mixes `SoundEvent` and `Holder<SoundEvent>` field types — **see §6**. |
| `discord/` | **DiscordRPC** (done.md Phase 17): `DiscordIpc` is the transport — hand-rolled, zero deps, Windows named pipe (`\\.\pipe\discord-ipc-N`) via `RandomAccessFile` or a unix domain socket elsewhere, framed as 4-byte LE opcode + 4-byte LE length + UTF-8 JSON, probing sockets 0–9. `DiscordRpcThread` is a **daemon thread that owns the socket** so the render thread never touches IO; the module parks a `Presence` record via `AtomicReference` and the thread pushes real changes only (record equality = the diff). Discord being closed is the normal case: retries every 30s, silently, forever. |
| `ChamsRenderType` / `ChamsRenderState` | Custom no-depth pipeline + the state bridge. `init()` must run early (it does, first line of `UnluckyClient.init()`). |
| `ItemUtil` | `icon(ItemLike)` — an `ItemStack` when the item's components are bound, `ItemStack.EMPTY` otherwise, plus `componentsBound()` for code that needs the components themselves (names, `components().has(...)` filters). **Every GUI that can be open with no world goes through it** — the HUD editor's previews, module toasts, the block and item pickers. See §6. |
| `FreecamRenderProxy` / `FreecamProxyRenderState` | Marks the one synthetic player extraction used by Freecam's F5 spectator head (`ThreadLocal` depth counter), so `AvatarRendererMixin` can pose *that* state as a spectator head while the real local-player state stays untouched at its world position. |
| `gui/FrameBlur` + `gui/BlursBackground` | Hands out the single blur a frame is allowed, and the marker interface that lets the HUD know a screen above it wants it. See §6. |
| `gui/chat/ClientCommandChatUi` | The completion list for dot commands: per-`EditBox` state in a `WeakHashMap`, the input accent, and the suggestion popup. Only ever engaged for the syntax `ChatCommandMixin` claims. |
| `SessionTracker` · `ServerStats` | Kills/deaths, TPS, ping. |
| `WorldScan` · `InteractUtil` · `MoveUtil` · `CombatUtil` · `GearUtil` | Shared helpers. |
| `Theme` · `ColorUtil` · `Animation` · `Easing` | Visual layer. |
| `TextBox` (`ui/`) | Shared single-line text-edit engine for all GUI text fields — see §4.3. |

---

## 6. Hard-won 26.2 API notes

These have each cost real debugging time. **Trust this list over your priors.**

**Combat / crits** (decompiled from the named jar — see `Phase 17` in done.md)
- The whole critical-hit condition now lives in **`Player.canCriticalAttack(Entity)`**
  (private): `fallDistance > 0 && !onGround() && !onClimbable() && !isInWater() &&
  !isMobilityRestricted() && !isPassenger() && target instanceof LivingEntity &&
  !isSprinting()`, gated on `getAttackStrengthScale(0.5f) > 0.9f`.
- **`!isSprinting()` is part of it** — sprinting cancels crits (it becomes a knockback
  hit instead). Anything crit-related has to w-tap, and the `STOP_SPRINTING` packet
  must be sent *manually*: `setSprinting(false)` alone won't reach the server until
  `LocalPlayer.tick()` next runs, which is after the attack packet has already gone.
- `Player.isMobilityRestricted()` is **public** and is just `hasEffect(BLINDNESS)`.
- `Entity.fallDistance` is a **`double`** now (was `float`).
- There is **no pre-hit damage signal client-side**. `ClientboundDamageEventPacket` is
  the server reporting a hit it already applied, and a player's swing packet goes out
  as their hit lands. Anything "reactive" is a combo-breaker, not a dodge (see `Dodge`).

**Records where you expect getters**
- `GameProfile` is a **record**: `.name()` / `.id()` — **not** `getName()` / `getId()`.
- `ResourceKey<Level>` uses **`.identifier()`** — **not** `.location()`.

**Sounds**
- `SoundEvents` mixes plain `SoundEvent` fields (`UI_TOAST_IN`, `EXPERIENCE_ORB_PICKUP`,
  `AMETHYST_BLOCK_CHIME`) and `Holder.Reference<SoundEvent>` fields (`NOTE_BLOCK_PLING`,
  `NOTE_BLOCK_BELL`, `UI_BUTTON_CLICK`). `SimpleSoundInstance.forUI` overloads both, so
  either resolves — but the field type is not what you'd guess. `util/PingSound` wraps this.

**Containers & brewing**
- **One container open at a time**, and every click is validated against the
  `containerId` the server opened. "Reach into a chest while the stand is up" does not
  exist — `AutoBrew` cycles open/close instead, and takes its decisions about a
  container *while that container is open*.
- `ClientboundOpenScreen` carries a title and a menu type but **no `BlockPos`**. The
  only honest place to learn which block a menu belongs to is the `useItemOn` click
  itself; reading `mc.hitResult` when the menu arrives agrees right up until the player
  turns their head during the round trip.
- `LocalPlayer.closeContainer()` is **public** (it's `protected` on `Player`), and also
  clears the screen.
- Menu slot indices: `BrewingStandMenu` is bottles `0-2`, ingredient `3`, fuel `4`, then
  the player — all **private** constants, so they're restated in `AutoBrew`. To tell the
  player's slots from the container's, test `slot.container instanceof Inventory`
  (`Slot.container` is a public final field) — that needs no constant and works for any
  container size.
- `PotionBrewing.hasMix(input, reagent)` and `PotionBrewing.mix(reagent, input)` take
  their arguments **in the opposite order**. Both are public; `Level.potionBrewing()` is
  available client-side.
- `quickMoveStack` on a brewing stand **offers the fuel slot first**, and blaze powder is
  both a fuel and a reagent — so shift-clicking blaze powder can never load the
  ingredient slot. Use an explicit pickup-and-place there.
- `BottleItem` raycasts for fluid **on the server**, from the rotation you sent it, so
  filling bottles needs a `RotationManager` spoof, not a client camera nod.
- **There is no "move n items" click.** QUICK_MOVE always takes the whole stack, so
  asking a chest for 7 of something hands over all 64. `AutoBrew.takeExactly` builds one
  out of the clicks that exist: PICKUP the stack onto the cursor, right-click (`PICKUP`,
  button 1) n times to drop them one at a time, then PICKUP the source again to put the
  remainder back — all in one tick, so no cursor survives a tick.
- `BrewingStandMenu.getBrewingTicks()` is the **remaining** brew time, which is what
  makes multi-stand scheduling cheap: park a busy stand for exactly that many ticks and
  go work another one.
- **A menu exists before its contents do**, and the gap is a *lie that looks like data*.
  `ClientboundOpenScreen` builds the menu; `ClientboundContainerSetContent` fills it.
  In between, a freshly opened brewing stand reads as no bottles, no ingredient,
  `getFuel() == 0`, `getBrewingTicks() == 0` — indistinguishable from a real idle empty
  stand. **Test `menu.getStateId() != 0`**: the client builds a menu with 0 and only the
  server's content packet stamps a real one. AutoBrew gates `ensureOpen` on it; without
  that it re-opened a stand 400 ticks into a brew, saw "empty and idle", tipped more fuel
  in, loaded more bottles on top, and never saw a finished potion to pull out.
- **A container's block entity exists client-side but its inventory is empty** — the
  server only sends contents for a container you have *open*. So a block scan can settle
  a brewing stand outright (it's a stand by its block) but can only ever *nominate* a
  chest; you have to open it to see inside. `AutoBrew.scan()` / `peek()` split along
  exactly that line.
- `Item.getName(ItemStack.EMPTY)` returns an **empty string** — use
  `new ItemStack(item).getHoverName()`. Shipped a batch of blank "out of " messages.

**Screens**
- `mc.gui.setScreen(...)` / `mc.gui.screen()` — **not** `mc.setScreen` / `mc.screen`.

**Renames from 1.21.x**
- `PlayerRenderState` → `AvatarRenderState`
- `GuiGraphics` → `GuiGraphicsExtractor`
- `HttpTexture` → `SkinTextureDownloader`

**Records**
- `GameProfile` is a record → `.name()`, not `.getName()`.

**Deferred entity rendering**
- `extractRenderState` (has the `Entity`) and `submit` (has the model) are **different
  phases**. Anything you need in `submit` must be stashed on the render state during
  extract. This is the entire reason `ChamsRenderState` exists.

**Eating is driven by the use key, not by packets** *(`AutoEat`)*
- `Minecraft.handleKeybinds` starts a use from **two** places: the `consumeClick()` loop
  (a fresh press) and an `isDown()` branch (a held key). It also calls
  `releaseUsingItem` the moment `keyUse.isDown()` goes false. So calling
  `gameMode.useItem` yourself gets cancelled a tick later.
- `KeyMapping.setDown(true)` on `options.keyUse` is the whole trick: vanilla then handles
  the animation, timing, sounds, and the carried-slot sync (`ensureHasSentCarriedItem`
  runs inside `useItem`). Release the key to stop eating.
- **Consequence:** while AutoEat holds that key, our `startUseItem` hook fires every tick.
  Interact modules must yield — `AutoEat.busy()` is the guard, checked first in
  `MinecraftMixin`'s right-click handler. Nuker will need the same courtesy.

**AutoFish reads the server's own "a fish bit" signal** *(`AutoFish`)*
- The bite arrives as a `ClientboundSoundPacket` for `SoundEvents.FISHING_BOBBER_SPLASH`
  at the bobber's position. Cheaper and more reliable than watching `FishingHook`'s
  private `nibble`/`currentState`. Check the sound landed near **your** `player.fishing`
  hook, or someone else's catch reels your line.

**Mixins run whether or not the module is on — always check `isEnabled()`**
- Every hook fires for all players, all frames, forever. The module object exists from
  boot; its settings hold their defaults. A hook that only consults settings is *always
  active*.
- Shipped bug (2026-07-10, fixed same day): `Jesus.standsOn` checked `mode.is("Solid")`
  but not `isEnabled()`. Mode defaults to Solid → `canStandOnFluid` returned true always
  → `shouldTravelInFluid` false → **swimming was broken with the module disabled**: you
  sank and jump did nothing. Nothing looked wrong on the surface because the collision
  half needs vanilla's `isAbove` check, which fails once you're submerged.
- Put the check in the mixin *and* in any module method a mixin calls. Cheap, and the
  failure mode is silent.
- Second instance of the same class (2026-07-12, fixed same day): `UnluckyClient.renderHud`
  called `PlayerESP.renderOverlay` / `NameTags.renderOverlay` unconditionally every frame
  and neither checked `isEnabled()` — a *disabled* PlayerESP would still box every player.
  Invisible in singleplayer only because `targets()` skips the local player, so the loops
  came up empty when testing alone. The `isEnabled()` early-out lives *inside* each
  `renderOverlay` (mirrors how `ModuleManager.tick` gates `onTick` centrally).

**Never draw a pattern with per-element `g.fill` — the 26.2 GUI renderer chokes on state COUNT**
- Every `fill`/`blit`/`text` call becomes its own render-state object in the extract
  pipeline, and the GUI renderer's element-processing cost grows superlinearly with the
  state count. The HUD editor's dot grid (one 1px `fill` per dot, ~1.6k at dev size) pinned the
  whole game at **30 fps** — while the extract half measured only ~0.95 ms, i.e. the time
  went to the renderer consuming thousands of states, not to submitting them.
- Fix pattern: bake the repeating pattern into a **GUI sprite with `tile` scaling**
  (`assets/unlucky/textures/gui/sprites/<name>.png` + `.png.mcmeta` `{"gui": {"scaling":
  {"type": "tile", "width": 16, "height": 16}}}`) and draw it with ONE
  `blitSprite(GUI_TEXTURED, id, x, y, w, h, tint)` — the tile dispatch produces a single
  `TiledBlitRenderState`. Editor went 30 → ~255 fps. Sprites under `textures/gui/sprites/`
  are auto-stitched into the GUI atlas; the sprite id has no `textures/gui/sprites/` prefix
  (`unlucky:hud_grid`). Verified against `GuiSpriteScaling`/`GuiGraphicsExtractor.blitSprite`
  in the 26.2 sources.

**Walking on fluid needs THREE things** *(`Jesus`, `LivingEntityMixin`)*
`LiquidBlock.getCollisionShape` grants a collision box only when **all** hold. Miss any one
and the fluid stays passable — each omission is a bug we shipped on 2026-07-10:
1. **`LivingEntity.canStandOnFluid(FluidState)`** → true (reached via
   `CollisionContext.canStandOnFluid`), *and* the block above isn't the same fluid
   (`!above.getType().isSame(...)` — you must be at the top of the column). Only
   **source** blocks qualify at all: `LEVEL != 0` returns `Shapes.empty()` early, so
   flowing water is never walkable.
2. **`LivingEntity.getLiquidCollisionShape()`** → a **non-empty** shape. The base class
   returns `Shapes.empty()`, which is why (1) alone collides with *nothing*. The strider
   overrides it with `Block.column(16, 0, 8)` (a half-height box); we return a box up to
   `8/9` so the player stands on the rendered water surface rather than hovering above it.
   `LiquidBlock` maps the colliding entity through this method to get the shape.
3. **`CollisionContext.isAbove(shape, pos, true)`** → `entityBottom > pos.y + shape.maxY - 1e-5`,
   i.e. **your feet are already above that shape's top face.**
- A strider never submerges, so vanilla never has to solve (3). We do. Answering (1) also
  flips `shouldTravelInFluid` off — **swim physics disappear** — so a submerged player with
  no lift sinks forever and jump does nothing. Jesus lifts (`setDeltaMovement`) while fluid
  stands over the feet, then vanilla's collision holds them flat.
- Symptom guide: *sinks forever* = missing (2) or the lift. *Bobs like a cork* = the lift
  is targeting a height below the shape's top face, so collision never engages.
- Measure submersion with **`Entity.getFluidHeight(tag)`** = `fluidTop - aabb.minY`, metres
  of fluid above the **feet**. Anything eye-relative (`isUnderWater()`) settles the player
  chest-deep, because eyes sit ~1.62 above the feet.
- Module `onTick` runs on `END_CLIENT_TICK`, so velocity you set is consumed by *next*
  tick's `travel`, which subtracts gravity (~0.08) first. A lift ≤ 0.08 never rises.

**Riptide's dash is client-side; the throw is server-side** *(`TridentFly`)*
- `TridentItem.releaseUsing` gates on `EnchantmentHelper.getTridentSpinAttackStrength() > 0`
  **and** `isInWaterOrRain()`, and with no Riptide enchant it *throws the trident*. Do not
  route TridentFly through it. We apply the dash ourselves on right-click and cancel the
  vanilla use (`Minecraft.startUseItem` HEAD) so nothing gets thrown.
- `Player.startAutoSpinAttack(int ticks, float damage, ItemStack)` plays the spin; it's
  purely cosmetic here.

**Two modules can't both `cancellable`-inject the same point** *(`MinecraftMixin`)*
- Callback order at one injection point is undefined, and one handler's `ci.cancel()` does
  not stop the others from running. ClickTP + TridentFly both want right-click, so they
  share **one** handler with an explicit priority. A HEAD cancel means the `RETURN` inject
  (FastUse) never runs — which is the behaviour we want.

**Client-authoritative position, with a leash** *(`ClickTP`)*
- Setting `player.setPos(...)` is enough to teleport; the next `sendPosition` carries it.
  But the vanilla server rubber-bands a single tick's movement past its
  "moved too quickly" threshold, so hops are capped (default 8, max 10 blocks).
- `Minecraft.hitResult` only reaches your interaction range — raycast with
  `Entity.pick(distance, partialTick, fluids)` to target anything further.

**`Level` is shared with the integrated server** *(`LevelMixin`)*
- `net.minecraft.world.level.Level` is a **common** class: in singleplayer the integrated
  server's `ServerLevel` runs the exact same mixin code in the same JVM. Any hook there
  must check `(Object) this == Minecraft.getInstance().level` or you'll be rewriting the
  server's own state. (Same trap applies to any future common-class mixin.)
- Weather rendering reads `getRainLevel`/`getThunderLevel`; the **particles and ambient
  rain sound** come from `ClientLevel.tickWeatherEffects`, and the lightning **screen
  flash** from `Level.setSkyFlashTime`. Three separate hooks, one module.

**`FogData` has two independent channels** *(`FogRendererMixin`)*
- `renderDistanceStart/End` — the far fog `setupFog` writes directly; pulled in close it's
  also what makes the Nether/End feel closed in.
- `environmentalStart/End` — written by vanilla's `FOG_ENVIRONMENTS` list, one class per
  cause (`WaterFogEnvironment`, `LavaFogEnvironment`, `PowderedSnowFogEnvironment`,
  `BlindnessFogEnvironment`, `DarknessFogEnvironment`).
- **Clear them separately.** The old code blanked all four fields for any trigger, so
  disabling water fog also wiped render-distance fog.
- Module split (Lucien's call, 2026-07-10): **NoFog** = fog from *where you are*
  (Distance, Nether, End — dimension checked via `level.dimension() == Level.NETHER/END`).
  **NoRender** = fog from *what's happening to you* (water/lava/powder snow/blindness/
  darkness), alongside its screen overlays. There is no `NetherFogEnvironment`; the
  dimensional haze needs **both** channels cleared.

**Silent rotations: yaw has a spare field, pitch does NOT** *(`RotationManager`, `AvatarRendererMixin`)*
- Third-person model rotation comes from three render-state fields: `bodyRot` ←
  `entity.yBodyRot`, `yRot` (head) ← `entity.yHeadRot`, `xRot` (pitch) ← `entity.getXRot()`.
- Yaw is separable: `yHeadRot`/`yBodyRot` are distinct from the camera's `getYRot()`, so
  `RotationManager.onTickEnd` pokes them and the body/head visibly turn while the
  first-person camera stays free. **Pitch is not**: `xRot` *is* the camera pitch — there's
  no `xHeadRot`. Set `player.setXRot()` and you tilt the actual camera, breaking "silent".
- So the third-person model always aimed at body height regardless of Aura's target point
  (Head/Feet only changed the *server* pitch, invisible locally). Fix: override
  `state.xRot = RotationManager.getPitch()` for the local avatar in
  `AvatarRenderer.extractRenderState` while spoofing — the render state is per-frame and
  camera-independent, so the model tilts correctly and first person is untouched.
- Yaw is deliberately **not** overridden there — `yHeadRot` already carries it (smoothly,
  via `yHeadRotO` interpolation), and forcing `state.yRot` would make Spinbot's spin snap
  per tick instead of interpolating.
- The pumpkin/head-equippable overlay is `Hud.extractTextureOverlay` (data-driven from
  `Equippable.cameraOverlay()`); the *in-block* overlay is `ScreenEffectRenderer`'s
  `submitBlockSprite`. Two different things — NoRender has a toggle for each.

**Potion-icon HUD geometry** *(`HudManager.applyPotionAvoidance` / `potionBand`)*
- To slide HUD widgets clear of the vanilla status-effect icons you must reproduce
  `Hud.extractEffects` layout exactly: each icon background is 24×24, icons step **25px**
  leftward from the right edge (`x = guiWidth − 25·index`), beneficial effects sit on a top
  row at `y = 1` (`+15` in demo), harmful effects on a second row **26px** lower. So the
  band is `left = guiWidth − 25·max(beneficial, harmful)`, `bottom = (harmful>0 ? 27 : 1) + 24`.
- Only effects with `MobEffectInstance.showIcon()` count; beneficial vs harmful is
  `getEffect().value().isBeneficial()`. Icons hide (→ no band) when a screen with
  `showsActiveEffects()` is open (inventory), matching vanilla.
- Avoidance is a per-widget eased Y offset (`HudWidget.setTargetPush` + a nanoTime-based
  exponential ease, so it's frame-rate-independent). The manager cascades top-down over
  widgets whose *column* overlaps the band: a pushed widget extends the "floor" for the next
  one **only** if they're within 8px (a stack), so tightly-grouped widgets move together and
  keep their gap while unrelated widgets below stay put.

**Chat is two elements: message log (green) + input bar (red)** *(`ChatSlideMixin`, `ChatInputSlideMixin`, `HudManager.avoidChat`, `ChatAnim`)*
- The **log** (messages + dark backing) renders through the deferred `ChatComponent.extractRenderState`
  (7-arg, public), which both the HUD (`DisplayMode.BACKGROUND`, every frame) and the open
  `ChatScreen` (`FOREGROUND`) call. `ChatSlideMixin` translates its pose (HEAD push+translate,
  RETURN pop) to slide the log **in from the left** on open. It does **not** push the HUD.
- The **input bar** is drawn by `ChatScreen.extractRenderState`: `fill(2, height−14, width−2, height−2)`
  then the EditBox + suggestions, with the FOREGROUND log call *in between*. `ChatInputSlideMixin`
  slides the bar **up from the bottom**, but must bracket its translate **around** that middle log
  call (push@HEAD, pop before the `ChatComponent.extractRenderState` INVOKE, push after it, pop@RETURN)
  — otherwise the FOREGROUND text gets both the red up-slide and its own green left-slide, desyncing
  it from the always-on log. Four injects, balanced.
- Only the **input bar** pushes the HUD (`avoidChat`), not the log. The bar rect is fixed:
  `[2, guiWidth−2] × [guiHeight−14, guiHeight−2]` (full-width, ~12px). Widgets overlapping it slide
  **up** ~12px via the same eased-offset cascade as the potion band, mirrored (bottom-most lifts first).
- **Cascade trap** *(the overreach bug)*: the "stacking" chain must fire **only when the gap between a
  widget and the one being chained is ≥ 0** — a genuine vertical stack. Bottom-anchored widgets all
  share `wBottom = guiHeight − MARGIN`, so a tall right-side widget (e.g. ArrayList, 198px) ties on
  `wBottom` with short left/centre widgets it does **not** overlap horizontally. Without the `gap ≥ 0`
  guard the chain read the negative gap as "adjacent" and dragged each widget up to the tall one's new
  top → runaway (−210, −253…). With the guard, each just clears the bar (−12). Same guard applied to
  both `avoidChat` and `avoidPotions`.
- Both slides share `ChatAnim`: a **one-shot** entrance factor (1→0 over ~220ms easeOut) stamped on the
  closed→open edge (driven by the log hook, which runs every frame). At rest — settled *or* closed — the
  offset is exactly 0, so nothing is left shifted/clipped. No close animation on purpose: the focused
  view vanishes with its screen and the log just stays. An eased-toward-target value can't do this
  (rest-closed ≠ 0); the one-shot timestamp is what keeps rest pristine.
- The **vanilla** bottom HUD (hotbar, health, food, armor, air, XP/contextual bar, held-item name) also
  clears the input bar: all of it is drawn by `Hud.extractHotbarAndDecorations` (health/food/armor/air
  live under `extractPlayerHealth`), so `HudMixin` wraps that one method with a pose translate that eases
  the whole cluster up ~16px while `getChat().isChatFocused()`. This one is a **sustained** eased-toward-
  target shift (not the one-shot), since it must hold up the entire time chat is open, then ease back.
  Works in creative and survival — it's the same umbrella method for both.

**keyPressed fires before charTyped — a keybind leaks its letter** *(`BindComponent`, `GroupBox`, `ClickGuiScreen`)*
- Pressing a printable key dispatches **`keyPressed` then `charTyped`** for the same key. A keybind
  capture consumes the `keyPressed` (binds, clears its `listening` flag) — but the trailing `charTyped`
  still arrives, and by then the flag is already false, so a focused text field (the ClickGUI module
  search) types the bound letter. Guarding the field on the listening flag doesn't work (it's cleared
  before the char).
- Fix: `BindComponent.markBound()` stamps a time on any bind completion (both the setting-level
  `BindComponent` and the module-level bind in `GroupBox`); `ClickGuiScreen.charTyped` swallows the char
  while `BindComponent.recentlyBound()` (~60ms window — catches the immediate trailing char, expires long
  before real typing). Same shape as the chat one-shot: an edge event you time-gate, not a steady flag.

**No `ItemStack` can be built on the title screen** *(`ItemUtil`, 2026-08-04)*
- 26.2 made item components **data-driven**: they live on the registry `Holder`, not on
  `Item`, and are bound only once a world syncs its registries. `Item.components()` just
  forwards to `builtInRegistryHolder().components()`, which throws
  `NullPointerException: Components not bound yet` before then.
- Every `ItemStack` constructor reads them, so `new ItemStack(Items.DIAMOND)` in a menu is a
  crash, not a stack. This took the HUD editor down on the diamond in its item-pickup
  placeholder every time it was opened from the main menu, and the module-toggle toast,
  the block picker and the item picker the same way.
- Fix: `ItemUtil.icon(item)` returns `EMPTY` when `Holder.areComponentsBound()` is false, and
  vanilla's own `GuiGraphicsExtractor.item` no-ops on an empty stack — so the call site only
  has to drop the icon's width from its layout. Where the *components* are needed rather than
  a stack (the item picker's filters call `components().has(...)`, names come off the stack),
  gate the whole build on `ItemUtil.componentsBound()` and show "Join a world first", the way
  `BrewQueuePopup` already did for `mc.level`.
- Diagnosis note: `Holder` exposes `areComponentsBound()` — a real API, no accessor needed.
  Prefer `BuiltInRegistries.ITEM.wrapAsHolder(item)` over `builtInRegistryHolder()`, which is
  deprecated.
- **It reaches further than GUIs, twice over** *(2026-08-04)*. Both were found by the
  gametests within an hour of each other, and neither is a screen:
  - **A `Setting`'s default is computed at client init**, which is far too early. Deriving
    AutoEat's blacklist by asking each item what eating it does crashed the client on boot —
    `ModuleManager.init()` runs long before any world. A default that needs components cannot
    exist; move the question to where the stack is in hand (`AutoEat.harmful`, §4.1).
  - **Constructing a block entity can trip it too, and poisons the class.**
    `BlockGroups.storage()` identifies containers by building each block's block entity —
    and `VaultBlockEntity` builds an `ItemStack` in a *static initialiser*. Catching is not
    enough: a failed `<clinit>` marks the class erroneous for the life of the JVM, so probing
    early leaves the vault permanently unclassifiable and the answer quietly wrong all
    session. `storage()` therefore declines to answer until components bind and the picker
    says so. Note `catch (Exception)` walks straight past an `ExceptionInInitializerError` —
    it is an `Error`. Catch `Throwable` when the callee is arbitrary code.

**A frame allows exactly one blur** *(`FrameBlur`, `BlursBackground`, `GuiBlurMixin`)*
- `GuiRenderState.blurBeforeThisStratum` records **one** stratum per frame and throws
  `IllegalStateException: Can only blur once per frame` on the second call. Two features that
  each want a blurred backdrop therefore take the game down rather than share: a blurred HUD
  widget under any blurred menu, which is how the HUD editor and the ClickGUI both crashed.
- Everything that blurs asks `FrameBlur.claim(g)`; a second asker quietly goes without.
- **Which one goes without is not arbitrary.** The blur applies to everything drawn *below*
  the claiming stratum, and the HUD extracts a stratum earlier than the screen over it — so a
  HUD claim catches the world alone, a screen claim catches the world *and* the HUD. One claim
  cannot serve both: at the screen's stratum it smears the widget's own text, at the HUD's it
  leaves the menu backdrop sharp. The HUD asks `FrameBlur.screenWillClaim()` (i.e. "is the
  current screen a `BlursBackground`?") and stands down, which costs nothing — every client
  screen bar the Future ClickGUI blurs the whole frame anyway.
- The claim is reopened in `Gui.extractRenderState` HEAD, the only point that runs every frame
  regardless of what is on screen. See the `GuiBlurMixin` row in §3.2 for the two obvious
  alternatives that silently don't.

**`GLFW_KEY_UNKNOWN` is both a real key report and our unbound sentinel** *(`ModuleManager`, `KeyboardHandlerMixin`, `GroupBox`)*
- GLFW reports several media/consumer keys (play/pause, volume, some laptop Fn rows) as
  `KEY_UNKNOWN`. We also store `KEY_UNKNOWN` as "this module has no bind".
- Left alone, one press of a media key dispatches to **every unbound module at once**, and
  pressing one while rebinding writes the sentinel back, silently clearing the bind.
- Both ends are guarded now: key dispatch returns early on `KEY_UNKNOWN`, and a bind capture
  ignores it and waits for a usable key rather than turning it into an accidental unbind.

**Top toolbar is shared** *(`ClickGuiToolbar`, `FutureClickGuiToolbar`)*
- The floating top-centre icon bar (ClickGUI / HUD Editor / Friends / Configs / Close) lives in
  `ClickGuiToolbar`; both `ClickGuiScreen` and `HudEditorScreen` call `draw(..., activeIndex)`,
  `buttonAt(...)`, and `activate(button)` (caller skips the currently-active index so re-opening the
  current view is a no-op). Lets you switch between the two screens or close from either.
- Every client screen carries a **parent** through `activate(button, parent)`, so the toolbar
  returns you to wherever you came in from — including the title screen, where all of these
  are reachable. `FutureClickGuiToolbar` is the Future-styled twin; each screen picks between
  them with `FutureClickGuiToolbar.isSelected()`.

**Two ClickGUI renderers** *(`ClickGuiScreen.create`, `ClickGuiPalette`)*
- `ThemeModule.clickGuiStyle` picks Skeet (default) or Future, and **`ClickGuiScreen.create(parent)`
  is the only constructor call sites should use** — it returns the right screen for the setting.
- The two screens deliberately share **no layout code**: Future's identity is every category on
  screen at once, and forcing one layout to serve both is how both end up mediocre. They do share
  every `GuiComponent`, so behaviour cannot drift.
- Because the components are shared, they must not reach for `Theme.accent1` directly — that left
  Future's aqua glass full of Skeet-green checkboxes, sliders and dropdown marks, i.e. every control
  you actually touch themed by the wrong client. `ClickGuiPalette.accent1/accent2/ramp` resolve
  against the active style; Future is defined as a single accent, so both ends of a gradient collapse
  onto it and the ramp flattens. Only the accents move — the recessed greys and border blacks are
  neutral enough for both.
- `FuturePalette` derives the glass from that one accent by dropping saturation and brightness, so
  the backing stays related to the hue without becoming a saturated copy of it that text can't sit on.

**Mannequin is an `Avatar`, not a `Player`** *(`CombatUtil.validTarget`)*
- The 26.2 Mannequin (`world.entity.decoration.Mannequin`) extends `Avatar` — a **sibling**
  of `Player`, which also extends `Avatar`. So `instanceof Player` is false, and since it's
  not an `Enemy` either it silently lands in the *passive* bucket. It uses
  `LivingEntity.createLivingAttributes` (20 HP), so `isAlive()` is true.
- Combat targeting treats a `Mannequin` as a player (grabbed under the *Players* toggle) so
  PvP-practice dummies get targeted by Aura/TargetStrafe/TriggerBot.

**Projectile tick order is type-specific** *(`ProjectilePathUtil`)*
- `ThrowableProjectile.tick()` applies gravity, then inertia (`0.99` air / `0.8` water),
  then clips and moves. `AbstractArrow.tick()` clips and moves with the current velocity,
  then applies inertia and gravity. One generic `velocity = (velocity - gravity) * drag`
  recurrence is therefore wrong for arrows from the first point onward.
- `Projectile.shootFromRotation` applies a potion/XP-bottle pitch offset **only to Y**; X/Z
  still use the player's unadjusted pitch before `shoot()` normalizes the vector. Applying the
  offset to all three axes shortens every bottle path. These two details are why profiles include
  update order and pitch offset rather than leaving either to Trajectories/BowAimbot.

**Screen overlays and camera zoom in 26.2**
- `ScreenEffectRenderer.submit` fans out to `submitBlockSprite` (view-blocking block,
  i.e. pumpkin/powder snow), `submitWater`, `submitFire` — all **private static**, so
  their `@Inject` handlers must be static too. The totem swing is
  `displayItemActivation` (instance) on the same class.
- Third-person camera distance: `Camera.alignWithEntity` calls the private
  `getMaxZoom(4.0f)`, which raycasts and pulls the camera in. `@ModifyArg` changes the
  requested distance; a cancellable `@Inject` at `getMaxZoom` HEAD returning the request
  unchanged skips the raycast (= clip through walls). No `@Shadow` of the private method
  needed.
- Boss bars are killed at `BossHealthOverlay.extractRenderState` (extract phase, before
  anything reaches the GUI render state).
- **No clean hook found for the portal/nausea spin** — `GameRenderer`'s
  `PORTAL_SPINNING_SPEED`/`NAUSEA_SPINNING_SPEED` are `static final` and inlined by the
  compiler, so they don't appear at any call site. Deferred, not forgotten.

**Hunger/fall damage are computed SERVER-side from what you report** *(`LocalPlayerMixin`)*
- `Player.causeFoodExhaustion` is a **no-op on the client** (it early-returns on
  `level().isClientSide`). You cannot stop hunger by touching the client's FoodData —
  the server charges exhaustion in `ServerPlayer.checkMovementStatistics` and friends.
- The server detects a **jump** by watching the packet's `onGround` go true → false
  (`ServerGamePacketListenerImpl` → `jumpFromGround()`), and charges **sprint**
  exhaustion only while *its* `isSprinting()` is true — which it learns solely from
  `ServerboundPlayerCommandPacket.Action.START_SPRINTING/STOP_SPRINTING`, sent by
  `LocalPlayer.sendIsSprintingIfNeeded`. So AntiHunger = spoof `onGround` + suppress the
  sprint command (cost: no sprint knockback). Resync the sprint state on toggle.
- **Fall damage** likewise: the server resets its own fall distance whenever we claim to
  be grounded, so NoFall is the same `onGround` lie.
- `LocalPlayer.sendPosition` calls `onGround()` **6 times** (four packet variants +
  `lastOnGround` bookkeeping + the status-packet comparison). A `@Redirect` covers all of
  them, which is what you want — a partial lie makes the client emit spurious packets.
  The call's constant-pool owner is **`LocalPlayer`**, not `Entity` — the `@At` target
  must say `Lnet/minecraft/client/player/LocalPlayer;onGround()Z` or it won't match.
- `Entity.fallDistance` is a **`double`** in 26.2 (was float).

**Replacing elytra movement needs `updateFallFlyingMovement`, not a tick hook**
*(`ElytraFly` Static, `LivingEntityMixin`)*
- Vanilla **re-derives** the delta from your look angle every tick inside
  `travelFallFlying`, so a velocity written from a module's `onTick` before the tick runs
  is simply overwritten. That's fine for anything that only wants to *add* to vanilla's
  result (Boost does), and useless for anything that wants to *replace* it.
- `travelFallFlying` is three lines —
  `setDeltaMovement(updateFallFlyingMovement(getDeltaMovement())); move(SELF, getDeltaMovement());`
  — so an `@Inject` at the RETURN of `updateFallFlyingMovement` swapping the return value
  is the entire hook. The climbable bail-out and collision handling still run and we never
  call `move` ourselves. Both methods are **private**; the names above are the 26.2
  Mojang-mapped ones, confirmed by javap on the deobf jar.
- `fallDistance` still accumulates while gliding — `Entity.checkFallDamage` has **no**
  fall-flying exemption — so a long controlled descent can still hurt on touchdown. NoFall
  covers it, and unlike the printer's granted-flight case it works here, because Static
  never sets `abilities.flying` and the client-side `fallDistance` NoFall's Packet mode
  watches stays real.

**Criticals only corrects knockback for the thorns exchange it caused**
*(`Criticals`, `ClientPacketListenerMixin`)*
- A critical spoof makes the server evaluate the hit while the attacker appears airborne.
  The thorns damage event arrives before its entity-motion packet, so that retaliation can
  inherit an airborne/stale Y value and launch the player much higher than grounded vanilla
  knockback would.
- Each actual critical attack arms the attacked entity id for 40 ticks. A correction is
  scheduled only when the local player's damage packet is `DamageTypes.THORNS` and its
  direct/cause id matches that exact target. The following local-player motion packet keeps
  its X/Z and caps only Y to vanilla's grounded knockback formula. Ordinary damage, other
  attackers, later packets, and Criticals-off play are untouched.

**Velocity hooks each force according to its packet/movement semantics**
*(`Velocity`, `ClientPacketListenerMixin`, `EntityFluidInteractionMixin`, `EntityMixin`,
`FishingHookMixin`, `LocalPlayerMixin`)*
- `ClientboundSetEntityMotionPacket` carries an absolute velocity. Scale the difference from
  the player's current velocity (`current + (incoming - current) * factor`) so disabling
  knockback does not erase an existing jump or fall.
- Explosion knockback and fluid flow are additive vectors, so their vectors are multiplied
  directly at the respective packet/fluid hooks. Entity collision pushes, suffocation
  block-push, passive sinking, and fishing pulls have separate switches so none must be
  globally cancelled to control another.
- A factor of `0` cancels that force and `1` is vanilla. Horizontal and vertical factors stay
  independent for attacks, explosions, and currents.

**Vehicle modules keep vanilla's move/collision boundary**
*(`BoatFly`, `EntitySpeed`, `EntityControl`, `EntityMixin`, `MobMixin`)*
- `Entity.move(MoverType, Vec3)` is the narrow shared boundary: changing its `Vec3`
  argument gives BoatFly and EntitySpeed an exact requested velocity while vanilla still
  resolves blocks, steps, ground state, fall handling and packet positions. The hook only
  claims the local player's current boat or living mount.
- BoatFly uses the configured sprint key for descent because sneak is the protocol-level
  dismount input. Hovering otherwise trips vanilla's floating-vehicle timer, so Anti kick
  inserts one `-0.04` Y dip per cycle — just beyond the server's `-0.03125` reset threshold.
- EntityControl changes controller selection, not equipment data. `Mob.isSaddled()` is
  spoofed only while our player is already a passenger, which also restores horse/nautilus
  jumping; pigs and striders get WASD at `getRiddenInput` instead of their steering-item-only
  constant-forward vector. Max jump forces the local controlled mount's charge to 1, while
  Lock yaw aligns its body and head to the player's view. In singleplayer the same
  UUID-limited decision runs for the
  integrated `ServerPlayer`. A remote vanilla server still owns its controller check and can
  reject an unsaddled/no-steering-item vehicle; there is no client-only way around that hard
  server gate.

**Fancy chat fonts: verify glyph coverage, don't assume it** *(`ChatFont`)*
- 26.2 ships `unifont_all_no_pua` (asset index → `minecraft/font/unifont.zip`). It **does**
  cover the SMP math alphanumerics, so Fraktur/Script/Bold render — but codepoints past
  the BMP are written 6-digit in the `.hex` (`01D50A:`, not `1D50A:`), which makes a naive
  grep say they're missing. Check with both paddings.
- **Script and Fraktur must use the bold ranges** (U+1D4D0 / U+1D56C). The non-bold ones
  have seven holes — ℬ ℯ ℭ ℌ ℑ ℜ ℨ live in Letterlike Symbols — so `base + (c - 'a')`
  silently emits reserved codepoints for those letters. The bold ranges are contiguous.
- Small caps are not a range at all (three blocks), and Unicode has **no** small-capital X.
- Trim **after** styling and never split a surrogate pair: half a pair is invalid text and
  gets the whole message rejected rather than shortened. Past Fullwidth every letter costs
  two Java chars, so the 256 cap bites at ~128 letters.

**Levitation lives in `travelInAir`, not `travel`** *(`LivingEntityMixin`)*
- `travel` just dispatches to `travelInFluid` / `travelFallFlying` / `travelInAir`
  (private). Levitation is `getEffect(MobEffects.LEVITATION)` inside `travelInAir`,
  immediately null-checked — so a `@Redirect` returning `null` cleanly falls through to
  normal gravity. Slow falling is a separate `hasEffect` inside `getEffectiveGravity`.
- Test the `Holder` in the redirect handler rather than pinning an `ordinal`; it survives
  vanilla adding another effect lookup to the same method.
- `MobEffects.LEVITATION` is a `Holder<MobEffect>`, compared by identity.
- 26.2 keeps `LivingEntity.isFallFlying()` **and** adds `canGlide()` — the old name did
  not go away, don't "fix" it to `isGliding`.

**`ServerboundPlayerCommandPacket.Action` is a nested enum**
- The `START_SPRINTING` / `STOP_SPRINTING` constants live on `...Packet.Action`, *not* on
  the packet class (easy to misread in `javap` output).

**Chunk compilation is threaded**
- `SectionCompiler` runs on worker threads. Snapshot any module render state on the main
  thread first. Avoid Fabric Rendering API redirect clashes here.

**Client-side block scan (Search)** *(`modules/world/Search`)*
- Reading blocks for an ESP-style scan runs on the **tick thread** and is main-thread safe —
  this is *not* the threaded compiler above, so no snapshotting is needed. Loaded chunks:
  `mc.level.getChunkSource().getChunkNow(cx, cz)` (returns null when unloaded — never forces a
  load). Per section: `section.hasOnlyAir()` then `section.maybeHas(predicate)` fast-rejects a
  whole 16³ before you touch individual `getBlockState(lx,ly,lz)`. Section world-Y =
  `chunk.getSectionYFromSectionIndex(i) << 4`.
- Time-slice it: a hard chunk cap **and** a `System.nanoTime()` budget per tick, refilling the
  ring from the player's chunk each pass; publish the finished list and re-emit cached boxes
  each tick (TreasureESP pattern). Occlusion reuses StorageESP's relevant-prefilter so a big
  result set stays O(k), not O(n²).
- **`ChunkPos` is a `record` in 26.2**: `.x`/`.z` fields are private → use the accessors
  `.x()`/`.z()`, and `asLong(int,int)` was renamed `pack(int,int)` (unpack still `getX/getZ(long)`).

**Block breaking must round-trip the server ("packet mine")** *(`Nuker`, `MultiPlayerGameModeAccessor`)*
- `continueDestroyBlock`/`destroyBlock` drive vanilla's *client prediction* and rely on the server's own
  mining **timer** to actually remove the block. A Nuker that removes blocks faster than that timer gets
  the block back — it vanishes on the client (prediction) and **respawns on relog** because the server
  never accepted it. That was the "breaks are client-side only" bug.
- Fix (from **MeteorClient**'s Nuker/BlockUtils): break each block with a `START_DESTROY_BLOCK` +
  `STOP_DESTROY_BLOCK` action pair **in the same tick**, telling the server the block was mined
  start-to-finish. The block is removed by the *server's* response, not client prediction — so on a
  lenient server it sticks, and on a strict one it honestly stays. On the strict single-player integrated
  server this only accepts instant/creative blocks (verified: creative cleared targets to 0).
- The action packet **must carry the real prediction sequence** or the server's ack desyncs.
  `startPrediction` is private → `@Invoker` it (`MultiPlayerGameModeAccessor.unlucky$startPrediction`)
  and fire the two actions through it, exactly like vanilla `startDestroyBlock`/`destroyBlock` do inside.
- Still rotate server-side toward each block first (`RotationManager.lookAt` — spoofs the outgoing
  `MovePlayerPacket.Rot`, camera-free like Aura); a server also rejects a break you aren't facing. Face
  via `Direction.getApproximateNearest`; `getDestroyProgress <= 0` = unbreakable, skip it. Swing is
  client `player.swing(hand)` or a raw `ServerboundSwingPacket`.

**Elytra is TWO wings, not one cape sheet** *(`WingsLayerMixin` / `AvatarRendererMixin` / `ClientAvatarStateMixin`)*
- The wings are mirrored `ModelPart`s carrying big **opposite** `zRot` spread values, and
  `ModelPart` composes rotations Z→Y→X. **Per-wing Euler offsets can never be a rigid
  sway** — identical deltas land in differently-rotated frames, so the wings distort
  asymmetrically and clip into the body. We shipped exactly that bug in the old
  `ElytraModelMixin` (deleted 2026-07-10); do not resurrect the approach.
- Correct approach (ported from OhHeyItsJosh/Elytra-Physics, which targets our exact
  MC 26.2 / Java 25 / Loader 0.19.3 stack): rotate the **whole layer on the PoseStack**
  bracketing `WingsLayer.submit` — the collector copies the pose at submit time, so
  push@HEAD / pop@RETURN works. Add wing spread only via
  `HumanoidRenderState.elytraRotZ`, which the model mirrors onto both wings itself.
- **Fade the sway to identity** with `state.fallFlyingScale()` while `isFallFlying`,
  otherwise it fights the real flight pose (wing twitching mid-glide). Attenuate while
  `isVisuallySwimming` (0.25 vs 0.85 lean factor).
- The sway inputs come from `ClientAvatarState`'s cloak sim, which **hard-snaps per axis
  past 10 blocks** (verified in bytecode: snap at ±10, lerp 0.25) — at ElytraFly speeds
  that fires constantly and jerks both cape and elytra. `ClientAvatarStateMixin` swaps in
  a smooth 9.5-block clamp when ElytraPhysics + "Smooth cape sim" are enabled.
- Vanilla cape drivers (`AvatarRenderer.extractCapeState`):
  `capeFlap = clamp(dy*10, -6, 32)` (vertical bob → pitch);
  `capeLean = clamp((dx·sin + dz·cos)*100, 0, 150)` (forward billow → pitch, zeroed while
  fall-flying); `capeLean2 = clamp((dx·cos − dz·sin)*100, -20, 20)` (sideways sway).

**Vanilla `AABB` semantics** *(both verified against decompiled bytecode)*
- `AABB.clip` returns `Optional.empty()` on a miss **and** when the segment starts inside
  the box.
- `AABB.contains(x,y,z)` uses `x >= minX && x < maxX` — **upper bound exclusive.** Any
  reimplementation must match, or ESP boxes flicker on exact boundaries.

**GUI textures sample nearest-neighbour**
- `blit(pipeline, id, x, y, u, v, w, h, texW, texH, color)` draws a `w×h` region tinted by
  ARGB multiply. Passing `u=v=0, w=h=texW=texH=size` spans UV 0..1 (whole texture)
  regardless of the PNG's native resolution — so one white PNG serves dim/hover/active.
- Icons: thinnest stroke is 2/24 of the icon, so a stroke is **dropped iff draw size < 12px.**
  `TAB_ICON = 16`, `TB_ICON = 14` both clear this. Keep any new icon ≥ 12.

**GitHub API**
- Requires a `User-Agent` header or returns **403**. Unauthenticated limit: 60 req/hr.

**Copyright constraint**
- **Never bundle or redistribute Mojang cape textures.** Stream from Mojang's server and
  cache locally in the client config.

**A rotation nobody re-asserts is a flicker** *(cost four rounds, 2026-07-30)*
- A module that aims **only on the ticks it acts** produces a pose that is applied on a
  fraction of frames and reads as no rotation at all. Measured on the Printer with
  a frame probe: **22229 of 137590 frames** posed — the model sat at the camera angle for the
  other 84%. Re-assert the angle every tick you are working (`Printer.holdAim()`), for
  ~1s past the last aim. It costs no packets: `RotationManager` only sends on change.
- **The deeper trap was upstream.** `PlacementSolver.facings()` used to try *the player's
  own facing first*, so a block that needs no particular rotation caused none — and a
  mapart is entirely non-directional blocks, so the solver kept returning the camera's own
  angle and the printer "rotated" to where it already pointed. A frame-level probe showed
  the spoofed yaw matching the camera to the decimal. Looking at the click now leads that list;
  ordering is safe because a facing is only accepted when the simulation says it produces
  the wanted state.
- Three fixes went into the render path before that was found, each justified by correct
  bytecode. **Instrument the chain before repairing a link.** The probe that finally
  answered it was deleted in v1.9.2 once the bug was closed, but the lesson generalised:
  the v1.9.2 restock cycle repeated the same mistake at a larger scale, and the fixes only
  started landing once the event log stopped lying and the band count became exact.

**Vanilla never repacks the toast stack**
- Toasts are assigned one of five 32px slots for life. When the top one expires the ones
  below **stay where they are**, leaving a hole. So HUD avoidance must measure the *last
  occupied slot* (`ToastManager.occupiedSlots`, a `BitSet` — `length()` is the highest set
  bit + 1), not the number of toasts: counting made widgets ride up into a stack that had
  not moved.

**Litematica interop** (verified against litematica-fabric-26.2-0.28.4 + malilib 0.29.3)
- **26.2 mods ship Mojang-mapped.** Litematica's jar carries *zero* intermediary refs
  (`net/minecraft/core/RegistryAccess`, not `class_5455`), and so does our own remapped
  output — so third-party mod jars go on the classpath with plain **`compileOnly`**, not
  `modCompileOnly`. No remapper round trip, and direct calls link at runtime.
- **`getEclosingBox()`** (Litematica's own typo) is a bare field read whose only writer,
  `updateEnclosingBox()`, is private — so it is **null unless Litematica happens to render
  the box**, and with "render enclosing box" off it is null forever. Use
  `getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED)`, which builds from the schematic's
  own area sizes every call. This cost a silent "movement does nothing" until a probe
  showed `cursor=0/0`.
- **`LayerRange.setLayerRangeMin(int)` clamps** against the other end
  (`Math.min(value, layerRangeMax)`), so a single pass loses the first write whenever the
  new band sits entirely above the old one. Write **min, max, min** and it lands in both
  directions (`LitematicaBridge.applyBand`).
- **Vanilla syncs the carried hotbar slot once per tick.** A printer that switches items
  several times in one tick places the *wrong block* — send
  `ServerboundSetCarriedItemPacket` per switch (`Printer.select`). Found from a `.report`
  Lucien filed on a cobblestone that should have been white carpet.
- **`SchematicWorldHandler.getSchematicWorld()` is not a cheap getter.** It lazily builds
  `WorldRendererSchematic` → `SchematicRenderState` → `ChunkFixUniform`, which calls
  `RenderSystem.getDevice()`. Call it during mod init and you get
  `IllegalStateException: Can't getDevice() before it was initialized`. **Config load
  re-enables saved modules at init time**, so a module's `onEnable` must not touch
  Litematica — only the loader lookup (`LitematicaBridge.present()`) is safe there. Defer
  everything else to the first in-world tick.
- `LayerRange` moved to **`fi.dy.masa.malilib.util.position`** (it was in
  `litematica.util`, which is what older printer addons import). Reached via
  `DataManager.getRenderLayerRange()`; the `isPositionWithinRange(int,int,int)` overload
  avoids allocating a `BlockPos` per scanned position.
- `WorldSchematic extends net.minecraft.world.level.Level`, so `getBlockState(pos)` is
  just the vanilla method.
- Litematica logs its own chunk-rebuild churn (`addTask: [EMPTY] Waking up threads...`)
  at **ERROR** level. It is normal noise, not our bug — expect it while printing.

---

## 7. Build & tooling

```sh
./gradlew build            # jar → build/libs/unlucky-<mod_version>.jar (no classifier = production)
./gradlew compileClientJava -q   # fast compile check; empty output = clean
./gradlew runClientGameTest      # boots a client, sweeps screens then modules (~60s)
build.bat                  # builds and copies "Unlucky Utility Mod.jar" to the repo root
```

**Client gametests** (`src/gametest`, wired by `fabricApi.configureTests` in
`build.gradle`, run dir `build/run/clientGameTest` — never your real `run/`).
`ScreenSmokeTest` opens every client screen and both ClickGUI styles, each of the four
picker popups, and finally the in-game HUD with every widget enabled — once with no
world, once inside a generated one — and renders each for a few ticks. It asserts
nothing about layout; the claim is only that the frame does not throw, which is the
half that has been costing us releases: 10 of the 11 crash reports that were ours up to
v2.0 were a screen or widget throwing while rendering, and the worst of them
(`ItemPickupWidget` on the title screen) survived three releases. Verified by A/B on
2026-08-04 — reintroducing that one bug fails the run in 16s, naming the screen.

- A render exception takes the client down, which fails the task. The `[smoke]` log line
  printed before each screen names the one that broke.
- Adding a screen means adding a line to `ScreenSmokeTest.sweep`. Static popups
  (`*Popup.open`) are opened inside the screen supplier and closed after, since they are
  global state that would otherwise leak into the next screen.
- The block picker is swept through **every tab** via `BlockPickerPopup.selectTab`, which
  exists for this. Simulating a click at computed coordinates would put this file's layout
  constants in the test too: move a tab three pixels and the click lands on nothing, the
  test still passes, and it has been testing the same tab five times ever since. That sweep
  earned itself immediately — the Storage tab crashed the title screen (§6).
- CI runs it as the `client-gametest` job under Xvfb with mesa's llvmpipe
  (`LIBGL_ALWAYS_SOFTWARE=1`); logs and crash reports upload as artifacts on failure.

**`ModuleSmokeTest`** (2026-08-04) is the second entrypoint — both are listed in
`src/gametest/resources/fabric.mod.json` and run in order. It enables all 122 modules in a
world, **one at a time and then all together**, while frames render. One at a time is for
blame: the log line before each module names whatever took the client down. All together is
for the failures that only exist between modules, which the isolated pass cannot see by
construction. Three modules are skipped by class (so deleting one is a compile error), all
because enabling them reaches outside the machine: `UnluckyUsers` publishes to
api.unlucky.life, `BibleBot` fetches from bible-api.com, `DiscordRPC` opens an IPC socket.
`Capes` deliberately stays in — it only reads, and it is the only cover the cape-swap
render path gets.

It also carries three assertions that exist to keep the test honest rather than to test the
client:

- **The scene is verified after it is built.** `runCommand` goes through the command
  dispatcher, which reports failures to the source and swallows them — so a mistyped or
  version-changed command is silent, and without the check the render modules would be
  swept against an empty field. Zombie, cow, dropped item, chest, buried ore, banner and
  brewing stand, at **midnight**: a zombie at noon burns to death a third of the way through
  the sweep and takes the hostile-mob coverage with it.
- **Derived groups must still cover the lists they replaced**, in both directions —
  see §5 `BlockGroups`. A rule that stops matching returns a smaller set, not an error, and
  a superset check alone cannot fail a rule that has gone too wide.
- **Every mixin whose target class exists must have landed on it** — see §5 `MixinAudit`.

Both A/B verified on 2026-08-04: breaking a scene command fails the run naming the missing
block, and reintroducing the `ItemPickupWidget` bug fails it in 16s naming the screen.

- `rootProject.name = 'unlucky'`, so the artifact is `unlucky-1.0.0.jar`.
- `options.encoding = "UTF-8"` is set in `build.gradle` — required, or non-ASCII source
  (e.g. the Greeter smiley) breaks under Windows' Cp1252 javac default.
- Decompiled sources for reference:
  `~/.gradle/caches/fabric-loom/26.2/minecraft-client-only.jar` and `minecraft-common.jar`.
- **Optional mod dependencies** come from the Modrinth maven (`exclusiveContent` filtered
  to `maven.modrinth`), declared **`compileOnly`** — see the Litematica notes in §6 for why
  `modCompileOnly` is wrong here. Versions live in `gradle.properties`
  (`litematica_version`, `malilib_version`). Nothing is bundled: verify with
  `unzip -l build/libs/unlucky-dev.jar | grep -c fi/dy/masa` → must be `0`. To exercise the
  Printer in the dev client, drop both jars into `run/mods/`.
- **Windows/MINGW:** current-dir command lookup is disabled — call `"%~dp0gradlew.bat"`,
  not `gradlew.bat`. For `java @argfile`, convert paths with `cygpath -w` and use `;` as
  the classpath separator.

### Icons

`tools/IconRasterizer.java` — JDK-only SVG→PNG rasterizer (no external deps). Paste an SVG
path in, run it, and it emits a white-on-transparent 64×64 PNG into
`src/client/resources/assets/unlucky/textures/gui/icons/`. Reference it in the GUI with
`icon("name")`. The rasterizer's javadoc has the run command and the ≥12px rule.

---

## 8. Performance notes

**StorageESP** was the one real perf incident. With occlusion culling on, it did ~5M
ray/`contains` ops per tick at 200 chests — all main-thread, all allocating. Fixed by:

1. Allocation-free AABB slab math (`Render3D.slabEntry`).
2. A per-target relevant-occluder prefilter — O(n²) → O(n·k).
3. Caching computed geometry (`BoxGeom`), invalidated only on rescan, occlusion-toggle
   flip, or camera movement > `GEOM_INVALIDATE_DIST_SQ` (currently `0.2²` blocks).

Two things that look like bugs but are **deliberate**:

- The `occluded()` cull uses the **player eye**; clipping uses the **camera**. These differ
  under Freecam, on purpose.
- `weldNeighbors()` is still O(n²). An O(n) axis-neighbour rewrite was implemented,
  verified, and **reverted**: welding grows a box past its source block cube, which can
  cascade into diagonally-positioned boxes that were never block-adjacent. `weld()` only
  sees bounds, not source positions, so an axis-neighbour lookup *structurally* cannot
  reproduce it. Proven with a 200k-scene differential harness (47k mismatches). Full
  postmortem in `done.md` and the method javadoc. **Do not "optimize" this again.**

Current status: smooth at 100–200 chests, degrades ~500. The user judged 500 unrealistic,
so time-slicing (Phase 4) was **intentionally not implemented.** Cheapest remaining dial if
it ever matters: loosen `GEOM_INVALIDATE_DIST_SQ`, since walking invalidates nearly every tick.

---

## 9. Version bump checklist

The version is **derived, not stored** — do not write release numbers into any file:

- **Local builds are always `dev`** (`mod_version=dev` in `gradle.properties`) →
  `unlucky-dev.jar`, watermark/title say "dev".
- **Releases are cut by pushing a git tag** in the form `v<major>.<minor>[.<patch>]` —
  two numbers for a normal release (`v1.9`), an optional third for a small follow-up on
  top of one (`v1.9.1`, first used 2026-07-18 for the mark-styles/configs polish):
  ```sh
  git tag v1.9.1 && git push origin v1.9.1
  ```
  The workflow strips the leading `v` (`${GITHUB_REF_NAME#v}`), so any dotted form works
  and a three-part tag is valid semver (Fabric parses it more happily than two-part).
  `.github/workflows/release.yml` builds with `-PreleaseVersion=<version>` and publishes a
  GitHub Release with the jar attached.
- **The Release is titled `Unlucky Client <version> - <mc_version>`** (2026-08-04), e.g.
  *Unlucky Client 2.0 - 26.2*. The game version appears there and **nowhere else**: the tag
  stays `v2.0`, the jar stays `unlucky-2.0.jar`, the in-game watermark stays `2.0`. A
  download page is the one place someone needs to know which Minecraft this is for; every
  other surface is already inside the right game. The workflow **reads** `minecraft_version`
  out of `gradle.properties` and fails the release if it is missing, so bumping the game
  version moves the title with it and the two cannot drift.
- One mod version therefore maps to one game version: 26.3 gets the next number, not a
  re-tagged `v2.0`. **Multi-version tooling was considered and rejected** — Stonecutter
  handles mechanical renames well and does nothing for architectural churn like 26.2's
  extract/submit split, which is where this mod's cost actually is. Meteor, much larger,
  ships latest-only with an unsupported archive; the tag history already gives us the same
  archive for free.
- **Release notes come from `changelogs/<tag>.md`** (e.g. `changelogs/v2.0.md`), written for
  the people downloading the jar rather than for us: what changed and what it does, grouped,
  no commit hashes. The workflow falls back to GitHub's generated notes only when that file
  is missing — which it should never be for a real release. Write it **before** pushing the
  tag; the release is created the moment the tag lands.
- `UnluckyClient.VERSION` reads the version back from Fabric's mod metadata at runtime —
  one source of truth. **Never hardcode a number there again.**

When cutting a release, in this file:

- [ ] Update the **Last synced** line at the top (release tag + MC/loader/Java if changed).
- [ ] Add/remove modules in §4.1 — cross-check against `ModuleManager.init()`, don't trust
      the directory listing.
- [ ] Add/remove HUD widgets in §4.2 — cross-check against `HudManager.init()`.
- [ ] Add/remove mixins in §3 — cross-check against `unlucky.client.mixins.json`.
- [ ] Append any new API trap to §6. This section is the highest-value part of the doc;
      if something cost you more than 20 minutes, write it down.

- [ ] Give `README.md` (user-facing; rewritten 2026-07-10) a quick pass for the same
      drift — new modules/widgets belong there too.

- [ ] **Move the finished phase out of `plan.md` into `done.md`.** They split
      2026-07-17: `plan.md` is open work only, `done.md` is the archive, and an item
      lives in **exactly one** of them. Leaving completed work in the plan is how v1.9
      shipped with the registry described as unbuilt in two places, two releases after
      it went live — a doc nobody trusts is a doc nobody reads. If a phase is only
      part-done, its history goes to `done.md` with a pointer and the open items stay
      in `plan.md`. Cite archived phases as `done.md Phase N`, not `plan.md Phase N`.
