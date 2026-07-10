# Unlucky Client — Work Plan

Visuals-first MC 26.2 / Java 25 Fabric client. ~43 modules. Package root:
`src/client/java/unlucky/utility/client/`

## Next up (the 3 deferred items)

### 1. Silent / spoofed rotations  ✅ DONE
- [x] `util/RotationManager` — `rotate(yaw,pitch)` / `lookAt(Vec3)` per tick;
      sends `Rot` packet at tick end, restores real rotation when done.
- [x] `ClientCommonPacketListenerMixin` — rewrites outgoing PosRot/Rot packets
      while spoofing (camera moves don't leak to server).
- [x] 3rd person / freecam: `yBodyRot`/`yHeadRot` synced to spoof (visible turn).
      Note: head *pitch* stays camera-tied in 3rd person (camera-coupled).
- [x] Auto-wired: `InteractUtil.useOnBlock`/`breakBlock` + ObsidianFarm mining
      now lookAt their target → AutoFarm/AutoWither/ObsidianFarm all covered.
- Future aura modules: just call `RotationManager.lookAt(target)` each tick.

### 2. ESP inter-object occlusion culling  ✅ DONE
- [x] `Render3D.occluded(target, eye, occluders)` — CPU ray test eye→box center;
      culled only when another ESP box blocks the sightline. Terrain never blocks.
      Occluders deflated 0.08 so touching boxes (double chests) don't cull neighbors.
- [x] StorageESP "Occlusion cull" toggle (default on) — applies to blocks + minecarts.
- [x] Follow-up: same cull wired into TreasureESP + VanityESP (default-on
      "Occlusion cull" toggle each; boxes cached as AABBs so occluded()'s
      identity check works). Done 2026-07-10 for the release.
      (Player/Mob glow-shader ESP can't do selective occlusion — GPU outline.)

### 3. HUD right-click settings popup  ✅ DONE
- [x] Right-click a widget in the HUD editor → popup: widget toggle + Background.
      Left-click rows to toggle; click elsewhere closes. Disabled widgets show a
      faded outline in the editor (still draggable / re-enableable).
- [x] Generalized: popup renders Boolean (checkbox), Number (mini slider, drag),
      and Mode (click cycles) settings — ArrayList row exposes animation settings.

### 4. Combat batch  ✅ DONE
- [x] `CombatUtil` — target filter (players/hostiles/passives), attack timing
      (Attributes = weapon charge via `getAttackStrengthScale`, or flat CPS), attack+swing.
- [x] **KillAura** — range, target groups, speed mode+CPS, priority (Closest/Health),
      silent rotations (body turns in 3rd person, camera free), pause-in-GUI.
- [x] **TriggerBot** — fires when crosshair rests on a valid target; same filters/speed.
- [x] **AutoClicker** — CPS or attribute-timed clicks, optional hold-to-click.

### 5. XRay  ✅ DONE
- [x] `SectionCompilerMixin` — skips tesselation of hidden blocks/fluids, opens
      the visibility graph (see through everything). Volatile snapshot fast path.
- [x] `BlockListSetting` (+ config persistence) with `BlockPickerPopup` in ClickGUI:
      item icons, Storage|Ores|Valuables presets, scroll, click-to-toggle.
- [x] Chunk rebuild via `levelExtractor.allChanged()` on setting change only
      (per-chunk-cross rebuilds removed — they flashed the whole screen).
- [x] Distance cap (chunks slider): per-section range check on worker threads vs
      a main-thread camera snapshot (never touch render state off-thread!).
- [x] Fullbright option: flat fully-lit blocks AND fluids. Needed hooks in BOTH
      pipelines: vanilla (`BlockModelLighter.Cache.getLightCoords`, `CardinalLighting`,
      AO redirect) and Fabric Indigo (`AoCalculatorMixin` — Indigo bypasses vanilla
      lighting entirely; see memory `section-compiler-threading`).
- [x] Buried ore faces render via static `Block.shouldRenderFace` inject; fluids
      keep full volume via `FluidRendererMixin`.
- Deferred: true 0-1 opacity slider (needs translucent-layer + vertex-alpha rewrite).

### 6. ArrayList gradient animation  ✅ DONE
- [x] `Theme.hudScrollingAccent` — triangle-wave scroll of the gradient.
- [x] Settings on HUD module: on/off, speed (0.1–5), direction (Down/Up) —
      editable in ClickGUI and the HUD editor right-click popup.

### 7. Mob picker submenu (KillAura/TriggerBot)  ✅ DONE
- [x] `EntityListSetting` — INVERTED storage (deselected ids), so empty default
      = everything targeted and new mobs auto-allowed. Config-persisted.
- [x] `MobPickerPopup` — right-click Hostiles/Passives toggle ("..." hint) →
      popup with live mob renders (`g.entity`, inventory-preview path), All/None,
      scroll, click-to-toggle. Catalog = one never-ticked entity per type, built
      on open, dropped on close; only visible rows extract render states.
- [x] Hostile classification switched `instanceof Monster` → `instanceof Enemy`
      (now covers ghasts, slimes, hoglins, dragon).

### 8. HUD batch — planned (researched 2026-07-07, craft next)

**Mini HUD panel in the editor (Right Ctrl):**  ✅ DONE
- [x] Draggable "HUD" panel centered in the editor: lists the HUD *widgets*
      (reads the live widget list, so new widgets appear automatically).
      Left-click = widget's own toggle, right-click = the same settings popup,
      title strip drags, auto-height + scrollbar when the list grows.

**Widget infrastructure (build once, before/with TargetHUD):**
- Each widget = a `HudWidget` subclass + a Boolean toggle on `HudModule` + a
  `popupRows` entry listing its settings. Per-widget extra settings live on
  `HudModule` (flat, prefixed names) — they show in the ClickGUI *and* the popup.
- Shared `Animation`/lerp helper for smooth values; slide+fade helper for
  widget show/hide (animate an alpha+offset per widget, driven in render()).
- `HudEntity` helper: extract+submit a living entity into a GUI box — lift
  `MobPickerPopup.drawMob` into a util so TargetHUD/PlayerModel reuse it.

**Top 15 HUD widgets — approach + options for each:**
1. [x] **TargetHUD** — DONE. Aura exposes `static Entity currentTarget`; card
       fades in/out. Live model via `HudEntity`, name, lerped health bar tinted
       red→green, gold absorption, hurt flash from `hurtTime`. Gear icons (glint
       + durability free from `g.item`), abbreviated enchant chips (`GearUtil`,
       first-4-letters + level, deduped max), potion effects (sprite via
       `Hud.getMobEffectSprite`, amplifier badge, mm:ss timer pulsing < 5s).
       Options: source (Aura/Crosshair/Both), bg, model, health number, hurt
       flash, gear, enchants, potions.
2. [x] **Keystrokes** — DONE. Reads `mc.options.keyUp/Left/Down/Right/keyJump/
       keyAttack/keyUse` `.isDown()` per frame. W-centered top row + A/S/D +
       wide space bar + LMB/RMB row; per-key `Animation` fills accent on press,
       eases out on release; label lerps to dark on press. CPS = rising click
       edges pushed into a 1s sliding `ArrayDeque`, size = the count, shown under
       each mouse key. `keystrokesBg` off = outline-only keys. Options: mouse
       keys, space bar, show CPS, key size (12–28px).
3. [x] **ArmorHUD** — DONE. Armor via `getItemBySlot` + optional main hand;
       `g.item` per piece, 2px durability bar green→yellow→red (two-stage lerp),
       optional % text, pulse below a threshold slider. Options: bg, vertical,
       held item, percent, blink %.
4. [x] **PotionHUD** — DONE. `getActiveEffects()` sorted by remaining ticks;
       sprite via `Hud.getMobEffectSprite` + `blitSprite`, name + roman level +
       mm:ss (∞ for infinite), alpha pulse < 100t. Full list or compact icon
       strip. Options: bg, compact, hide ambient.
5. [x] **Coords/Direction** — DONE. `getX/Y/Z` + 8-way facing from yaw (or
       degrees); second line with the other dimension's coords (×8 in nether via
       `level.dimension()==Level.NETHER`, else ÷8). Options: bg, dimension line,
       compact Y-only, degrees.
6. [x] **Speedometer** — DONE. Per-*distinct-tick* position delta / real dt =
       b/s (avoids per-frame zero flicker; decays to 0 when stopped, teleport
       guard). 48-sample sparkline via `Render2D.line`. Options: bg, unit
       (b/s ÷ km/h), sparkline, decimals.
7. [x] **PopCounter** — DONE. `LivingEntityMixin` on `handleEntityEvent` id 35
       (totem pop) → `SessionTracker` counts pops per entity UUID. Widget shows
       your pops + last target's. Options: bg, target pops, announce (native
       toast with a totem icon).
