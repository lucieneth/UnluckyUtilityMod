# Unlucky Client — Architecture & Feature Map

> **Orientation doc for contributors and AI assistants.** Read this before touching the
> codebase. It explains what exists, what each mixin hooks, and the 26.2-specific API
> traps that will otherwise cost you an hour each.
>
> **Last synced:** v1.3 / MC 26.2 / Fabric Loader 0.19.3 / Java 25
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
| `ModuleManager` | Registers all 70 modules in one `init()` block. `get(Class)` is an `IdentityHashMap` lookup — it sits on per-entity-per-frame render paths (chams/glow/nametag mixins), so keep it O(1). |
| `PerfDebug` | Frame/tick profiler behind `-Dunlucky.perfDebug` (or env `UNLUCKY_PERF_DEBUG=true`): rolling avg/max per section logged once a second. `static final` flag → zero cost when off. Sections: `overlay.*` (ESP/NameTags), `hud.*` (per widget + avoidance), `tick.<Module>`. |
| `HudManager` | Registers all 18 HUD widgets. |
| `ConfigManager` | Gson → `config/unlucky/config.json` (everything client-side lives under `config/unlucky/`: config, `friends.json`, cape cache; the pre-2026-07 `config/unlucky.json` is auto-migrated via `Files.move` on first load). Saved on a JVM shutdown hook. |

Default keys (rebindable in-GUI): `Right Shift` ClickGUI, `Right Ctrl` HUD editor.

`UnluckyClient.onKeyPress` returns `true` to **swallow** the key. This is load-bearing: if
it didn't, the same press that opens the ClickGUI would immediately reach the new screen
and close it.

---

## 3. Mixin map

