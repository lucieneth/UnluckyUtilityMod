# Unlucky Client — Work Plan v2 (the giga plan)

Everything from the v1 plan shipped in **v1.1** (see git history and
[ARCHITECTURE.md](ARCHITECTURE.md) for the full map of what exists). This plan
covers the next 18 modules, phased by shared infrastructure and risk — early
phases are quick wins that build momentum, later phases are the flagships that
need new foundations.

Ground rules (unchanged):
- Every phase ends with: build, boot smoke-test, ARCHITECTURE.md §3/§4 sync.
- No anticheat bypass — vanilla/anarchy semantics only. Movement is
  client-authoritative on vanilla servers; that's what makes most of Phase 1/3
  work at all.
- New settings reuse existing components; **never hand-roll pickers or text
  input** (`BlockPickerPopup`, `MobPickerPopup`, `ui/TextBox` exist — Phase 4
  adds `ItemPickerPopup` to complete the set).

---

## Phase 1 — Packet & tick quickies (5 modules) ✅ DONE (2026-07-10)

Research spike confirmed hunger/fall are **server-side, derived from what we
report** — see the new ARCHITECTURE.md §6 entry. NoFall + AntiHunger therefore
share one `LocalPlayerMixin` (both lie about the same `onGround` flag).

- [x] **NoFall** (Movement) — `@Redirect` on the 6 `onGround()` calls in
      `LocalPlayer.sendPosition`; the server resets its own fall distance
      whenever we claim to be grounded.
      Options: *Mode* (Packet / Constant), *Min fall distance* (3),
      *Disable during elytra* (on).
- [x] **AntiHunger** (Player) — spoof `onGround` (server never sees the jump →
      no jump exhaustion) + cancel `sendIsSprintingIfNeeded` (server never
      thinks we sprint → no sprint exhaustion). Resyncs sprint state on toggle.
      Options: *Spoof onGround* (on), *Spoof sprint* (on — costs sprint
      knockback). Honest: reduces drain, doesn't stop it.
