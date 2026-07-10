# Unlucky Client — Architecture & Feature Map

> **Orientation doc for contributors and AI assistants.** Read this before touching the
> codebase. It explains what exists, what each mixin hooks, and the 26.2-specific API
> traps that will otherwise cost you an hour each.
>
> **Last synced:** `mod_version 1.0` / MC 26.2 / Fabric Loader 0.19.3 / Java 25
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
| `ModuleManager` | Registers all 53 modules in one `init()` block. |
| `HudManager` | Registers all 18 HUD widgets. |
| `ConfigManager` | Gson → `config/unlucky.json`. Saved on a JVM shutdown hook. |

Default keys (rebindable in-GUI): `Right Shift` ClickGUI, `Right Ctrl` HUD editor.

`UnluckyClient.onKeyPress` returns `true` to **swallow** the key. This is load-bearing: if
it didn't, the same press that opens the ClickGUI would immediately reach the new screen
and close it.

---

## 3. Mixin map

30 entries in `unlucky.client.mixins.json`, all `client`-side, `compatibilityLevel: JAVA_25`,
`defaultRequire: 1`. Every injected method is prefixed `unlucky$`.

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
| `LivingEntityRendererMixin` | `LivingEntityRenderer` | `submit` @ `INVOKE` | Re-submits the model as a tinted silhouette in the same transform. Through-walls uses a custom no-depth `RenderPipeline` (`ChamsRenderType`). |
| `EntityRendererMixin` | `EntityRenderer` | `extractRenderState` | Stashes the ESP outline colour on the render state. |
| `MinecraftMixin` | `Minecraft` | `shouldEntityAppearGlowing` RETURN | Forces the vanilla glow/outline pass on for ESP targets. |
| `AbstractClientPlayerMixin` | `AbstractClientPlayer` | `getSkin` RETURN | Swaps cape/elytra on your own skin so vanilla layers render it 1:1. |
| `ElytraModelMixin` | `ElytraModel` | `setupAnim` TAIL | Cape-like sway on the elytra. **See the trap in §6.** |
| `FogRendererMixin` | `FogRenderer` | `setupFog` RETURN | NoFog. |
| `GameRendererMixin` | `GameRenderer` | `bobHurt` HEAD | NoHurtCam. |
| `LightmapRenderStateExtractorMixin` | `LightmapRenderStateExtractor` | `extract` TAIL | Fullbright (the *global* one, distinct from XRay's). |

Note `MinecraftMixin` and `MinecraftTitleMixin` **both target `Minecraft.class`** — split
purely for readability (`createTitle` → window title branding).

### 3.3 Input & camera

| Mixin | Target | Hook | Serves |
| --- | --- | --- | --- |
| `KeyboardHandlerMixin` | `KeyboardHandler` | `keyPress` HEAD, cancellable | Routes raw keys to `UnluckyClient.onKeyPress`; cancels when swallowed. |
| `KeyboardInputMixin` | `KeyboardInput` | `tick` TAIL | Freezes player movement while Freecam flies the camera. |
| `MouseHandlerMixin` | `MouseHandler` | `@Redirect turnPlayer` | Steers the freecam instead of the player. |
| `CameraMixin` | `Camera` | `calculateFov` RETURN, `alignWithEntity` TAIL | Zoom, and freecam detach. |

### 3.4 Network, combat, chat

| Mixin | Target | Hook | Serves |
| --- | --- | --- | --- |
| `ClientCommonPacketListenerMixin` | `ClientCommonPacketListenerImpl` | `@ModifyVariable send` HEAD | Rewrites outgoing movement packets with the spoofed rotation (`RotationManager`). |
| `ClientPacketListenerMixin` | `ClientPacketListener` | `handleSoundEvent`, `handleSetTime`, `handleTakeItemEntity`, `@Redirect handleSetEntityMotion` | SoundLocator, TPS estimate, item-pickup HUD, Velocity (knockback scaling). |
| `MultiPlayerGameModeMixin` | `MultiPlayerGameMode` | `attack` HEAD | Feeds `SessionTracker` so it can approximate kills. |
| `LivingEntityMixin` | `LivingEntity` | `aiStep`, `canGlide` RETURN, `handleEntityEvent` | NoJumpDelay, FakeFly, totem-pop counter. |
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

### 4.1 Modules — 53, registered in `ModuleManager.init()`

> **Trap:** the package layout is *not* the category. `Category` comes from the `Module`
> constructor. `Fullbright` lives in `modules/visuals/` but reports `RENDER`.

**Combat** — Aura, TriggerBot, AutoClicker, TargetStrafe

**Movement** — ElytraFly, AutoSprint (omni), CreativeFlight, Jetpack, Speed, BunnyHop,
Velocity, NoJumpDelay, FakeFly, RocketMan, RocketJump, Updraft, RoadTrip (AFK travel
safeties), AFKVanillaFly

**Render** — PlayerESP (shader silhouette, CS-style 2D boxes w/ HP+armor bars, skeleton,
tracers), MobESP, StorageESP, Chams, XRay, Freecam, ElytraPhysics, NoFog, AutoDrawDistance,
Fullbright, Zoom, NoHurtCam

**World** — ChatSigns, WaxAura, AutoDoors (close-behind), BannerData, TreasureESP,
Archaeology, AutoFarm, AutoWither, ObsidianFarm, BlockAirPlace, VanityESP

**Player** — Cape, Honker, PagePirate, AutoExtinguish, AutoXPRepair

**Misc** — HudModule, ThemeModule (live accent recolor + menu blur), AdBlocker,
AntiToS (blacklist: `config/unlucky-antitos.txt`), BookTools, SoundLocator, Spinbot

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

`BlockListSetting` / `EntityListSetting` open the `BlockPickerPopup` / `MobPickerPopup`.

---

## 5. Support infrastructure (`util/`)

| Class | Notes |
| --- | --- |
| `Render2D` / `Render3D` | Drawing primitives. `Render3D` holds the allocation-free slab math and the `BoxGeom` cache used by the ESPs — **see §6**. |
| `RotationManager` | Server-side rotation spoofing, flushed in `onTickEnd()`. |
| `CapeManager` | Cape packs. Streams Mojang capes + a **live GitHub pack** from `lucieneth/Capes`, cached to `config/unlucky/capes/`. Exposes `revision()` so the picker rebuilds when the async fetch lands. |
| `ChamsRenderType` / `ChamsRenderState` | Custom no-depth pipeline + the state bridge. `init()` must run early (it does, first line of `UnluckyClient.init()`). |
| `SessionTracker` · `ServerStats` | Kills/deaths, TPS, ping. |
| `WorldScan` · `InteractUtil` · `MoveUtil` · `CombatUtil` · `GearUtil` | Shared helpers. |
| `Theme` · `ColorUtil` · `Animation` · `Easing` | Visual layer. |

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

**Chunk compilation is threaded**
- `SectionCompiler` runs on worker threads. Snapshot any module render state on the main
  thread first. Avoid Fabric Rendering API redirect clashes here.

**Elytra is TWO wings, not one cape sheet** *(`ElytraModelMixin`)*
- `zRot` **is the wing-spread axis.** Touching it folds the wings into each other.
- `rightWing.xRot = leftWing.xRot` (**same** sign); `yRot`/`zRot` mirror **opposite**.
- Correct approach: sway the assembly as a rigid unit — apply the **same** `xRot`/`yRot`
  offset to both wings, never `zRot`.
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

Version lives in **two** places — keep both in sync. The scheme is **two numbers**
(`major.minor`, e.g. `1.0`), by explicit user preference:

1. `gradle.properties` → `mod_version` — drives the jar name (`unlucky-<version>.jar`).
2. `UnluckyClient.java` → `public static final String VERSION` — drives the watermark
   and window title.

Then, in this file:

- [ ] Update the **Last synced** line at the top (mod version + MC/loader/Java if changed).
- [ ] Add/remove modules in §4.1 — cross-check against `ModuleManager.init()`, don't trust
      the directory listing.
- [ ] Add/remove HUD widgets in §4.2 — cross-check against `HudManager.init()`.
- [ ] Add/remove mixins in §3 — cross-check against `unlucky.client.mixins.json`.
- [ ] Append any new API trap to §6. This section is the highest-value part of the doc;
      if something cost you more than 20 minutes, write it down.

> `README.md` is user-facing and currently **stale** (missing Cape, Chams, XRay, Freecam,
> the whole Combat category, and more; its "No PvP cheats" line no longer holds). Treat
> *this* file as the source of truth and refresh the README on the next bump.