8. [x] **SessionInfo** — DONE. `SessionTracker` (ticked from `UnluckyClient`):
       session start time; kills = a recently-hit entity that starts dying or
       vanishes (attacks captured by `MultiPlayerGameModeMixin.attack`, covers
       manual + module attacks); deaths = local player alive→dead transition.
       Resets on connection change (survives dimension hops). Options: bg,
       time / kills / deaths / K-D rows.
9. [x] **InventoryViewer** — DONE. 9×3 grid of `getInventory()` slots 9–35 via
       `g.item` over a translucent rounded panel. Options: opacity slider,
       hide-when-empty.
10. [x] **Radar** — DONE. Scissored square canvas; nearby `entitiesForRendering`
        living entities projected from horizontal offset, scaled to range,
        rotated so the camera faces up (θ = π − yaw) or north-up. Dots colored
        player=accent / hostile=red / passive=green, center player marker.
        Options: bg, range, size, rotate, per-category toggles.
11. [x] **Notifications** — DONE, via the **native ToastManager** (achievement
        style, user preference). `UnluckyToast implements Toast` draws the vanilla
        `toast/advancement` frame: "Unlucky" header, "<Module> enabled/disabled"
        title, emerald/redstone icon for on/off. `NotificationManager.add(header,
        title, icon)` → `mc.gui.toastManager().addToast(...)`; module-toggle hook
        already existed. Gated by HUD `Notifications` toggle. Old custom
        bottom-right slide renderer removed.
