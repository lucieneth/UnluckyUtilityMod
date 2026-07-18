# Unlucky Client — Done

Archive of completed work. **Nothing open lives here** — if it still needs doing
it's in [plan.md](plan.md), and it is in exactly one of the two files. Kept because
these entries hold the hard-won 26.2 findings (why a hook is where it is, which
approach was tried and reverted, what the API actually does) that ARCHITECTURE.md
states as fact but doesn't justify.

Phases ran 1 -> 17; v1.9 closed Phase 17 on 2026-07-17.

Lineage: the v1 plan shipped whole in **v1.1**. What follows was "Work Plan v2 (the
giga plan)" — scoped at the time as *the next 18 modules*, phased by shared
infrastructure and risk (early phases quick wins, later ones flagships needing new
foundations). It ended up running to 90 modules across 17 phases.

## Post-v1.9 — Configs manager + friend mark polish ✅ DONE (2026-07-17)

- [x] **Per-module `Hidden`** shipped with v1.9 (see Phase 17 tail).
- [x] **Configs manager** (`gui/configs/ConfigsScreen` + `ConfigManager` profile API):
      the toolbar's "Configs (soon)" placeholder became real. Named profiles are plain
      JSON in `config/unlucky/configs/` — a profile is just a config that isn't loaded
      right now, so sharing one is sending a file. `ConfigManager` was refactored into
      `toJson()`/`apply(JsonObject)` halves (save/load now call those), plus
      `saveProfile` (filename-sanitised), `loadProfile` (applies **and** makes it the
      active config — a load that didn't survive restart would read as failed),
      `listProfiles` (newest first). Import/Export are native tinyfd file dialogs, same
      pattern as the skin picker (off-thread — tinyfd blocks); Open folder via
      `Util.getPlatform().openPath`. Verified in-client: save → list → mutate → load
      restores, hostile name `bad/../name!!` sanitises to `badname`.
- [x] **ClickGUI default page** (user suggestion via Lucien): Theme → "GUI opens on"
      (Search / any category, default Search = old behavior). Applied on the **first
      open per launch only** — mid-session the GUI keeps remembering the last page, so
      toolbar bounces (HUD editor ↔ ClickGUI, each of which constructs a new screen)
      don't keep yanking you back to the configured page.
- [x] **Tablist friend mark spacing** (Lucien's screenshot): the mark sat flush against
      the vanilla skin face and read as part of it. `getNameForDisplay` is measured and
      drawn from the same string, so one leading space in the prefix is the whole fix.
- [x] **Mark styles** (Lucien's ask, clarified twice): `UnluckyUsers` → Style is a
      13-option dropdown — **★** (default) **∞ † ♥ ♣ ♠ ❤ ☘ ⚡ ◆ ‡ ☠ ᴜʟ** — and
      `Friends` → Style **Dot / ꜰ**; each a `markerText()` on its module threaded
      through every text site (tablist mixin — both marks live there — NameTags,
      GamemodeNotifier chat line, FriendsScreen rows). `ModeComponent` already renders
      4+ options as a slide-down dropdown, so the menu cost nothing. Checked against
      the 26.2 jar's font json: every glyph in the list is on the crisp
      `nonlatin_european` page **except ☘** (unifont fallback, kept — it's the actual
      clover), and the old default **✦ was itself unifont-only** — which is why it
      always looked chunkier than the text around it; ★ is its crisp twin. Marks are
      local rendering (each user sees their own pick); only the colour is registry-shared.

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

---

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

---

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

---

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

---

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

---

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

---

## Phase 7 — NameTags (the flagship render module) ✅ DONE (2026-07-11)

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

---

## Phase 8 — InventoryInfo (the tooltip suite) ✅ DONE (2026-07-12)

> One stretch item (container fullness bar) never done — see [plan.md](plan.md).

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

---

## Phase 10 — FPS optimization pass — Tiers 0-3 ✅ DONE (2026-07-12)

> Tiers 4 (tick-thread render work) and 5 (beat-baseline features) are still
> open — see [plan.md](plan.md).

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

---

## Phase 11 — Friends & networking — 11.1/11.2 ✅ DONE (2026-07-15)

> 11.3 (opt-in cross-server presence) is still open — see [plan.md](plan.md).

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

### Phase 2 — the registry ✅ DONE (2026-07-15, shipped in Phase 16 — see there)
Shipped, but not to this sketch. Two deviations worth remembering:
- **Auth was dropped, deliberately.** The planned Mojang joinServer/hasJoined
  handshake *cannot run on Cloudflare* — Mojang's WAF 403s it from datacenter IPs
  (proven with `wrangler tail`). Registry is trust-UUID instead; the reasoning and
  the tamper-proof upgrade path (profile-key signing, no egress) are written up in
  the `server/src/index.js` header. Read that before reopening this.
- **`PUT /v1/profile`, not `PUT /v1/cape`** — one endpoint carrying cape *and*
  marker colour. Routes: `PUT|POST /v1/profile`, `GET /v1/users`, `GET /v1/capes`.

---

## Phase 12 — Heads & identity visuals — 12.1-12.5 ✅ DONE (2026-07-13)

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

---

## Phase 13 — 3DSkinLayers (tr7zw/3d-skin-layers recreation) — 13.1/13.2 ✅ CODE DONE (2026-07-14)

> Ships **default off**: the on-screen visual check and first-person hands are
> still open — see [plan.md](plan.md).

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

---

## Phase 14 — Alt account switcher (PandoraLauncher-referenced) ✅ DONE (2026-07-14)

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

---

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

---

## Phase 16 — Meteor-inspired visuals + the registry (v1.8) ✅ DONE (2026-07-15)

- [x] **Waypoints** (`Waypoints` + `util/waypoints/`): saved beacons in
      `config/unlucky/waypoints.json`, beam + name/distance label, fade-on-approach,
      near-actions (keep/hide/delete), death points latched on `isDeadOrDying()`, and
      the **8:1 overworld↔nether projection** so a nether waypoint shows where it maps.
      Compass-bar pins. Console `waypoint add|remove|list` (alias `wp`).
- [x] **LogoutSpots** (`LogoutSpots`): detects logouts by a **tab-list UUID
      disappearing** (not entity unload), ghost box + head + "Nm ago" + health color,
      friend color kept, expires (default 10m), clears on dimension change.
- [x] **ItemPhysics** (`ItemPhysics` + `ItemEntityRendererMixin` +
      `ItemEntityRenderStateMixin` + `util/ItemPhysicsData`): dropped items lie flat and
      tumble. Two `@Redirect`s on the bob `translate` and the Y-spin `mulPose` in
      `ItemEntityRenderer.submit` — leaves the whole model/bundle/stack pipeline alone.
- [x] **PopChams** (`PopChams`): fades a tint over a player for ~900ms after a totem
      pop (fed from `LivingEntityMixin` event id 35), rendered through the **proven Chams
      re-submit path** in `LivingEntityRendererMixin`, not a new RenderLayer.
- [x] **ItemFrames** (`ItemFrames` + `EntityRenderDispatcherMixin`): distance-culls
      item frames (map frames get a tighter cap) at `shouldRender` — the earliest bail,
      so no render state is even extracted. Big FPS win in frame-papered storage rooms;
      the frame cost is vanilla (item frames are per-frame entities, not baked mesh).
- [x] **The registry** (`UnluckyUsers` + `util/net/` + `server/`): a public cosmetic
      directory — who runs Unlucky and their cape/marker colour. Cloudflare Worker + KV
      (`server/`, deploy via `server/DEPLOY.md`), `api.unlucky.life`. **Trust-UUID**:
      the client publishes its own uuid; there's no Mojang handshake because Mojang's
      WAF 403s that call from Cloudflare's IPs (proven via `wrangler tail`). Cosmetic
      stakes make the trade fine; tamper-proof upgrade (profile-key signing, no egress)
      documented in the Worker header. ✦ marker in tab + nametags in the user's own
      colour; capes resolve from mojang/GitHub, registry hosts no textures.
- [x] **Alt session rebuild** (`AccountSwitcher.rebuildSession`): switching now rebuilds
      `userApiService` / `userPropertiesFuture` / `profileKeyPairManager`, not just
      `user`/`profileFuture` — fixes Realms & registry reading a switched session as
      "invalid" (they verify against Mojang; the stale services answered for the launch
      account). Plus a **⟳ refresh** button per Microsoft account.
- [x] **First-boot defaults**: a fresh install starts with only **UnluckyUsers** on and
      the Watermark HUD; Zoom/BookTools/Friends no longer self-enable.

---

## Phase 17 — Combat & comms batch (v1.9) ✅ DONE (2026-07-17)

Build order: **ChatTag → GamemodeNotifier → Criticals → Dodge → DiscordRPC →
AutoBrew** — two warm-ups, the two combat modules back-to-back (shared packet
research), RPC standalone, AutoBrew as the anchor. All six shipped, plus
HealthIndicators (floating damage/heal numbers) added mid-batch on request. The
"cut v1.9 before AutoBrew" escape hatch went unused.

**26.2 findings from this batch** (all decompiled from the named jar, not guessed):
- `Player.canCriticalAttack(Entity)` is where the whole crit condition now lives:
  `fallDistance > 0 && !onGround() && !onClimbable() && !isInWater() &&
  !isMobilityRestricted() && !isPassenger() && target instanceof LivingEntity &&
  !isSprinting()`, gated on `getAttackStrengthScale(0.5f) > 0.9f`. It's private,
  but `Player.isMobilityRestricted()` is public and is just the blindness check.
  **`!isSprinting()` is inside the crit condition** — sprinting cancels crits, which
  is why Criticals must w-tap and why AutoSprint had to learn to back off.
- `ClientPacketListener.handlePlayerInfoUpdate` creates `PlayerInfo` for joining
  players in a **separate `newEntries()` loop** and only then applies actions via
  `applyPlayerInfoUpdate` — so at HEAD the tab list still holds the old gamemode,
  and a joining player has no entry at all (which is the join-spam guard for free).
- `GameProfile` is a **record** now: `.name()` / `.id()`, not `getName()`.
- `ResourceKey<Level>` uses `.identifier()`, not `.location()`.
- `SoundEvents` mixes plain `SoundEvent` and `Holder<SoundEvent>` fields;
  `SimpleSoundInstance.forUI` overloads both, so either kind resolves.
- `Entity.fallDistance` is a **double** now (was float).
- `LivingEntity.absorptionAmount` is a **plain private field, not synched entity
  data** — the client only knows its *own* (simulated from effect packets, which is
  how the yellow hearts render). HealthIndicators diffs `health + absorption` so
  absorption hits register on yourself; other players' read 0 and the sum collapses
  back to health. Not fixable client-side: the server never sends it.
- `PotionBrewing.mix(reagent, input)` and `hasMix(input, reagent)` take their args in
  **opposite orders**. Both public, as is `Level.potionBrewing()` — enough to solve
  brewing without touching the private mix lists (see `BrewingSolver`).
- `ClientboundOpenScreen` carries **no BlockPos**. Tying a menu to a block has to
  happen at the `useItemOn` click; `mc.hitResult` at menu-arrival is a guess that
  breaks when the player turns during the round trip.
- `MultiPlayerGameMode.useItemOn` takes **`LocalPlayer`**, while `attack` right above
  it takes `Player`. Mixing them up compiles and fails at mixin-apply time.
- `LocalPlayer.closeContainer()` is public (`protected` on `Player`) and also clears
  the screen. `Slot.container` is a public final field — testing
  `instanceof Inventory` beats hardcoding player-slot indices.
- `BrewingStandMenu.quickMoveStack` offers the **fuel slot first**, and blaze powder is
  both fuel and reagent — shift-clicking it can never load the ingredient slot.

- [x] **ChatTag** (`ChatTag`, Misc): highlights your name in chat + optional ping.
      Rebuild runs at the addMessage HEAD `@ModifyVariable` (chained inside AntiToS's
      handler — censor first, then highlight — because mixin won't order two
      handlers into one method); flattens via `Component.visit`, which resolves each
      leaf's style, so click/hover/font survive and only matched runs are recolored.
      The **ping fires at the display-queue call instead**, which only runs for
      messages that survive AdBlocker and the visibility filter — so a blocked ad
      that @'s you stays silent. Costs one extra regex per shown message; cheaper
      than sharing state across two injections. `Heads.currentSender()` (new,
      non-consuming peek) identifies your own messages. Word-boundary lookarounds,
      longest-name-first alternation, pattern cached on account+setting.
- [x] **GamemodeNotifier** (`GamemodeNotifier`, Misc): chat line + ping on a
      gamemode switch, from a `handlePlayerInfoUpdate` HEAD inject. `isSameThread()`
      guard (HEAD runs on netty first, before `ensureRunningOnSameThread`
      reschedules). Null tab-list entry = joining, skipped. Filter (All /
      Creative+Spectator / Friends), Self toggle (default off), friend dot.
- [x] **Criticals** (`Criticals`, Combat): **Jump** (default) swallows the attack,
      hops, and replays it once `fallDistance > 0` — real state, nothing faked.
      **Packet** sends Meteor's `y+0.0625` then `y+0`, both flagged airborne, so the
      *server* banks the fall while we never leave the ground. Both w-tap first
      (`STOP_SPRINTING` packet + client flag, since LocalPlayer wouldn't sync it
      until next tick — too late for the attack). Jump swallows Aura's interim hits
      so they don't spend the swing mid-rise, and **AutoSprint now checks
      `Criticals.suppressesSprint()`** so it stops re-asserting sprint under a
      pending crit. Merged into the existing `MultiPlayerGameModeMixin` attack
      handler so a swallowed hit isn't session-counted twice. Reference: Meteor.
- [x] **Dodge** (`Dodge`, Combat): melee **combo-breaker**, and the docs say so —
      confirmed against the packet API that no pre-hit signal exists
      (`ClientboundDamageEventPacket` is the server reporting a hit it already
      applied; a swing packet goes out as the hit lands, not before). Triggers on
      `handleDamageEvent` (`sourceCauseId` → the attacker) and/or `handleAnimate`
      (SWING_MAIN_HAND from a player in reach, within ~45° of facing us). Steps
      perpendicular to the attacker via `setDeltaMovement` (TargetStrafe's proven
      path), only toward a side whose path is clear **and** still has floor at the
      far end — lava/water fall out for free (neither collides, so both read as a
      ledge). Both sides blocked = no dodge. Re-checks safety every tick.
- [x] **DiscordRPC** (`DiscordRPC` + `util/discord/`): hand-rolled IPC, zero new
      deps. `DiscordIpc` = transport (Windows named pipe via RandomAccessFile, unix
      domain socket elsewhere, 4-byte LE opcode + 4-byte LE length + UTF-8 JSON,
      probes `discord-ipc-0..9`). `DiscordRpcThread` = a daemon thread that owns the
      socket so the render thread never touches IO; retries every 30s forever and
      stays quiet, since "Discord isn't open" is normal, not an error. Presence is a
      record so "did anything change" is just equals. Server address behind a
      privacy toggle, **default off**. **BLOCKED: needs the Discord application id**
      — `CLIENT_ID` in `DiscordRPC.java` is a placeholder, and the art asset must be
      uploaded as `logo`.
- [x] **AutoBrew** (World) — **built chest-fed directly; the "v1 stand keeper /
      v2 chests" split was dropped** at Lucien's call ("assigning containers with
      bottles… and container with ingredients if all within reach"). Pick a potion +
      count, then **open** your bottle chest, reagent chest and the stand: roles are
      read from what's *inside* each container, not from the block type, so one chest
      holding both gets both jobs and there's nothing to bind. Positions are
      per-session and per-world (a saved coordinate pointing into another world is
      worse than asking again). Empty glass bottles get filled from any water source
      or cauldron in reach. Nothing pathfinds — everything must be within your own
      reach, and it says so when it isn't.
      - **`BrewingSolver`** derives chains by BFS from a water bottle, calling the
        public `PotionBrewing.mix(reagent, input)` — *the stand's own method* — as an
        oracle rather than reading the private mix lists and restating the rules. No
        accessor mixin, no hardcoded recipes, datapack/mod mixes free. Verified
        in-game: 135 reachable bottles, `Splash Strong Strength <= gunpowder,
        nether_wart, blaze_powder, glowstone_dust`.
      - The **one-container-at-a-time** rule is the whole shape of the module: it
        works the stand until something's missing, closes, opens the chest that has
        it, and comes back — the stand keeps brewing while it's away. Decisions about
        the stand are therefore taken *while the stand is open*.
      - `produced` counts bottles **pulled back out**, never predicted; bottles in the
        stand must all agree on stage (one reagent transforms all three at once).
      - **Turns to face** what it's about to touch (`RotationManager.face`, new:
        `rotate`/`lookAt` snap, `face` walks there over N ticks and reports when
        aimed). The old snap was invisible — one tick is ~3 frames — and no hand
        produces an instant 180°. Pitch was already F5-visible via
        `AvatarRendererMixin`'s `state.xRot` override; the gap was duration, not axis.
      - **`Screens` mode**: Silent (no windows; `GuiMixin` cancels `Gui.setScreen`
        for our own opens — legal because `fromPacket` assigns `containerMenu`
        *before* `setScreen`) or Visible (watch it click through them).
      - **Queue** (`BrewQueueSetting` + popup, replacing the old single
        Potion/Type/Count trio): an ordered list — "1 Strength, then 10 Night Vision,
        then 5 Invis" — worked front to back. A List, not a Set: order and
        duplicates-as-counts both matter. Popup rows are the **real potion stacks**
        (vanilla tints them), left-click +1 / right-click −1. Verified in-game:
        config round-trips in order, `key(fromKey(k)) == k`, and a deliberately bogus
        entry resolves to null so it's reported and skipped, not stalled on.
      - **Multi-stand** (2026-07-17): show it as many stands as you like, worked
        round-robin. `getBrewingTicks()` is the *remaining* time, so a busy stand is
        parked for exactly that long and the next gets loaded — 3 stands = 3 batches
        in flight. Each stand's bottle count is re-read from the stand every visit and
        a stand may only take the order's shortfall **minus what the others are already
        brewing**, so an order of 7 across 3 stands can't overshoot to 9.
      - **Takes only what it needs** (2026-07-17): QUICK_MOVE can only move a *whole
        stack*, so 64 glass bottles all came over for an order of 7. `takeExactly`
        synthesises a "move n" out of PICKUP + n right-clicks + put-the-rest-back, in
        one tick. Reagents/fuel are placed one at a time (a powder is 20 brews).
      - **Verified with an in-world rig** (chest of 64-stacks + 2 stands + water,
        scripted then traced): max ever held = 3 glass / 1 wart / 1 powder / 3 water;
        ended `produced=3` with 3 more in flight on the second stand for an order of 7.
      - Earlier silent-failure fixes: it now *says* what it needs (no stand / empty
        queue) instead of returning quietly, and `Item.getName(ItemStack.EMPTY)` was
        returning "" so every "out of X" printed blank.
      - **Multi-chest + Discover** (2026-07-17): the single `bottlePos`/`reagentPos`
        pair is gone. Any container holding something brewable joins `chests`, and
        `pickChest` routes per fetch — preferring one remembered holding the thing,
        falling through to the rest when that memory is stale, skipping out-of-reach
        ones. `Discover` (default on) sweeps reach as you walk: stands settle from the
        **block**, containers can only be *nominated* by the block (their inventory is
        empty client-side until opened) so they go on a peek queue and get looked in
        once. Barrels/shulkers work — it tests `instanceof Container`, not block id.
      - **Verified with an in-world rig, nothing taught by hand**: 2 stands + a chest of
        glass + a *barrel* of wart/powder + water. It found both stands on sight, peeked
        both containers, routed bottles→chest and reagents→barrel, and ran both stands
        (`load=[3,3]` → `produced=3`). Max ever held: 3 glass / 1 wart / 1 powder.
      - **Menu-sync race fixed** (2026-07-17, root cause of "won't advance past the
        first step" + "never takes the potions out"): a menu arrives one packet before
        its contents, so a re-opened stand reads empty/fuel=0/brewTicks=0 —
        indistinguishable from idle. Caught on tape: `t=170 bottles=[Water x3]
        ing=nether_wart fuel=19 brewTicks=400` then `t=177 bottles=[-|-|-] fuel=0
        brewTicks=0`, seven ticks into a 400-tick brew. Gate: `getStateId() != 0` in
        `ensureOpen`. Chain visibly advanced (stage 0 -> 2) after the fix.
      - **NOT re-verified end-to-end after that fix.** The rig became unreliable — the
        test world persists between runs, so old rigs' stands were still standing and
        discovery kept finding them (fuel=18 before anything ran; leftover potions
        appearing mid-brew). Wiping the area first fixed the pollution but the run now
        stalls before reaching the stand, and I can't tell rig damage from a second real
        bug.
      - **Empty-stand deadlock fixed** (2026-07-17) — the "made them but never took
        them out / waited forever" bug, found in one look at the new widget: `want =
        min(3, remaining(stand))` goes to **0** once the other stands already cover the
        order, and then `bottles.size() >= want` is `0 >= 0` — vacuously true. So it
        skipped loading and fell through to `feedReagent` on an **empty** stand; the
        reagent sits there, the stand never brews, and every later visit sees "reagent
        already in" and waits on it forever. Guard: an empty stand after loadBottles
        declines is parked, not fed.
      - **Parallel orders / multibrew** (2026-07-17): counting core reworked. Stands
        are allocated to **work**, not to orders (Lucien's spec: 7 stands + 4 orders =
        4 stands; 1 order of 9 = 3 stands — same rule, since 9 bottles is 3 batches).
        `orderIndex`+`produced` are gone; now `standOrder` (stand -> order),
        `producedPer` (order -> pulled out), and `remaining(order, except)` which only
        counts stands **working that same order** against it. `orderFor(stand)` keeps a
        stand on its order while it still holds bottles (else a half-done batch gets
        orphaned when another order looks more urgent), else claims the first order with
        uncovered work. Widget lists every order and tags each stand with the order it
        owns.
      - **`allDone` regression fixed** (2026-07-17): it asked `remaining(order) > 0`,
        but `remaining` subtracts bottles already **loaded into stands** — so every
        order read as covered the instant the last bottle went *in*, `finish()` fired,
        and the module switched off abandoning three stands mid-brew. Now measured on
        `producedPer` (pulled back **out**). In is not done; out is done.
      - **Potion storage** (2026-07-17): `Empty potions` (default on). A container with
        a **hopper directly under it** is storage — told apart by how it's built, not by
        a setting. Never joins `chests` (an output treated as an input = fetching our own
        potions back). `storable()` only puts away finished product and keeps
        intermediates in the bag: Awkward is both a target and a rung on most ladders, so
        storing it while Healing is cooking would mean walking it to the chest and then
        brewing a fresh one. Widget lists storage separately.
      - **Multibrew + storage verified in-world** (2026-07-17, Lucien): parallel orders
        across stands and hopper-fed deposit both confirmed working at a real setup. The
        widget is what made it checkable — the scripted rigs never could see the
        fetch/fill phases.
      - **Turtle Master brews from `Items.TURTLE_HELMET`, not scute** (2026-07-17): the
        wearable helmet (display name "Turtle Shell") *is* the reagent — confirmed in
        `PotionBrewing.addVanillaMixes`; `SCUTE` appears in no mix. Reported as an
        AutoBrew bug, wasn't one: `BrewingSolver` derives reagents by calling vanilla's
        own `mix()`, so it can't disagree with the stand. **This is the oracle design
        paying for itself** — a hand-written recipe list would have said "scute" and been
        wrong. Note turtle helmets don't stack (max 1), the only non-stackable reagent in
        play, so they take `takeExactly`'s `count <= n` fast path.
      - **`BrewingWidget`** (2026-07-17, Lucien's call — stop guessing, show the state):
        HUD read-out of order + progress, current job, next order, each stand
        (idle/`12s`/load) and each chest with its remembered contents. AutoBrew grew a
        `status` line set at every decision point plus read-only getters. This replaces
        the scripted-rig approach for finding the remaining stall: run it at a real
        setup and read where it wedges.
- [x] **Per-module `Hidden`** (2026-07-17): every module gets a Hidden toggle that keeps
      it off the ArrayList while it runs. Added in `ModuleManager.register`, **not** the
      `Module` constructor — `register` runs after the subclass constructor, so the
      setting lands *after* each module's own settings instead of jumping in front of
      all ~70 of them. `ArrayListWidget` feeds `enabled && !hidden` to the existing
      slide animation, so hiding slides out like a disable rather than popping. Old
      configs just lack the key and default to false; no migration.

---

## Suggested release cadence

- **v1.2** after Phase 2 (8 quick modules — a fat changelog on its own)
- **v1.3** after Phase 4 (movement trio + eat/fish + item picker)
- **v1.4** after Phase 6 (Search + Nuker — the anarchy workhorse release)
- **v1.5** after Phase 8 (NameTags + InventoryInfo — the pretty release)
- Baritone lands whenever upstream makes it possible.
- **v1.9** after Phase 17 (combat & comms) — all six modules landed plus
  HealthIndicators, so no cut was needed.

---

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

---