43 entries in `unlucky.client.mixins.json`, all `client`-side, `compatibilityLevel: JAVA_25`,
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
| `EntityRendererMixin` | `EntityRenderer` | `extractRenderState` | Stashes the ESP outline colour on the render state; also nulls `state.nameTag` for players when **NameTags** is on (`NameTags.hidesVanilla`) so our billboard replaces the built-in tag. |
| `MinecraftMixin` | `Minecraft` | `shouldEntityAppearGlowing` RETURN, `startUseItem` HEAD (cancellable) + RETURN, `pickBlockOrEntity` HEAD | ESP glow pass; right-click actions (ClickTP, TridentFly) in **one shared handler**, FastUse's `rightClickDelay`, middle-click ClickTP. **See §6.** |
| `AbstractClientPlayerMixin` | `AbstractClientPlayer` | `getSkin` RETURN | Swaps cape/elytra on your own skin so vanilla layers render it 1:1. |
| `WingsLayerMixin` | `WingsLayer` | `submit` HEAD+RETURN | ElytraPhysics sway: push/transform/pop the PoseStack around the elytra layer — rigid-unit rotation. **See the trap in §6.** |
| `AvatarRendererMixin` | `AvatarRenderer` | `extractRenderState` TAIL | ElytraPhysics wing spread via `state.elytraRotZ`; **silent-aim pitch** on the local model via `state.xRot` while `RotationManager.isSpoofing()` (**see §6**). |
| `ClientAvatarStateMixin` | `ClientAvatarState` | `moveCloak` HEAD (cancellable) | ElytraPhysics "Smooth cape sim": replaces vanilla's 10-block cloak snap with a smooth 9.5-block clamp so cape/elytra don't jerk at ElytraFly speeds. Vanilla path untouched when off. |
| `FogRendererMixin` | `FogRenderer` | `setupFog` RETURN | Fog for **both** NoFog (distance, Nether, End) and NoRender (water, lava, powder snow, blindness, darkness). Clears the two `FogData` channels **independently** — see §6. |
| `GameRendererMixin` | `GameRenderer` | `bobHurt` HEAD | NoHurtCam. |
| `LevelMixin` | `Level` | `getRainLevel` / `getThunderLevel` RETURN, `setSkyFlashTime` HEAD | NoWeather. **`Level` is common — every hook is gated on "is this the client's level"**, or we'd lie to the integrated server. |
| `ClientLevelMixin` | `ClientLevel` | `tickWeatherEffects` HEAD, `addDestroyBlockEffect` HEAD | NoWeather (rain particles + ambient sound), NoRender (block-break particles). |
| `ScreenEffectRendererMixin` | `ScreenEffectRenderer` | `submitFire` / `submitBlockSprite` / `submitWater` HEAD (all **static**), `displayItemActivation` HEAD | NoRender: fire / in-block / water overlays + totem animation. |
| `BossHealthOverlayMixin` | `BossHealthOverlay` | `extractRenderState` HEAD | NoRender boss bars. |
| `HudMixin` | `Hud` | `extractTextureOverlay` HEAD; `extractHotbarAndDecorations` HEAD push+translate / RETURN pop | NoRender pumpkin overlay (the head-equippable camera overlay; **not** the in-block one, that's `submitBlockSprite`); plus the chat-clear shift that eases the whole bottom HUD cluster up while chat is open (§6). |
| `ChatSlideMixin` | `ChatComponent` | `extractRenderState` (7-arg) HEAD push+translate / RETURN pop | Message-log slide-in from the left on open (one-shot; log + focused text share this method). Does not push the HUD. |
| `ChatInputSlideMixin` | `ChatScreen` | `extractRenderState` HEAD/RETURN + before/after the `ChatComponent` INVOKE | Input-bar slide-up from the bottom; brackets its pose translate around the middle FOREGROUND-log call. |
| `LightmapRenderStateExtractorMixin` | `LightmapRenderStateExtractor` | `extract` TAIL | Fullbright (the *global* one, distinct from XRay's). |
| `ItemStackTooltipMixin` | `ItemStack` | `getTooltipImage` RETURN; `getTooltipLines` RETURN | InventoryInfo: returns a `ContainerTooltipData` for `CONTAINER` stacks (rendered as a grid via the Fabric `ClientTooltipComponentCallback` registered in `UnluckyClient.init`), and appends the byte-size line. |
| `ItemContainerContentsMixin` | `ItemContainerContents` | `addToTooltip` HEAD (cancellable) | InventoryInfo: cancels the vanilla "x N ItemName" text lines when the container-grid preview is on, so text + grid don't double up. |
| `PlayerTabOverlayMixin` | `PlayerTabOverlay` | `getNameForDisplay` RETURN | Friends: wraps the returned Component with a blue `•` prefix. That method is the single source for the shown name (called for both column-width measurement and drawing), so layout stays consistent. |
| `ToastManagerAccessor` | `ToastManager` | `@Invoker freeSlotCount` | HUD toast avoidance: top-right widgets slide down while toasts occupy slots (5 × 32px, 160 wide; merged with the potion band in `HudManager.avoidTopRight` so nothing double-pushes). |
| `SodiumBlockRenderContextMixin` | sodium `AbstractBlockRenderContext` (string target) | `shouldDrawSide` + `isFaceCulled` HEAD, `require = 0` | XRay under Sodium: its mesher skips every vanilla path our other XRay hooks use. Uses the `*At(pos)` XRay checks — the plain `active()/hides()` gate on a ThreadLocal only the vanilla section compiler sets (permanently false on Sodium threads, the reason two working-hook rounds still hid nothing). |
| `SodiumBlockRendererMixin` | sodium `BlockRenderer` (string target) | `renderModel` HEAD, `require = 0` | XRay terrain hide: cancels meshing hidden states outright (`XRay.hidesAt`). `isFaceCulled` is declared on the parent context, NOT here — targeting it here silently aborted the whole mixin (one invalid injection kills all injects; require 0 hid it). |
| `SodiumLightDataAccessMixin` | sodium `LightDataAccess` (string target) | `@ModifyReturnValue compute(III)I`, `require = 0` | XRay fullbright under Sodium: rebuilds the packed light word (full block+sky light, flat AO, no emissive; opacity/full-cube flags preserved via shadowed `pack*`/`unpack*`) when `XRay.fullbrightAt(pos)`. Replaces the bypassed vanilla flat-shade path (CardinalLighting/BlockModelLighterCache/etc). |
| `VisGraphMixin` | `VisGraph` | `setOpaque` HEAD, cancellable | XRay: nothing is opaque to the section-visibility graph while enabled, so enclosed caves stay renderable. Engine-agnostic root — Sodium's occlusion culler reuses vanilla VisGraph, so this one hook opens both pipelines. Gated on `XRay.enabled()` (no range/ThreadLocal). |

Note `MinecraftMixin` and `MinecraftTitleMixin` **both target `Minecraft.class`** — split
purely for readability (`createTitle` → window title branding).

### 3.3 Input & camera

| Mixin | Target | Hook | Serves |
| --- | --- | --- | --- |
| `KeyboardHandlerMixin` | `KeyboardHandler` | `keyPress` HEAD, cancellable | Routes raw keys to `UnluckyClient.onKeyPress`; cancels when swallowed. |
| `KeyboardInputMixin` | `KeyboardInput` | `tick` TAIL | Freezes player movement while Freecam flies the camera. |
| `MouseHandlerMixin` | `MouseHandler` | `@Redirect turnPlayer`; `onButton` HEAD | Steers the freecam instead of the player; Friends middle-click toggle (crosshair player, in-game only — vanilla pick-block still proceeds). |
| `CameraMixin` | `Camera` | `calculateFov` RETURN, `alignWithEntity` TAIL, `@ModifyArg getMaxZoom(F)` in `alignWithEntity`, `getMaxZoom` HEAD | Zoom, freecam detach, ViewClip (distance + clip-through). |

### 3.4 Network, combat, chat

| Mixin | Target | Hook | Serves |
| --- | --- | --- | --- |
| `ClientCommonPacketListenerMixin` | `ClientCommonPacketListenerImpl` | `@ModifyVariable send` HEAD | Rewrites outgoing movement packets with the spoofed rotation (`RotationManager`). |
| `ClientPacketListenerMixin` | `ClientPacketListener` | `handleSoundEvent`, `handleSetTime`, `handleTakeItemEntity`, `@Redirect handleSetEntityMotion` | SoundLocator, AutoFish (bobber-splash bite detection), TPS estimate, item-pickup HUD, Velocity (knockback scaling). |
| `MultiPlayerGameModeMixin` | `MultiPlayerGameMode` | `attack` HEAD | Feeds `SessionTracker` so it can approximate kills. |
| `MultiPlayerGameModeAccessor` | `MultiPlayerGameMode` | `@Invoker startPrediction` | Lets Nuker send START/STOP block-action packets with a valid prediction sequence ("packet mine", §6). |
| `LocalPlayerMixin` | `LocalPlayer` | `@Redirect onGround() in sendPosition`, `sendIsSprintingIfNeeded` HEAD | NoFall + AntiHunger — both lie about the same outgoing `onGround` flag. **See §6.** |
| `LivingEntityMixin` | `LivingEntity` | `aiStep`, `canGlide` RETURN, `handleEntityEvent`, `canStandOnFluid` RETURN, `@Redirect getEffect in travelInAir`, `@Redirect hasEffect in getEffectiveGravity` | NoJumpDelay, FakeFly, totem-pop counter, Jesus (real fluid collision — **see §6**), AntiLevitation (levitation + optional slow-falling). |
| `ChatComponentMixin` | `ChatComponent` | `addMessage` HEAD + `@ModifyVariable` | AdBlocker (drop) and AntiToS (censor). |
| `SignTextMixin` | `SignText` | `getMessages` RETURN | AntiToS on signs. |

### 3.5 Book screens

| Mixin | Target | Serves |
| --- | --- | --- |
| `BookEditScreenMixin` | `BookEditScreen` | BookTools: injects §-formatting buttons. |
| `BookViewScreenMixin` | `BookViewScreen` | PagePirate: adds a deobfuscate button. |
| `MultiLineEditBoxAccessor` | `MultiLineEditBox` | Accessor (not a mixin) — exposes internals for the above. |

---

## 4. Feature inventory

### 4.1 Modules — 68, registered in `ModuleManager.init()`

> **Trap:** the package layout is *not* the category. `Category` comes from the `Module`
> constructor. `Fullbright` lives in `modules/visuals/` but reports `RENDER`.

**Combat** — Aura, TriggerBot, AutoClicker, TargetStrafe

**Movement** — ElytraFly, AutoSprint (omni), CreativeFlight, Jetpack, Speed, BunnyHop,
Velocity, NoJumpDelay, FakeFly, RocketMan, RocketJump, Updraft, RoadTrip (AFK travel
safeties), AFKVanillaFly, NoFall, AntiLevitation, Yaw (hard yaw lock — a *real* rotation,
unlike `RotationManager`'s spoof), Jesus, TridentFly, ClickTP

**Render** — PlayerESP (shader silhouette, CS-style 2D boxes w/ HP+armor bars, skeleton,
tracers), NameTags (billboard tags via the same world→screen 2D pass: gamemode/health
Number|Hearts (heart row scaled to the name width)/ping/distance, armor row with 3-letter
enchant chips in an even, uniform-width column grid (total capped by a slider);
Off/Custom/Vanilla backdrop; distance-falloff scale; cancels the vanilla tag), MobESP, StorageESP, Chams, XRay, Freecam, ElytraPhysics,
NoFog, AutoDrawDistance, Fullbright, Zoom, NoHurtCam, NoWeather, ViewClip, NoRender (screen-clutter toggles)

**World** — ChatSigns, WaxAura, AutoDoors (close-behind), BannerData, TreasureESP,
Search, Nuker, Archaeology, AutoFarm, AutoWither, ObsidianFarm, BlockAirPlace, VanityESP

**Player** — Capes, Honker, PagePirate, AutoExtinguish, AutoXPRepair, AntiHunger, FastUse,
AutoEat (exposes `busy()` — interact modules must yield to it; scores food across the hotbar
**and offhand**, and clears the main hand to an empty slot when eating offhand so the held
right-click can't mis-eat or place a block), AutoFish

**Misc** — HudModule, ThemeModule (live accent recolor + menu blur), AdBlocker,
AntiToS (blacklist: `config/unlucky-antitos.txt`), BookTools, SoundLocator, Spinbot,
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

### 4.2 HUD widgets — 18, registered in `HudManager.init()`

Watermark, ArrayList, Coords, Speedometer, Keystrokes, ArmorHud, PotionHud, TargetHud,
Radar, InventoryViewer, ItemCounter, ItemPickup, PopCounter, SessionInfo, Info,
PlayerModel, CustomText, **Greeter**.

Widgets are positioned by fractional screen coords (`setFractions(x, y)`) so they survive
resolution changes. `Greeter` is intentionally **not user-editable** — its text is derived
from time-of-day + username.

Adding a widget = 3 edits: the widget class, `HudManager.init()`, and a settings row in
`HudEditorScreen`'s `switch` (plus the toggle/color settings on `HudModule`).

### 4.3 Settings & GUI components

Each `Setting<T>` has a matching `GuiComponent`:

`BooleanSetting` · `NumberSetting` · `ModeSetting` · `ColorSetting` · `KeybindSetting` ·
`StringSetting` · `BlockListSetting` · `EntityListSetting`

`BlockListSetting` / `EntityListSetting` / `ItemListSetting` open the `BlockPickerPopup` /
`MobPickerPopup` / `ItemPickerPopup`.

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
| `RotationManager` | Server-side rotation spoofing, flushed in `onTickEnd()`. |
| `CapeManager` | Cape packs for the Capes module. Streams Mojang capes + a **live GitHub pack** from `lucieneth/Capes`, cached to `config/unlucky/capes/`. Exposes `revision()` so the picker rebuilds when the async fetch lands. |
| `FriendManager` | The friends list: UUID → last-known name in `config/unlucky/friends.json`, lazy-loaded, saved on every change. UUID-keyed so friendships survive name changes. `COLOR`/`TEXT_COLOR`/`DOT` constants are the one source for the friend accent (0xFF4A9BFF). Local-only for now — the networking phase (plan.md Phase 11) syncs presence/capes but this file stays the source of truth. |
| `ChamsRenderType` / `ChamsRenderState` | Custom no-depth pipeline + the state bridge. `init()` must run early (it does, first line of `UnluckyClient.init()`). |
| `SessionTracker` · `ServerStats` | Kills/deaths, TPS, ping. |
| `WorldScan` · `InteractUtil` · `MoveUtil` · `CombatUtil` · `GearUtil` | Shared helpers. |
| `Theme` · `ColorUtil` · `Animation` · `Easing` | Visual layer. |
| `TextBox` (`ui/`) | Shared single-line text-edit engine for all GUI text fields — see §4.3. |

---

## 6. Hard-won 26.2 API notes

These have each cost real debugging time. **Trust this list over your priors.**

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

**Top toolbar is shared** *(`ClickGuiToolbar`)*
- The floating top-centre icon bar (ClickGUI / HUD Editor / Friends / Configs / Close) lives in
  `ClickGuiToolbar`; both `ClickGuiScreen` and `HudEditorScreen` call `draw(..., activeIndex)`,
  `buttonAt(...)`, and `activate(button)` (caller skips the currently-active index so re-opening the
  current view is a no-op). Lets you switch between the two screens or close from either.

**Mannequin is an `Avatar`, not a `Player`** *(`CombatUtil.validTarget`)*
- The 26.2 Mannequin (`world.entity.decoration.Mannequin`) extends `Avatar` — a **sibling**
  of `Player`, which also extends `Avatar`. So `instanceof Player` is false, and since it's
  not an `Enemy` either it silently lands in the *passive* bucket. It uses
  `LivingEntity.createLivingAttributes` (20 HP), so `isAlive()` is true.
- Combat targeting treats a `Mannequin` as a player (grabbed under the *Players* toggle) so
  PvP-practice dummies get targeted by Aura/TargetStrafe/TriggerBot.

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

---

## 7. Build & tooling

```sh
./gradlew build            # jar → build/libs/unlucky-<mod_version>.jar (no classifier = production)
./gradlew compileClientJava -q   # fast compile check; empty output = clean
build.bat                  # builds and copies "Unlucky Utility Mod.jar" to the repo root
```

- `rootProject.name = 'unlucky'`, so the artifact is `unlucky-1.0.0.jar`.
- `options.encoding = "UTF-8"` is set in `build.gradle` — required, or non-ASCII source
  (e.g. the Greeter smiley) breaks under Windows' Cp1252 javac default.
- Decompiled sources for reference:
  `~/.gradle/caches/fabric-loom/26.2/minecraft-client-only.jar` and `minecraft-common.jar`.
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
  postmortem in `plan.md` and the method javadoc. **Do not "optimize" this again.**

Current status: smooth at 100–200 chests, degrades ~500. The user judged 500 unrealistic,
so time-slicing (Phase 4) was **intentionally not implemented.** Cheapest remaining dial if
it ever matters: loosen `GEOM_INVALIDATE_DIST_SQ`, since walking invalidates nearly every tick.

---

## 9. Version bump checklist

The version is **derived, not stored** — do not write release numbers into any file:

- **Local builds are always `dev`** (`mod_version=dev` in `gradle.properties`) →
  `unlucky-dev.jar`, watermark/title say "dev".
- **Releases are cut by pushing a git tag** in the form `v<major>.<minor>` (two numbers,
  e.g. `v1.1` — explicit user preference, never three-part semver):
  ```sh
  git tag v1.1 && git push origin v1.1
  ```
  `.github/workflows/release.yml` builds with `-PreleaseVersion=1.1` and publishes a
  GitHub Release with the jar attached.
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