12. [x] **PlayerModel** — DONE. `HudEntity` with `mc.player`; head/pitch follow
        where you look. Options: bg, follow look, armor, held items.
13. [x] **Ping/TPS/FPS** — DONE, folded into the **Info** widget as toggleable
        segments (not a separate widget, per user). FPS `mc.getFps()`, ping
        `getConnection().getPlayerInfo(uuid).getLatency()`, TPS via `ServerStats`
        fed by a `ClientPacketListenerMixin` on `handleSetTime` — measures
        `gameTime` delta ÷ real time (accurate at any send interval), clamped
        0–20, smoothed. Options: Info FPS / coords / ping / TPS row toggles.
14. [x] **ItemCounter** — DONE. Sums matching stacks across hotbar + main +
        offhand. Item chosen via Mode: Totem / XP Bottle / Obsidian / Ender Pearl
        / Gapple / Held (held reads the current main-hand item). Options: bg,
        item, show icon, warn-below threshold (pulses red).
15. [x] **Clock** — DONE. `LocalTime.now()` via `DateTimeFormatter`; optional
        in-game day + time from `level.getOverworldClockTime()` (26.2 renamed
        day-time → clock ticks). Options: bg, 12/24h, seconds, in-game time.

**Polish rules (apply to every widget):** share Theme accents + background +
rounded corners; every value change animates (lerp, never snap); slide+fade on
widget enable/disable; scale setting per widget; keep extraction cheap — no
per-frame allocation in render paths; all sized/positioned in the HUD editor
with the right-click popup for per-widget settings.