- [x] **FastUse** (Player) — `Minecraft.startUseItem` RETURN, shortens
      `rightClickDelay`. Options: *Mode* (Everything / Food only — custom list
      lands with Phase 4's ItemPickerPopup), *Delay* (0–4).
- [x] **AntiLevitation** (Movement) — `@Redirect getEffect` in
      `LivingEntity.travelInAir` returns null (it's null-checked, so it falls
      through to normal gravity); slow falling via `getEffectiveGravity`.
      Options: *Levitation* (on), *Slow falling* (off).
- [x] **Yaw** (Movement) — hard `setYRot`/`setYHeadRot`/`yBodyRot` per tick.
      Options: *Mode* (Exact / Snap 45 / Snap 90), *Angle* (0–359).

## Phase 2 — Render toggles (3 modules) ✅ DONE (2026-07-10)

- [x] **NoWeather** (Render) — three hooks: `Level.getRainLevel`/`getThunderLevel`
      → 0 (rain, sky darkening, weather fog), `ClientLevel.tickWeatherEffects`
      cancelled (rain particles + ambient sound), `Level.setSkyFlashTime`
      cancelled (lightning flash). Options: *Rain*, *Thunder*.
      **`Level` is common** — hooks gated on "is this the client's level", see
      ARCHITECTURE.md §6.
- [x] **ViewClip** (Render) — `@ModifyArg` on the `getMaxZoom(4.0f)` call in
      `Camera.alignWithEntity` sets the distance; a cancellable inject at
      `getMaxZoom` HEAD skips the terrain raycast.
      Options: *Distance* (1–32, vanilla 4), *Clip through blocks* (on).
- [x] **NoRender** (Render) — overlays: *Fire*, *Block* (pumpkin/powder snow),
      *Water*, *Totem animation*, *Boss bars*, *Break particles*. Plus the
      situational fog moved off NoFog: *Water fog*, *Lava fog*, *Powder snow
      fog*, *Blindness fog*, *Darkness fog*. One injection each; designed to grow.
- [x] **NoFog rescoped** (Lucien's call): now only *Distance*, *Nether*, *End* —
      fog from **where you are**. Everything else is NoRender's, i.e. fog from
      **what's happening to you**. Fixed a latent bug in the process: the old
      mixin blanked both `FogData` channels for any trigger, so disabling water
      fog also killed render-distance fog.
      *Portal/nausea overlay* still deferred — its spin constants are
      `static final` and inlined by javac, so there's no call site to hook.

## Phase 3 — Movement trio (3 modules) ✅ DONE (2026-07-10)

- [x] **Jesus** (Movement) — `canStandOnFluid` RETURN inject. Turned out to be
      a *vanilla mechanic*, not a velocity hack: `LiquidBlock.getCollisionShape`
      asks the collision context, which asks `LivingEntity.canStandOnFluid`, so
      answering yes gives the fluid a real collision box (the strider's trick).
      Vanilla's own `isAbove` check means you still swim when submerged.
      Options: *Mode* (Solid / Dolphin), *Lava* (off), *Sneak to sink* (on).
- [x] **TridentFly** (Movement) — self-applied dash on right-click, cancelling
      the vanilla use. Routing through `TridentItem.releaseUsing` was rejected:
      it gates on `isInWaterOrRain()` **and** the enchant, and would *throw* an
      unenchanted trident. Motion is client-authoritative so the server accepts it.
      Options: *Any item* (off), *Strength* (0.5–5), *Cooldown*, *Spin animation*.
- [x] **ClickTP** (Movement) — own raycast (`Entity.pick`, since
      `Minecraft.hitResult` stops at interaction range) then `setPos`; the next
      movement packet carries it.
      Options: *Button* (Right / Middle), *Max distance* (8, capped at 10 —
      further trips the server's moved-too-quickly rubber-band), *Land on top* (on).
      ClickTP and TridentFly share one right-click handler; two cancellable
      injects at one point fire in undefined order. See ARCHITECTURE.md §6.

## Phase 4 — ItemPickerPopup + auto-utilities ✅ DONE (2026-07-10)

- [x] **`ItemPickerPopup`** (shared infra) — `ItemListSetting` carries a
      `Predicate<Item>` filter, so one popup serves every list-of-items setting.
      Catalog built from the item registry on open (air skipped), with its own
      `TextBox` search since even a filtered registry is long. Wired through
      `GroupBox`, `ClickGuiScreen` (incl. char/key routing) and `ConfigManager`.
- [x] **AutoEat** (Player) — holds the use key (`KeyMapping.setDown`) and lets
      vanilla eat: animation, timing, sounds and slot sync all come free.
      Options: *Hunger threshold* (16), *Blacklist* (food-filtered picker,
      pre-seeded with rotten flesh / spider eye / poisonous potato / pufferfish /
      chorus fruit / raw chicken / suspicious stew), *Prefer* (Best saturation /
      First in hotbar), *Swap back* (on).
      Exposes `AutoEat.busy()`; `MinecraftMixin`'s right-click handler checks it
      first, so ClickTP/TridentFly (and later Nuker) don't hijack a meal.
- [x] **AutoFish** (Player) — bite detected from the `FISHING_BOBBER_SPLASH`
      sound packet (the server's own signal), matched against **our** bobber's
      position so a neighbour's catch doesn't reel our line.
      Options: *Recast* (on), *Reel delay min/max* (randomised window),
      *Recast delay*.
- [x] **FastUse** gained its *Custom* mode + item list, as promised in Phase 1.

## Phase 5 — Search (1 module, reuses the ESP stack) ✅ DONE (2026-07-11)

- [x] **Search** (World) — find any block, show it through walls.
      Options: *Blocks* (`BlockListSetting` picker, preset diamond/deepslate
      diamond/ancient debris), *Range* (chunks), *Max results* (FPS guard),
      *Through walls*, *Color* + *Fill* + *Fill opacity*, *Tracers* (off) +
      *Tracer color*, *Occlusion cull* (on, with the StorageESP relevant-prefilter).
      Implementation: time-sliced ring scan on the tick thread — a hard chunk cap
      **and** a wall-clock budget per tick, refilling the ring from the player's
      current chunk **nearest-first** each pass. `LevelChunkSection.maybeHas`
      fast-rejects sections with no target before touching states; matched boxes
      cached and re-emitted each tick like TreasureESP. Runs on the tick thread
      reading loaded client chunks (main-thread safe — unlike the section
      *compiler*, §6). Verified in-world (results centered on the player).
      **Fix (2026-07-11, found by Lucien):** the ring was polled corner-first
      (`(-r,-r)` first, FIFO), so a dense block hit the Max-results cap on that one
      chunk and published only it — results appeared pinned `r` chunks to −X/−Z.
      Now the ring is sorted nearest-first, so the cap keeps the closest matches
      (a blob around you) and raising Range genuinely widens the area.

## Phase 6 — Nuker (the flagship interact module) ✅ DONE (2026-07-11)

- [x] **Nuker** (World) — Future/Meteor grade, fully featured. Verified in-world
      (survival path: 129 targets in range, progressive break via vanilla tracking,
      no exceptions). Two paths off `getAbilities().instabuild`: creative bursts
      `destroyBlock` up to *Blocks per tick*; survival calls `continueDestroyBlock`
      on one target (it auto-starts on a new pos and reports done — vanilla tracks
      progress), instant-break blocks still burst. Face = `Direction.getApproximateNearest`
      toward the eye; swing = client `swing()` / raw `ServerboundSwingPacket` / none.
      No new mixin (calls the public `MultiPlayerGameMode` methods). See §6.
      Options:
      - *Shape* (Sphere / Cube), *Range* (1–6)
      - *Mode* (All / Whitelist / Blacklist — BlockPickerPopup)
      - *Flatten* (only blocks at/above feet), *Smash* (instant-break only)
      - *Sort* (Closest / Furthest / Top-down)
      - *Blocks per tick* (1–5, creative/instabreak only), *Break delay*
      - *Rotate* (silent rotations via RotationManager — exists)
      - *Avoid liquids* (skip blocks adjacent to fluids, anti-flood, on)
      - *Swing* (Client / Packet / None)
      - *Pause while eating* (AutoEat interop from Phase 4)
      Implementation: survival path = one `startDestroyBlock` /
      `continueDestroyBlock` target at a time with progress tracking;
      creative path = burst destroy. `MultiPlayerGameModeMixin` already
      exists (attack tracking) — extend, don't duplicate.

## Phase 7 — NameTags (the flagship render module)

- [x] **NameTags** (Render) — vanilla-look tags, maximum information, fully
      customizable. Cancels the vanilla name-tag submit for players
      (`EntityRendererMixin` TAIL nulls `state.nameTag` via
      `NameTags.hidesVanilla`) and draws our own billboard through the ESP 2D
      projection pass (`Render3D.worldToScreen` above the head, one scaled pose
      for text + hearts + item icons). Delivered:
      - *Health* (Off / Number / Hearts — real `hud/heart/*` sprites via
        `blitSprite(GUI_TEXTURED,…)`, the heart row scaled to span the name line
        under it), *Ping* (latency-colored), *Distance*, *Gamemode* (S/C/A/SP
        prefix), all from `PlayerInfo`
      - *Armor row* (`GearUtil.gear` icons above the name) + *Enchants*
        (3-letter chips in an even, uniform-width column grid above each icon so
        they never collide; *Enchant limit* slider 5–45 caps the total)
      - *Scale*, *Constant size* (distance-falloff when off)
      - *Background* mode: Off / Custom (opacity slider) / Vanilla (game's own
        text-backdrop opacity) — centered on the head anchor
      - *Through walls* (off = `hasLineOfSight` cull), *Self* in third person (off)
      - Name color setting today; friend color when the Friends system lands.

## Phase 8 — InventoryInfo (the tooltip suite)

- [~] **InventoryInfo** (Misc) — make every tooltip informative. All previews
      shipped (container, ender chest, map, banner, book, byte size); only the
      container fullness bar remains.
      - [x] *Container preview* — inventory-style grid tooltip on hover for any
        `DataComponents.CONTAINER` stack (shulkers). `ItemStackTooltipMixin`
        (`getTooltipImage`) hands a `ContainerTooltipData` to the tooltip
        system; the Fabric `ClientTooltipComponentCallback` (registered in
        `UnluckyClient.init`) maps it to `ContainerPreviewComponent`;
        `ItemContainerContentsMixin` cancels the vanilla text so they don't
        double up. Verified: world join, 0 mixin/injection errors.
        *Preview style* setting: Slot (per-cell `slot.png`) or GUI (the full
        `container.png` panel). Both `assets/unlucky/textures/gui/`.
      - [x] *Byte size* — `ItemStack.STREAM_CODEC` into a
        `RegistryFriendlyByteBuf`, `readableBytes()` → B/KB line.
      - [x] *Ender chest preview* — grid from the client's cached
        `player.getEnderChestInventory()` (last-seen since opened this session),
        drawn on the ender chest item with its own `enderchest.png` panel.
      - [x] *Map preview* — filled-map image (`prepareMapTexture`) drawn inside
        the `map.png` parchment frame (96px content, 120px framed).
      - [x] *Banner preview* — the banner item rendered scaled 4× (banners draw
        their patterns in-icon, so no atlas compositing needed).
      - [x] *Book preview* — first written page wrapped onto the `book.png`
        parchment (image component, dark ink in the writing area).
      - [ ] *Fullness bar* on containers (stretch)

- **Chams** got a *Mode* setting (Flat / CS:GO / **Image**). The Image mode is
  **screen-space + in-place**: it swaps the model's own render type via
  `getRenderType` (Meteor-style) so the model draws once as the galaxy — no
  coincident re-render, so no z-fighting / halo (the earlier re-submit + depth
  bias couldn't fully fix that). It uses a custom entity shader pair
  (`assets/unlucky/shaders/core/chams_screen.vsh/.fsh`, copies of `core/entity`
  that sample `Sampler0` by per-fragment screen position from the interpolated
  clip pos) wired through two `ChamsRenderType` pipelines (through-walls / depth).
  The galaxy stays fixed while the model moves through it — no framebuffer or
  post-chain needed. Verified live: forced the local model into chams-Image and
  the shader compiled + rendered with 0 GL/shader errors.
  - [x] **End portal** chams mode ✅ (2026-07-12) — new `chams_portal.fsh`: vanilla
    `rendertype_end_portal` layer math verbatim (COLORS table, GameTime scroll,
    15 layers) sampled by screen position; shares `chams_screen.vsh`. Single
    sampler: all layers read `textures/entity/end_portal/end_portal.png`
    (26.2 moved it into a subfolder — the flat path renders magenta); the end-sky
    layer is a measured constant — end_sky.png averages a BRIGHT (0.45, 0.34,
    0.61), and its COLORS[0] product is the portal's ambient blue glow (first
    attempt assumed it was near-black and rendered too dark). GameTime
    works because ENTITY_SNIPPET chains the GLOBALS bind group. In-place swap
    like Image (`Chams.inPlaceMode()`), so no z-fighting by construction.

## Phase 9 — Baritone integration (research-gated stretch)

- [ ] **Reality check FIRST**: does a Baritone build for MC 26.2 exist at all?
      Baritone historically lags major versions. If it doesn't exist, park
      this phase (do NOT write our own pathfinder) and revisit.
- [ ] If it exists: soft dependency only — `compileOnly` the Baritone API,
      `FabricLoader.isModLoaded("baritone")` at runtime, zero hard links.
      Surface: *Baritone* module (Misc) exposing goto/mine/follow through our
      command-ish UI, mine target via BlockPickerPopup, auto-pause our
      interact modules (Nuker, AutoEat handled via the Phase 4 interop hook)
      while pathing, and a ClickGUI indicator while Baritone drives.

## Phase 10 — FPS optimization pass (planned 2026-07-12, code audit done — not started)

Goal: the client's render-path overhead should be ~zero when features are off and
scale gracefully on crowded servers. Ordered by expected impact; every tier ends
with a measurement so we only continue while the numbers say it's worth it.

### Tier 0 — measure first ✅ DONE (2026-07-12)
- [x] Generalize the `-Dunlucky.espDebug` pattern into `-Dunlucky.perfDebug`:
      nano-time `renderHud` (split: PlayerESP overlay / NameTags overlay / HUD
      widgets individually) and each enabled module's `onTick`, log rolling
      avg/max once a second. Zero cost when the flag is off (same guard as
      StorageESP Phase 0). All later tiers get before/after numbers from this.
      Also reachable via env `UNLUCKY_PERF_DEBUG=true` (survives the gradle daemon).
      **Baseline (VerifyWorld, alone, ~265 fps, 2026-07-12):** HUD widgets total
      ≈0.35 ms/frame — Watermark 0.067 / ArrayList 0.066 / SessionInfo 0.031 /
      Info 0.030 / ArmorHUD 0.030 / Radar 0.027 / Coords 0.022 avg; overlays +
      avoidance ≈0.001 (empty world — the per-player costs need a server);
      tick.* all ≤0.012 ms. So in singleplayer the whole client is ~10% of a
      3.8 ms frame, nearly all of it Tier-2 widget churn.

### Tier 1 — gating bugs ✅ DONE (2026-07-12)
- [x] **`PlayerESP.renderOverlay` and `NameTags.renderOverlay` never check
      `isEnabled()`** — `UnluckyClient.renderHud` calls both unconditionally every
      frame. Invisible in singleplayer only because `targets()` skips the local
      player; on a server a *disabled* PlayerESP still draws boxes (correctness
      bug, same class as the Jesus `standsOn()` fix) and a disabled NameTags still
      walks/sorts the player list. Add the `isEnabled()` early-out inside each
      module (not the call site), matching how `onTick` is centrally gated.
- [x] **`ModuleManager.get(Class)` is an O(70) `isInstance` scan** and sits on the
      hottest paths: ~5 calls per entity per frame (EntityRendererMixin extract →
      EspGlow/NameTags.hidesVanilla/Chams/Spinbot, LivingEntityRendererMixin
      getRenderType + submit) plus one per HUD widget per frame. Back it with an
      `IdentityHashMap<Class<?>, Module>` built in `init()`; keep the list for
      iteration order.

### Tier 2 — per-frame allocation & re-measure churn ✅ DONE (2026-07-12)

**Result (same VerifyWorld scene as the baseline):** overall ~260 → ~330 fps;
ArrayList 0.066 → 0.050 ms; Watermark 0.067 → 0.061 (its cost is the strip
draws, only the animate path was churning); overlays now truly zero when off.
The per-player wins (NameTags/PlayerESP splits) don't show alone in
singleplayer — they land on servers. Skipped as not-worth-it after measuring:
HudManager avoidance (0.000 ms) and per-widget string caching (text *draw*
dominates those widgets, not string building). Also fixed in this tier: the
**HUD editor 30 fps drop** (see Fixes — one `g.fill` per grid dot; now a single
tiled `hud_grid` sprite).

- [x] **`Render3D.worldToScreen` rebuilds the view-projection matrix per call**
      (`camera.getViewRotationProjectionMatrix(new Matrix4f())` + a Vector4f + a
      Vec3 each call). PlayerESP calls it 8×/player/frame (box corners), +16 for
      skeleton, +2/tracer; NameTags 1×/player. Cache the matrix once per frame
      (frame-counter-stamped static) and transform with scratch objects.
- [x] **NameTags builds the whole tag from scratch every frame per player**: Seg
      list + name/health/ping/distance strings, `font().width()` per segment
      (twice — once to total, once to advance), gear list, `getEnchantments()`
      walk, chip abbreviations, per-chip width×2. Split per-tick (build a cached
      TagModel: strings, colors, pre-measured widths, chips) from per-frame
      (project + draw the cached model). Also `targets()` allocates + sorts per
      frame, and with Through-walls OFF does a **`hasLineOfSight` raycast per
      player per frame** — move selection to tick, keep only interpolation and
      projection per frame.
- [x] **PlayerESP same split**: per-tick target list; reuse corner scratch instead
      of `new Vec3` per corner; name/distance strings per tick.
- [x] **ArrayListWidget** iterates all ~70 modules per frame with
      `Render2D.width(name)` up to twice per module (a glyph walk) plus a sort.
      Module names never change: cache widths once in a map; short-circuit
      fully-collapsed animations before measuring.
- [x] ~~HudManager.applyAvoidance scratch lists~~ — measured 0.000 ms avg;
      skipped on the numbers, not worth the churn.
- [x] ~~Per-widget string-building sweep~~ — skipped: the measured widget cost is
      the text *draw* (glyph submission), not string building; caching strings
      wouldn't move the needle.

### Tier 3 — tooltip hover costs (InventoryInfo) ✅ DONE (2026-07-12)
- [x] **Byte-size line runs `ItemStack.STREAM_CODEC.encode` on every frame** the
      tooltip is visible (vanilla rebuilds tooltip lines per frame) — for a full
      shulker that's a full NBT encode per frame, plus a netty buffer alloc.
      Cache size by stack identity (recompute when the hovered ItemStack instance
      changes; identity check is enough — hover swaps instances).
- [x] Same for `getTooltipImage`: container/ender-chest previews re-copy the item
      list per frame (`nonEmptyItemCopyStream().toList()`). Cache the carrier per
      stack instance. Both caches are identity-keyed statics in
      `ItemStackTooltipMixin` (+count for the size line, since count mutates in
      place); leaving the slot swaps the instance and refreshes.

### Tier 4 — tick-thread render work (main-thread frame time)
- [ ] **Port StorageESP's Phase-3 `BoxGeom` cache to Search**: with occlusion on,
      Search re-runs the full clip (probe raycasts per face/edge ×12 edges ×
      up to `maxResults` boxes) every tick; StorageESP already proved the
      camera-stamped cache pattern (rebuild only on rescan/camera move/toggle).
      Search is the worst offender at 500–4000 boxes.
- [ ] `Render3D.visibleFillGeometry/visibleEdgesGeometry` allocate 8 corner Vec3s
      + faces/edges arrays per box per tick even on the immediate path — worth a
      scratch-buffer pass only if Tier-0 numbers show Search/TreasureESP high with
      realistic result counts.
- [ ] XRay `hides()` does a `ThreadLocal.get()` per block state during section
      meshing — only matters during rebuild storms (toggle, list edit); hoist to a
      per-section local passed through the compiler mixin if profiling shows it.

### Tier 5 — raise FPS *above* baseline (feature ideas, ask Lucien first)
- [ ] **EntityCulling-style module**: skip `submit` entirely for entities whose
      bounding box is fully occluded by terrain (cheap raycasts, budgeted on
      tick, cached per entity). This is the one item that beats vanilla FPS on
      crowded servers instead of just shrinking our own overhead.
- [ ] NoRender additions with real FPS impact: particle throttle/cap, armor-stand
      skip, distant tile-entity animation skip. AutoDrawDistance already exists.

Verification per tier: `-Dunlucky.perfDebug` before/after in the same VerifyWorld
scene, plus the usual visual-parity check. Alloc churn: quick spark/VisualVM
sample or `-verbose:gc` while standing in a Search-heavy area.


---

## Phase 11 — Friends & networking (Phase 1 done 2026-07-12)

The design discussion (2026-07-12): everything social — "is that player an Unlucky
user", "what cape", "friend online" — is a **UUID-keyed lookup, not realtime**
(the OptiFine-capes model). P2P was ruled out: discovery needs a rendezvous server
anyway, MC servers don't relay custom channels between clients, NAT traversal, and
direct connections leak user IPs to each other. A tiny hosted registry is the way.

### Phase 1 — local friends + blue dot ✅ DONE (2026-07-12)
- [x] `FriendManager` (util): UUID → last-known name, persisted to
      `config/unlucky/friends.json`, save-on-change, lazy load.
- [x] Config moved `config/unlucky.json` → `config/unlucky/config.json`
      (auto-migrates via `Files.move` on first load) — config, friends and the
      cape cache now all live under `config/unlucky/`.
- [x] `Friends` module (MISC, **enabled by default** via `setEnabledSilently(true)`
      in the constructor — config still overrides): Middle click / Tablist dot /
      Nametag dot settings.
- [x] Middle-click a player under the crosshair → toggle friend + toast
      (`MouseHandlerMixin.onButton` HEAD; vanilla pick-block unaffected).
- [x] Blue `•` (0xFF4A9BFF) before friend names: tablist via
      `PlayerTabOverlayMixin` on `getNameForDisplay` at RETURN (single source for
      measure + draw), NameTags via a prepended Seg in `buildTag`.
- Not yet: friend colors in ESP/PlayerESP, chat dot.

### Phase 1.5 — Friends GUI, console, polish ✅ DONE (2026-07-12)
- [x] **FriendsScreen** (toolbar → Friends): add by name (tablist first, then
      Mojang profile API via the shared `MojangLookup` util), scrollable list,
      per-row remove, status line for lookup feedback.
- [x] **CS:GO console** (`;` key, rebindable via `consoleKey` in config;
      toolbar icon from Lucien's SVG rasterized to `icons/console.png`):
      static scrollback (500 lines), input history (↑/↓), PgUp/PgDn, Submit.
      Commands are bare words (`CommandManager`): help, toggle/t, bind
      (a-z/0-9/f1-f12/none), friend add/remove/list, modules, say, clear.
- [x] **NameTags enchant fixes**: limit is now per item (one god-piece can't
      starve the rest; setting rescaled 1–10, default 4); `GearUtil.clean`
      strips Private Use Area/surrogate/control glyphs so texture packs that
      prepend icon-font glyphs to enchant names abbreviate correctly again.
- [x] **Toast avoidance**: top-right HUD widgets slide down while any toasts
      show (module toggles, advancements, the music "now playing" card) —
      potion band and toast band merge so nothing double-pushes. Occupancy via
      `ToastManagerAccessor.freeSlotCount`.
- [x] **XRay under Sodium** (took two rounds — first attempt didn't work in game):
      `shouldDrawSide` is only the FRAPI/non-terrain path; Sodium's TERRAIN
      mesher culls via `isFaceCulled`/`prepareCulling`, declared on
      `AbstractBlockRenderContext` — NOT on `BlockRenderer` (targeting it there
      made the whole mixin silently fail: one invalid injection aborts the
      entire mixin and require=0 hid it; constant-pool dumps list *called*
      methods too, only the declared-method table is truth). Fix:
      `SodiumBlockRendererMixin.renderModel` HEAD cancels hidden states (no
      quads at all); `SodiumBlockRenderContextMixin` forces shouldDrawSide +
      isFaceCulled while active (kept blocks draw every face). All three hooks
      log-verified alive on Chunk Render Task Executor threads. AND
      the other half: Sodium's occlusion culler builds on **vanilla VisGraph**,
      so enclosed caves were section-culled regardless of faces —
      `VisGraphMixin.setOpaque` HEAD-cancel while active opens the graph for
      both pipelines. All sodium-target mixins: string targets + require 0
      (self-skip without Sodium, log-not-crash on rename). Fluids still
      unhooked under Sodium.
      - **AND the actual "hooks fire but nothing hides" bug** (2026-07-13):
        `active()/hides()/fullbrightActive()` all gate on a `SECTION_IN_RANGE`
        ThreadLocal that ONLY the vanilla section compiler sets — permanently
        false on Sodium's mesh threads. Added position-based `hidesAt`/
        `activeAt`/`fullbrightAt(pos)` variants (range test against the block
        pos, which Sodium hands us anyway) and switched every sodium hook to
        them; VisGraph uses plain `enabled()`. Proof-logged real block cancels
        + fullbright forces from Chunk Render Task Executor threads.
      - **XRay fullbright under Sodium**: vanilla flat-shade path all bypassed →
        ores dark again. `SodiumLightDataAccessMixin` `@ModifyReturnValue` on
        `LightDataAccess.compute` rebuilds the packed light word (full block+sky
        light, flat AO, no emissive; opacity/full-cube flags preserved via
        shadowed pack/unpack helpers).
- [x] **Console `;` on non-US layouts**: GLFW keycodes are US-physical; Czech
      puts `;` on the grave key, so key 59 never arrived. When consoleKey is
      the default, any key whose `glfwGetKeyName` is ";" opens the console too.
- [x] **Console window**: CS-style close `x` in the title bar, draggable title
      bar, bottom-right resize grip; geometry static-persisted, default
      440x280 (ClickGUI-sized).
- [x] **Friends name auto-refresh**: friendships are UUID-keyed; every 10s the
      Friends module refreshes stored names of friends seen online under a new
      name (saves only on change).

### Phase 2 — the registry (first hosted piece; ask Lucien before starting)
- [ ] Cloudflare Worker on unlucky.life (free tier: 100k req/day; KV/DO for the
      registry, R2 if textures ever move off the GitHub cape repo). Own repo.
- [ ] Auth = Mojang joinServer/hasJoined handshake (what Lunar/Essential/Meteor
      do; no password/token ever reaches our server). Short-lived write token.
- [ ] `PUT /v1/cape` (choice from the curated list — no uploads, no moderation
      burden), `GET /v1/users?uuids=...` batched + cached ~5min (negative too).
- [ ] Client: letter/dot in tablist for Unlucky users, render other users'
      registered capes through the existing getSkin-swap path.

### Phase 3 — cross-server presence (opt-in, later)
- [ ] Heartbeat (UUID + hashed server address) + friend polling, or a Durable
      Object WebSocket for instant "friend online" toasts. Privacy: opt-in only.

## Phase 12 — Heads & identity visuals (12.1 done 2026-07-13)

### Phase 12.1 — Heads module + compass bar ✅ DONE (2026-07-13)
- [x] **HeadRenderer** util: 2D face+hat from just a UUID — tablist skin fast
      path, else vanilla `PlayerSkinRenderCache` (`ResolvableProfile.createUnresolved`)
      which downloads async and serves Steve/Alex until resolved. One
      `PlayerFaceExtractor` call, ARGB-tintable for fades.
- [x] **Heads module** (RENDER): chat heads. Sender chain: `ChatListenerMixin`
      stashes the signed sender in `showMessageToPlayer` → `ChatComponentMixin`
      moves it via a cancel-safe two-step handoff onto the `GuiMessage` (duck
      field, `GuiMessageMixin` on the record) → `splitLines` wraps 12px narrower
      and prepends a 3-space spacer per line (hover/click x-math stays native) →
      the two `ChatComponent$Drawing*GraphicsAccess` inner classes draw the face
      at exact line y. "Guess sender" setting matches plugin-formatted messages
      (`<name>` + first-tokens scan against `getDiscoveredUUID`). Toggle
      re-flows via `rescaleChat()`. Verified end-to-end in VerifyWorld
      (auto-sent signed chat → `[HeadsDebug] chat head drawn`).
- [x] **CompassBarWidget** (HUD, off by default, top-center): cardinal strip +
      15° ticks scrolling with yaw (MC yaw-space throughout, bearing =
      `atan2(-dx, dz)`), accent center caret; nearby players projected by
      bearing as heads, friend blue dot, alpha fades with distance. Settings:
      width, FOV, players on/off, friends-only, range.
- [x] Tablist "HEAD • NAME" needed no work — vanilla draws tablist faces
      (`PlayerTabOverlay` → `PlayerFaceExtractor`), our dot already prepends.

### Phase 12.2 — in-game skin & cape changer ✅ DONE (2026-07-13, needs Lucien's online-account pass)
Real account changes (not spoofed) via `api.minecraftservices.com`, bearer =
`mc.getUser().getAccessToken()` — exactly what Pandora Launcher does (verified
in its source: multipart POST `/minecraft/profile/skins`, PUT/DELETE
`/minecraft/profile/capes/active`, owned capes from GET `/minecraft/profile`).
- [x] S1: **`MinecraftServicesApi`** — async (`java.net.http` like MojangLookup,
      callbacks on the client thread): GET profile (active skin + owned capes),
      POST skin by URL / multipart PNG upload, DELETE skin (default), PUT/DELETE
      active cape, plus sessionserver skin-of-player (base64 `textures`,
      variant included) for copy-from-player. Errors surface Mojang's
      `errorMessage`; 401 → "Not authenticated (offline session?)" so dev runs
      degrade gracefully. Profile fetched once per screen visit (rate limit
      200 req/2 min/IP).
- [x] S2: **TitleScreenMixin** per the mockup — left strip: 100×110 live
      preview (`SkinPreviewWidget` → `SkinRender`, the vanilla
      `GuiGraphicsExtractor.skin` primitive with look-at-mouse rotation instead
      of drag), **Edit** + **NameMC** vanilla buttons beneath. Re-added each
      `init`, survives resizes.
- [x] S3: **SkinsScreen** (`gui/skins/`, restyled 2026-07-13 per Lucien to
      classic vanilla menu look: vanilla background/Buttons/EditBox/CycleButton,
      centered title, bottom [Apply Changes][Back] row) — staged-changes model:
      owned-capes grid ("None" = hide; front crops downloaded via the same
      `SkinTextureDownloader` pipeline CapeManager uses, cached to
      `config/unlucky/capes/owned/`), one smart input (URL **or** player name —
      name resolves via MojangLookup + sessionserver and copies the variant
      too), File (TinyFileDialogs off-thread; it blocks), Dir (opens
      `config/unlucky/skins`), Classic/Slim segment, Default skin, Apply/Revert.
      Apply chains skin op → cape op → profile re-fetch; variant-only change
      re-POSTs the current skin URL with the new arms.
- [x] S4: instant local preview — staged files register as `DynamicTexture`,
      staged URLs force-download through the skin pipeline (cache file deleted
      first since `downloadAndRegisterSkin` short-circuits on existing files);
      the preview keeps showing the applied skin after save. Others/servers
      still see the old skin until next join (Mojang hands textures out at
      join; same limitation as any launcher).
- Verified: build green, dev client boots to title with the panel mixin applied
  (defaultRequire 1), offline session degrades to the auth message.
  2026-07-13: Lucien production-tested — cape change confirmed live (v1.5).

### Phase 12.3 — chibi sprites + locator heads + XP fix ✅ DONE (2026-07-13)
- [x] **PlayerSprite** (`util/`): an **exact clone** of SkinSprite Studio's
      renderer (sss.1m3.jp; verified mean err ~3/255, zero alpha mismatches vs
      the site's native exports — v1 was an eyeballed approximation, replaced
      2026-07-13). Recipe recovered with Lucien via **calibration skins**: his
      region-colored template first (revealed block layout + a shared-color
      collision), then two coordinate-encoded templates (face ID in blue, x/y
      gradients in red/green) run through the site — every output pixel
      decoded to (face, src x, src y). Key findings: the site bakes a **12%
      desaturation toward Rec.601 luma** into every pixel (t=0.120 — THE
      pastel signature; luma-preserving → exactly invertible, which made
      decoding lossless); geometry = yaw-ortho projection, each cube face an
      axis-aligned rect (head front 8→16 + full side 8→6, hat +1px overhang
      all around, torso 8x12→10x10 + 1px side sliver, arms shoulder-drooped
      1px with the far arm occluded to 2 cols, legs 12→6 tall); sampling = box
      filter with coverage alpha-blending for overlays. 24x33 core + 1px
      outline = 26x35. Async per UUID: disk cache
      `config/unlucky/sprites/<uuid>.png` (1-day refresh + stale-format
      check) or sessionserver → skin download → compose off-thread →
      DynamicTexture. Legacy 64x32 skins mirror right limbs. `get()` null
      while cooking → HeadRenderer fallback. Decoder scripts in the session
      scratchpad (`sprite/`), calibration pairs in `Original files/`.
- [x] **FriendsScreen**: rows 15→24px with the chibi sprite icon per friend
      (face fallback while loading), dot + name beside.
- [x] **Locator bar heads** (the original ask — "compass bar" was a mixup, the
      widget stays as a bonus): `LocatorBarMixin` `@WrapOperation`s the one
      7-arg color `blitSprite` inside vanilla's forEachWaypoint lambda
      (`method = "*"` — the pitch arrows use the 6-arg variant so it's
      unambiguous), `@Local` grabs the TrackedWaypoint → player-UUID waypoints
      draw the head + friend dot, string waypoints keep the vanilla dot.
      Heads module setting "Locator bar" (on).
- [x] **AutoXPRepair look-down**: bottles now throw with a server-side-only
      pitch-90 (RotationManager, like Aura) so orbs land at your feet — no
      first-person flick. Root fix in `ClientCommonPacketListenerMixin`: since
      ~1.20.2 `ServerboundUseItemPacket` carries its own yaw/pitch which the
      server re-applies before item use, so the spoof now rewrites that packet
      too — previously ALL spoofed rotations were silently ignored for thrown
      items.

### Phase 12.4 — FoodOverlay (AppleSkin-style) ✅ DONE (2026-07-13)
- [x] **FoodOverlay** (RENDER): saturation overlay using **AppleSkin's own
      gold arc sprites** (extracted from Lucien's dropped icons.png row v=0 —
      the v1 hand-drawn square ring is replaced; their red row is unused by
      saturation, the dark dither is the exhaustion bar which needs their
      server mod). Buckets match their HUDOverlayHandler exactly (pip
      fraction >0/>.25/>.5/>=1 — porting this also fixed a v1 index bug where
      sub-0.5 saturation fragments crashed the lookup). Flash = their
      triangle wave with dwell (0.125/tick, clamp of -0.5..1.5, peak 0.65),
      not a sine. Restore preview of held food (main/offhand
      `DataComponents.FOOD`, hunger-effect variants). Reference assets
      archived in `Original files/appleskin/` (incl. tooltip_hunger_outline
      for a future food-tooltip feature). Hook: `HudMixin` `@Inject` TAIL of
      `Hud.extractFood(g, player, y, rightX)` — same coords vanilla laid the
      pips with (`x = rightX - i*8 - 9`, 9x9). Saturation reaches the client
      in `ClientboundSetHealthPacket`, so it works on any server; exhaustion
      never syncs without a server mod → deliberately no exhaustion underlay.
      **Resource-pack support**: ring sprites live at
      `assets/unlucky/textures/gui/sprites/food/saturation_{full,3,2,1}.png`
      and the vanilla GUI atlas directory-source stitches ALL namespaces from
      `gui/sprites`, so packs restyle them by shipping the same paths — the
      AppleSkin-style customization for free.
- [x] **Full feature parity pass** (2026-07-13, "why split the saturation"):
      the remaining AppleSkin features, all ported from their 26.2-fabric
      source. (a) **Saturation restore preview** — held food also flashes the
      gold arcs its saturation would back. (b) **Health restore preview** —
      flashes the hearts natural regen would heal after eating;
      `estimatedHealthIncrement` is their exact regen simulation (6.0
      exhaustion per heal, 4.0 overflow steps draining saturation then food,
      batched saturated-regen iterations); hook `Hud.extractHearts` TAIL with
      vanilla's own left/top/rows args (`rowHeight = max(10-(rows-2), 3)`),
      faint container at 0.25x alpha under the flashing heart, hardcore
      sprite variants. (c) **Food value tooltips** — `FoodTooltipData` +
      `FoodValueComponent` through the existing InventoryInfo carrier +
      Fabric-callback pipeline: outline + drumstick row (half for odd
      nutrition, rotten variants when the Consumable applies Hunger — their
      isRotten), 7px saturation icons from their icons.png v=27 strip (red
      v=34 when rotten), >10 icons collapses to icon + "x N". Renders after
      the tooltip title (our client's preview position), not at the bottom
      like AppleSkin. (d) **Exhaustion bar** — their 81x9 dither
      (`food/exhaustion` sprite) right-anchored behind the pips at 0.75
      alpha, ratio/4.0; vanilla never syncs exhaustion so it reads the
      integrated-server player via `FoodDataAccessor` (no vanilla getter) —
      real values in singleplayer, silently absent on servers. "Show when
      holding" setting (off) overrides AppleSkin's could-you-eat-it gate so
      previews also show while full.

### Phase 12.5 — nametag scoreboard + friend-dot polish ✅ DONE (2026-07-13)
- [x] **Scoreboard row in NameTags**: the giant vanilla below_name line ("6
      Deaths") survived our tag because 26.2 splits it into a separate render
      state field — `EntityRendererMixin` now nulls `state.scoreText`
      alongside `state.nameTag`, and NameTags renders
      `player.belowNameDisplay()` (vanilla's ready-made "<score> <objective>"
      Component, null when no objective) as a tight styled row 1px under the
      name, sharing the tag's backdrop/scale. New "Scoreboard" setting (on).
- [x] **Friend dots, unified + self**: Friends now exposes
      `dotColor(uuid)` (friend blue / self green / 0) with per-surface
      wrappers; new settings "Chat dot" (on) and "Self dot" (off,
      `FriendManager.SELF_COLOR` green, applies to tablist + NameTags +
      locator/compass dots). Chat heads get the same 3x3 corner dot as the
      locator/compass ones, faded with the chat line's opacity.

## Phase 13 — 3DSkinLayers (tr7zw/3d-skin-layers recreation)

### Phase 13.1 — mesh foundation ✅ DONE (2026-07-13)
- [x] **Source study** (their `main` branch): the whole trick is
      `SolidPixelWrapper.wrapBox` — for every pixel on every face of the
      overlay box: skip transparent, emit a 1px cube, hide side faces a
      neighbouring pixel covers (including neighbours continuing around the
      box edge onto the adjacent face), and when a border pixel's backside
      face also has content, mark a *corner* that collapses the shared quad
      to a triangle (their z-fighting fix). Solid pixels never hide behind
      translucent ones. Thresholds: present = `getLuminanceOrAlpha != 0`,
      solid = `== -1` (255). Geometry flattens to `float[]` (23/quad:
      normal + 4x pos/uv, pos pre-/16) rendered directly to a
      VertexConsumer — deliberately NOT a ModelPart so Sodium/Iris can't
      rewrite it. Part table: hat 8x8x8@(32,0) pivot-bottom +0.6, jacket
      8x12x4@(16,32), sleeves (slim 3)x12x4@(40,32)R/(48,48)L top-pivot -2,
      pants 4x12x4@(0,32)R/(0,48)L. 64x64 skins only.
- [x] **Port** (`util/skinlayers/`): `VoxelMesh` (baked quads + ModelPart
      pose copy + PoseStack render, 26.2 fused `addVertex`),
      `SolidPixelWrapper` (algorithm 1:1 on vanilla `Direction`),
      `SkinLayerMeshes` (cache keyed skin Identifier + slim; FAILED sentinel
      so HD/pending skins don't rebuild per frame; pixels via resource
      manager for bundled skins / `DynamicTexture#getPixels` for downloaded;
      `getLuminanceOrAlpha` confirmed unchanged in 26.2). Module skeleton
      `SkinLayers3D` (head/body/arms/legs + render distance) — **not yet
      registered**, lands with rendering. Their fastRender/Iris paths
      skipped for now. BUILD_OK.

### Phase 13.2 — render integration ✅ CODE DONE, boot-verified (2026-07-14)
- [x] **SkinLayer3DFeature** (`util/skinlayers/`): a
      `RenderLayer<AvatarRenderState, PlayerModel>` added to `AvatarRenderer`
      in its constructor via `AvatarRendererMixin` (`@Inject <init>` TAIL +
      `LivingEntityRendererInvoker`'s `@Invoker` for the protected inherited
      `addLayer` — plain `@Shadow` fails because it's declared on the
      superclass). Per part: pose the PoseStack with the animated *base*
      part's `translateAndRotate` (so layers follow the walk/swing for free),
      apply the mod's exact offset table (voxel scale 1.15 / body-width 1.05 /
      head 1.18, height 1.035; Shape y −0.2 body/leg, −0.1 arm; arm side ±0.998
      wide / ±0.499 slim), then `SubmitNodeCollector.submitCustomGeometry`
      (the 26.2 deferred path — snapshots the pose, calls `VoxelMesh.writeTo`
      in the render pass, fused 11-arg `addVertex`). RenderType
      `RenderTypes.entityTranslucent(skin, true)`.
- [x] **Flat layer hidden** by `PlayerModelMixin` (`setupAnim(AvatarRenderState)`
      TAIL): sets the enabled overlay parts (hat/jacket/sleeves/pants)
      `visible=false` under the same gate the layer uses (enabled + in range +
      mesh buildable), so 3D replaces flat, never doubles. Parts keep their
      animated transform; only visibility flips.
- [x] **VoxelMesh** refactored to the deferred model: dropped the ModelPart
      pose fields, `writeTo(PoseStack.Pose, …)` streams baked quads. Mesh
      cache now retries not-yet-downloaded skins (only caches usable meshes or
      a permanent HD-fail). Module registered (default **off** pending Lucien's
      visual check), `replaces()`/`meshesFor()`/`isSlim()` shared gate.
- [x] **Boot-verified**: build green, world-join clean, all four SkinLayers
      mixins apply, layer registers, zero injection/render-thread errors.
- [ ] **Visual verification pending (Lucien)**: a headless first-person
      auto-join never renders the local player model, so the actual voxel draw
      wasn't exercised on screen. Needs F5 / another player to confirm
      alignment. **Likely tweaks:** floating/offset layers → nudge the Offset
      table; z-fighting → small outward scale bump.
- [ ] **First-person hands** — separate renderer (their FIRSTPERSON offset
      providers); deferred to 13.3.

## Phase 14 — Alt account switcher (PandoraLauncher-referenced)

### Phase 14.1–14.3 ✅ CODE DONE, boot-verified (2026-07-14)
- [x] **Runtime session swap** (`util/alts/`, `MinecraftAccessor`): swaps the
      live account with no restart. The trap — swapping only `Minecraft.user`
      breaks server joins, because `getGameProfile()` reads the startup
      `profileFuture` first and only falls back to `user` when null, so you'd
      join with the new token but the OLD uuid → auth fail. `AccountSwitcher`
      replaces **both** `user` and `profileFuture` (a completed
      `ProfileResult`). Refuses to switch mid-multiplayer.
- [x] **Accounts + storage**: `AltAccount` (MS: live MC token + MSA refresh
      token + xuid + skin; offline: name → standard offline uuid, dummy token),
      `AltManager` → `config/unlucky/alts.json` (accounts + Azure client id;
      **sensitive file** — MS tokens grant account access; git-ignored, warned
      in-UI). Default client id = Lucien's own public Azure app
      (`de9f4927-…`), overridable in the json.
- [x] **Microsoft OAuth** (`MicrosoftAuth`) — **rewritten to Pandora's flow**.
      Device-code got a hard `403 "Invalid app registration"` at
      `login_with_xbox`: Azure apps registered after ~2022 must be **approved by
      Microsoft** before they may call it, and Lucien's brand-new app wasn't
      (the consent screen still shows Xbox Live, because `XboxLive.signin` is a
      *static* app permission — which is why the browser half looked fine).
      Reading PandoraLauncher's Rust source showed the way through: it uses a
      **grandfathered client id** (`e5226706-…`) with **auth-code + PKCE + a
      loopback redirect** (`http://localhost:3160/auth`), scopes
      `XboxLive.signin XboxLive.offline_access`. We now do the same:
      PKCE(S256) + state → raw `ServerSocket` on 127.0.0.1:3160 catches the
      redirect → token exchange → Xbox Live → XSTS (XErr → friendly message) →
      `login_with_xbox` → profile. Lucien's own id (`de9f4927-…`) stays
      documented as the override once/if it's approved.
      **`&prompt=select_account` is mandatory** — without it Microsoft's SSO
      cookie silently returns the account you're already signed in as, so
      "add a second account" just re-adds the first.
      MSA refresh token saved for silent re-auth on switch (`refresh()`).
      Raw responses log to **file only** — they can carry tokens.
- [x] **UI**: title-screen alt panel mirrored to the RIGHT of the menu column
      (`TitleScreenMixin`) — `AltPreviewWidget` shows a **zombie** (zombie
      texture on the player model — humanoid layout, no separate model) when
      empty, else the first alt's skin, mouse-tracked head like the skin
      changer. `AltsScreen`: click a row to switch, add-Microsoft (shows code +
      opens browser + copies), add-offline (username EditBox), ❌ remove, the
      sensitive-file warning line.
- [x] **Boot-verified**: build green, accessor mixin applies, clean world join.
- [x] **Verified by Lucien**: title panel, offline + Microsoft add/switch.

### Phase 14.4 — singleplayer skin fix ✅ DONE (2026-07-14)
- [x] Switching to an alt then joining **singleplayer** rendered Steve, while
      **multiplayer was fine**. Not the uuid — the **properties**. A
      `GameProfile` carries a *textures* property, and `switchTo` was setting a
      bare `new GameProfile(uuid, name)` with none. MP hides it because the
      *server* looks the textures up by uuid and sends them back in player-info;
      SP builds your `ServerPlayer` straight from `Minecraft.getGameProfile()`
      (which just joins `profileFuture`) → no textures → default skin.
      `AccountSwitcher` now builds `profileFuture` the way vanilla does at
      startup: `services().sessionService().fetchProfile(uuid, true)` on
      `Util.nonCriticalIoPool()`. Offline accounts keep the bare profile (no
      textures to fetch — Steve is correct there).

## Phase 15 — Freelook, NoSlow, InventoryMove, ClickGUI-in-menu ✅ DONE (2026-07-14)

- [x] **Freelook** (`Freelook`, r0yzer/perspektive recreation): 360° camera
      orbit while the body keeps facing (and walking) where it was. Hold **or**
      toggle mode, smoothing (eased rotation, frame-rate independent),
      sensitivity, restore-view. Recipe from their source: force third person,
      swallow the mouse deltas into our own yaw/pitch, and override the camera
      rotation **at the `getMaxZoom` INVOKE** in `alignWithEntity` — see the
      ARCHITECTURE mixin table for why that exact spot.
- [x] **NoSlow** (`NoSlow`, `PlayerMixin` + `LocalPlayerMixin`): items (full
      speed while eating/blocking/drawing — the `itemUseSpeedMultiplier` scale
      in `modifyInput`), webs (`makeStuckInBlock`), blocks (soul sand/honey
      drag, lifting only factors < 1 so boosts survive). Webs/blocks default
      **off** — far more visible to anticheat than the item one.
- [x] **InventoryMove** (`InventoryMove`, `KeyboardInputMixin` +
      `KeyMappingAccessor`): walk in any screen. Vanilla releases every
      `KeyMapping` on screen open, so one `@Redirect` on `isDown()` polls the
      hardware instead and covers all seven movement keys. **Typing always
      wins** (chat, console, focused `EditBox`, the ClickGUI search tab / open
      pickers). Arrow-key look while a screen holds the mouse. **Portals**:
      keeps screens open inside a nether portal (see the `LocalPlayerMixin` row).
- [x] **Zoom mouse wheel**: wheel steps the zoom factor while the zoom key is
      held, and swallows the scroll so the hotbar stays put.
- [x] **ClickGUI + full toolbar in the main menu**: "ClickGUI" button beside
      "Alts" on the title screen. The HUD editor **crashed** there — it renders
      the real widgets and 11 of 19 read `mc.player`. `HudWidget.requiresPlayer()`
      now gates those into a draggable name **placeholder** with no world, so the
      whole HUD can be laid out from the menu; the 8 world-free widgets draw for
      real. The toolbar also carries a **parent screen** across every view now —
      without it, Close from the menu dropped you on a blank screen.
- [x] NameTags enchant limit rebounded 5–45 (was 1–10).
- [x] **World-join verified** (`--quickPlaySingleplayer`), no
      `InvalidInjectionException`, no missing resources.

## Suggested release cadence

- **v1.2** after Phase 2 (8 quick modules — a fat changelog on its own)
- **v1.3** after Phase 4 (movement trio + eat/fish + item picker)
- **v1.4** after Phase 6 (Search + Nuker — the anarchy workhorse release)
- **v1.5** after Phase 8 (NameTags + InventoryInfo — the pretty release)
- Baritone lands whenever upstream makes it possible.

## Backlog (deferred by choice — do not start unprompted)

- [x] ~~NoSlow~~ — shipped in Phase 15.
- [ ] StorageESP Phase 4 time-slicing (only if perf ever demands it).
- [ ] Configs manager (ClickGUI toolbar button is a placeholder). Friends core
      landed 2026-07-12 (Phase 11.1) — still open on top of it: toolbar Friends
      panel (add offline players by name → Mojang API), NameTags/ESP friend
      colors, chat dot.
- [ ] Per-block colors for Search/XRay.
- [ ] NoRender: portal/nausea overlay toggle — needs the 26.2 post-effect path
      located (the spin constants are inlined, so there's no call site to hook).

## Fixes
- [x] **Vanilla bottom HUD clears the chat input bar** (2026-07-11, requested by Lucien). The whole
      cluster — hotbar, health, food, armor, air, XP/contextual bar, held-item name — is drawn by
      `Hud.extractHotbarAndDecorations`, so `HudMixin` wraps it and eases the lot up ~16px while chat is
      open (sustained eased shift, mirroring the chat slide feel), then back on close. Creative and
      survival both, since it's one umbrella method. See ARCHITECTURE.md §6.
- [x] **Nuker broke only client-side / respawned on relog** (2026-07-11, found by Lucien). The
      timer-based `continueDestroyBlock`/`destroyBlock` path only drives client prediction — the server
      kept the block. Rewrote to **packet mine** (MeteorClient's approach): a `START_DESTROY_BLOCK` +
      `STOP_DESTROY_BLOCK` action pair per block each tick, sent through vanilla's prediction (private
      `startPrediction`, reached via a new `MultiPlayerGameModeAccessor` `@Invoker`) so the sequence is
      valid and the **server** removes the block. Plus an always-on silent server-side rotation toward
      each block (`RotationManager.lookAt`, camera-free like Aura — the old *Rotate* toggle is gone,
      since a break you aren't facing is rejected). Verified in creative on the strict integrated server
      (targets cleared to 0); lenient servers accept hard blocks too. See ARCHITECTURE.md §6.
- [x] **ClickGUI search took no input** (2026-07-11, found by Lucien — regression from the keybind
      fix). `recentlyBound()` compared `nanoTime() - Long.MIN_VALUE`, which **overflows** to a tiny
      value that always read as "recent", so `charTyped` swallowed every char. Guarded the sentinel
      (`lastBindNanos != Long.MIN_VALUE`). Verified `recentlyBound()` is false at startup.
- [x] **Top toolbar shared with the HUD editor** (2026-07-11, requested by Lucien). Extracted the
      floating top bar into `ClickGuiToolbar`; the HUD editor now shows it too (its own icon
      highlighted), so you can jump back to the ClickGUI or close from either screen. Both screens
      call the same `draw`/`buttonAt`/`activate`, skipping the currently-active button.
- [x] **Search box typed the letter while binding a key** (2026-07-11, found by Lucien). `keyPressed`
      fires before `charTyped`, so a bind cleared its `listening` flag before the trailing char, which
      then leaked into the module search field. Fixed with a shared `BindComponent.recentlyBound()`
      ~60ms window (set by both the setting-level and module-level bind); `ClickGuiScreen.charTyped`
      swallows the char during it. See ARCHITECTURE.md §6.
- [x] **Chat open animations, two elements** (2026-07-10 → 11, requested/clarified by Lucien).
      *Message log* (`ChatSlideMixin`) slides in from the **left** and doesn't touch the HUD.
      *Input bar* (`ChatInputSlideMixin`) rises up from the **bottom**, and HUD widgets over it
      glide **up** (eased cascade, mirror of the potion band) to clear the ~12px bar. Both share
      `ChatAnim`'s one-shot timing; only while chat is focused. The input mixin brackets its pose
      translate around the middle FOREGROUND-log call so the messages keep only their left slide.
      See ARCHITECTURE.md §6.
- [x] **HUD overreach: chat-avoidance flung Coords/ArmorHUD to mid-screen** (2026-07-11, found by
      Lucien). Bottom-anchored widgets all share `wBottom = guiHeight − MARGIN`, so a tall
      right-side widget (ArrayList) tied on `wBottom` with short left/centre widgets it doesn't
      overlap horizontally; the stacking chain read the negative gap as "adjacent" and dragged each
      up to the tall one's new top (−210…−253px). Fixed by gating the chain on `gap ≥ 0` (a real
      vertical stack) in both `avoidChat` and `avoidPotions`; each widget now clears the bar by
      ~12px. Verified in-world via headless diagnostic.
- [x] **AutoEat "Ignore gapples"** (2026-07-10, requested by Lucien). New toggle (on by default)
      that skips golden and enchanted golden apples in food selection, so best-saturation stops
      wasting combat gapples. Applies in both prefer modes.
- [x] **HUD widgets slide clear of the potion icons** (2026-07-10, requested by Lucien).
      While status effects are active, any HUD widget whose column overlaps the
      vanilla top-right icons glides downward, then eases back when the effects end.
      Widgets stacked together (gap ≤ 8px) move as one group so a pushed widget never
      lands on the one below it. `HudManager.applyPotionAvoidance` mirrors
      `Hud.extractEffects` geometry (icons 25px apart from the right edge, a second
      26px-lower row for harmful effects); `HudWidget` eases each widget's offset
      frame-rate-independently. See ARCHITECTURE.md §6.
- [x] **TargetStrafe/Aura target Mannequins** (2026-07-10, requested by Lucien).
      The new Mannequin is a sibling `Avatar`, not a `Player`, so it fell through the
      Enemy/passive buckets and needed *Passives* on to be picked. `CombatUtil` now
      treats a `Mannequin` as a player — PvP-practice targeting grabs it under the
      *Players* toggle.
- [x] **Aura didn't aim at the head in third person** (2026-07-10, found by Lucien).
      Silent rotations set `yHeadRot`/`yBodyRot` (yaw has spare fields separate from
      the camera) but pitch has no such field — render-state `xRot` *is* the camera
      pitch. So the model aimed at body height; Head/Feet only moved the invisible
      server pitch. Fixed by overriding `state.xRot` for the local avatar in
      `AvatarRenderer.extractRenderState` while spoofing. See ARCHITECTURE.md §6.
- [x] **Pumpkin overlay now in NoRender** (its own toggle). It's the head-equippable
      camera overlay (`Hud.extractTextureOverlay`), distinct from the in-block
      "Block overlay" (`ScreenEffectRenderer.submitBlockSprite`).
- [x] **TargetStrafe gained On-hold** — orbit only while a bound key (default Left
      Alt) is down; while it's up the circle still shows the would-be target but you
      don't move. Off by default (keeps the hold-W behaviour).
- [x] **Jesus Solid: sank, then bobbed like Dolphin** (2026-07-10, found by Lucien
      across two rounds). Walking on fluid needs **three** vanilla conditions, and
      I had one: (a) `canStandOnFluid` — had it; (b) a non-empty
      `getLiquidCollisionShape()` — **missing**, the base class returns
      `Shapes.empty()`, so there was literally nothing to collide with (the strider
      overrides it; we now return a box up to the 8/9 water surface); (c)
      `isAbove` — feet above that shape's top face, which a submerged player never
      satisfies, so a lift is still needed because saying yes to (a) also removes
      swim physics. Dolphin separately floated chest-deep because it targeted
      `isUnderWater()` (eye-relative); both modes now measure `getFluidHeight`
      (metres of fluid above the **feet**). See ARCHITECTURE.md §6.
- [x] **HUD editor ran at 30 fps** (2026-07-12, reported by Lucien). The dot grid
      drew one 1px `g.fill` per dot — ~1.6k render states per frame even at dev
      window size — and the 26.2 GUI renderer's cost grows superlinearly with
      state count (extract measured only ~0.95 ms; the other ~30 ms burned in the
      renderer consuming the states). Replaced with a single tiled GUI sprite
      (`hud_grid` + mcmeta `"tile"` scaling): **30 → ~255 fps**, measured via the
      Tier-0 harness with a temp auto-open diagnostic. New §6 trap in
      ARCHITECTURE.md: never draw repeating patterns with per-element fills.
- [x] **Disabled PlayerESP/NameTags still ran their overlays** (2026-07-12, found in
      the Phase 10 audit). `UnluckyClient.renderHud` calls both every frame and
      neither `renderOverlay` checked `isEnabled()` — same class as the Jesus
      `standsOn()` bug, invisible in singleplayer because `targets()` skips the
      local player. Gated inside each `renderOverlay`. See ARCHITECTURE.md §6.
- [x] **ClickGUI opens on the Search tab** on first open each launch; the sidebar
      still remembers your last pick afterwards.
- [x] **Jesus broke swimming while disabled** (2026-07-10, found by Lucien).
      `standsOn()` checked the mode but never `isEnabled()`, and the mode defaults
      to Solid — so `canStandOnFluid` was always true, `shouldTravelInFluid` always
      false, and you sank with jump doing nothing. Guarded in both the mixin and the
      module. Swept every other mixin for the same class of bug: none found
      (`Zoom.fovDivisor()` and the Chams path guard internally). See
      ARCHITECTURE.md §6, "Mixins run whether or not the module is on".

- [x] **AutoXPRepair hands rework** (2026-07-13, Lucien's spec): bottles now
      go to the OFFHAND (thrown from there, same server-side look-down) so
      the main hand holds the repair target. Damaged mending items from the
      main inventory get parked in hotbar slot 0 while they mend; hotbar
      items are just selected in place; worn armor mends passively and is
      never touched. State machine (one inventory action per tick, pauses
      while another container is open): park -> unpark when full -> next
      target -> restore EVERYTHING at the end (parked item back, bottles /
      original offhand back via the same SWAP clicks, previous hotbar
      selection back) — also on module disable and when bottles run out.
      New InteractUtil helpers: swapWithOffhand/swapWithHotbar (generic
      SWAP clicks) + useOffhandItem.
- [x] **Ender chest preview never worked** (2026-07-13, found by Lucien).
      It read the client's `getEnderChestInventory()` — a dummy vanilla never
      fills (real contents only pass through the open chest menu's slots).
      InventoryInfo now snapshots those slots every tick the ender chest
      screen is open (vanilla `container.enderchest` title check), tied to
      the connection so a server hop drops stale loot, with a generation
      counter that busts the tooltip hover-cache (hovering the chest item
      *before* first opening it must not pin the cached "no preview").

## Notes (carried over)

- BookTools § stripping = vanilla server-side limit, not a bug (works on anarchy).
- Build + boot test each batch: watch for "Unlucky Client initialized", no Mixin errors.
- **Always grep verify logs for `Missing resource`** too, not just
  exception/compile patterns — a bad texture path renders as silent magenta
  (the Portal chams shipped one render round with
  `textures/entity/end_portal.png`; 26.2 moved it to
  `textures/entity/end_portal/end_portal.png`). The warning only fires when
  the texture is first *used*, so it needs the forced-render diagnostic.
- **Mixins apply on class load, so a main-menu boot proves nothing** for hooks on
  `LocalPlayer`/`LivingEntity`. Auto-join a world to force them:
  `./gradlew runClient --args="--quickPlaySingleplayer \"New World\""`.
  With `defaultRequire: 1`, any unresolved target crashes at load — so "player joined
  the game" + no `InvalidInjectionException` is proof the hooks resolved.
- Don't pipe the run log through `head` — it buffers and the file stays empty until
  exit. Redirect straight to a file (`> log 2>&1`) if you want to poll it live.
- PowerShell 5.1 `utf8` writes BOM (javac rejects) → use
  `[System.IO.File]::WriteAllText(path, text, UTF8Encoding($false))`.
- Movement/interact research pattern that works: javap the deobf jars in the
  Loom cache for exact 26.2 signatures BEFORE writing mixins (§6 of
  ARCHITECTURE.md collects the traps found so far).