**Auto edge-alignment (done):** `HudWidget` resolves `anchorRight`/`anchorBottom`
each frame from its center vs the screen center. `alignedX(lineWidth, pad)`
justifies content toward the hugged horizontal edge — applied to the multi-line
text widgets (Coords, Clock, Speedometer, Info) so short lines flush to the
correct side. Blocks already sit flush to whichever vertical edge they're near
via the size-aware fractional positioning, and ArrayList already flips L/R.

## Extras (beyond the top-15)
- [x] **Item pickup notifier** — `ItemPickupWidget` (a real HUD widget, so it's
      draggable in the editor with a right-click settings popup). Sliding list of
      picked-up items. Fed by `ClientPacketListenerMixin` on `handleTakeItemEntity`
      (HEAD + `isSameThread` guard, reads the `ItemEntity` before its stack is
      shrunk, filtered to our player id) → `HudManager.itemPickups().onPickup`.
      Aggregates repeats (bumps count + refreshes timer), colored names via the
      hover-name Component, icon + count, newest on top, rows slide/justify toward
      the docked edge via `anchorRight()`, vertical collapse on expiry. Editor
      placeholder row when empty. Options: bg, duration.

## Backlog (deferred by choice)
- [x] Tab icons — DONE. `ClickGuiScreen.drawCategoryIcon` draws a vector glyph per
      category (sword / bust / double-chevron / eye / iso-cube / dots) in the
      search-icon style, replacing the CB/PL/MV letters. Category name shows as a
      hover tooltip via `hoveredDescription`. (`Category.iconPlaceholder()` now unused.)
- [x] Chams — DONE. `Chams` module (Render): re-renders each living entity's
      model as a tinted, full-bright silhouette. Custom `ChamsRenderType` pipeline
      copies `ENTITY_TRANSLUCENT` but drops the depth test (`withDepthStencilState
      (Optional.empty())`) → shows through terrain; depth-tested fallback via
      `RenderTypes.entityTranslucent`. Color stashed on `LivingEntityRenderState`
      in `extractRenderState` (like `outlineColor`), read in `LivingEntityRenderer
      .submit` (before popPose) to re-submit the model. Pipeline registered at
      init. Options: players, mobs, self, color, opacity, range, through-walls.
- [x] Sharper outline shader — override `assets/minecraft/post_effect/entity_outline.json`
      (dropped vanilla's 2 blur passes; crisp sobel outline at any distance).
- [x] Hidden-edge culling — `Render3D.box` now draws per-edge lines, skipping
      edges that only border faces pointing away from the camera (no back lines).
- [x] Real dropdown popups for `ModeSetting` — DONE (was implemented but never
      checked off): `ModeComponent` opens an animated, scrolling dropdown for
      4+ options (2–3 options still click-cycle).

## Release prep (2026-07-10)
- [x] README rewritten for GitHub (honest scope incl. combat, capes repo,
      install + build instructions, ARCHITECTURE.md link).
- [x] fabric.mod.json "ment" typo fixed; UnluckyClient.VERSION synced with
      gradle.properties mod_version — two-number scheme (1.0) per user
      preference, see ARCHITECTURE.md §9.
- [x] ARCHITECTURE.md added — contributor/AI orientation doc, updated per bump.

## Notes
- BookTools § stripping = vanilla server-side limit, not a bug (works on anarchy).
- Build + boot test each batch: watch for "Unlucky Client initialized", no Mixin errors.
- PowerShell 5.1 `utf8` writes BOM (javac rejects) → use
  `[System.IO.File]::WriteAllText(path, text, UTF8Encoding($false))`.

---