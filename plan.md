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

## Phase 3 — Movement trio (3 modules)

- [ ] **Jesus** (Movement) — walk on water.
      Options: *Mode* (Solid / Dolphin), *Sneak to sink* (on).
      Implementation: `LivingEntity.canStandOnFluid` mixin (the strider hook)
      + cancel fluid push while on the surface; only when above the surface,
      never while swimming deep. Research the 26.2 fluid-collision path first.
- [ ] **TridentFly** (Movement) — riptide boost anywhere, no rain needed.
      The server never validates the *motion*, only the vanilla riptide use —
      so we self-apply the boost: `push(lookVec * strength)` + the auto-spin
      animation on use.
      Options: *Any item* (off — trident by default), *Strength* (1–5),
      *Cooldown* (ticks), *Spin animation* (on).
- [ ] **ClickTP** (Movement) — teleport to the block you click.
      Options: *Button* (Right / Middle), *Max distance* (default 10 — big
      jumps rubber-band on vanilla's moved-too-quickly check), *Land on top*
      (on). Single position packet to the ray-traced spot.

## Phase 4 — ItemPickerPopup + auto-utilities (infra + 2 modules)

- [ ] **`ItemPickerPopup`** (shared infra) — clone of `BlockPickerPopup` for
      items, with live item icons and an optional predicate filter (e.g. food
      only). Unlocks: AutoEat blacklist, FastUse custom list, future Nuker
      tool preferences. This is the "same menu as XRay but for food" ask.
- [ ] **AutoEat** (Player) — eat when hungry, never eat garbage.
      Options: *Hunger threshold* (default 16), *Blacklist* (ItemPickerPopup,
      food-filtered; pre-seeded: rotten flesh, spider eye, poisonous potato,
      pufferfish, chorus fruit), *Prefer* (Best saturation / First in hotbar),
      *Swap back after eating* (on), *Pause interact modules while eating*
      (on — interop hook Nuker uses later).
- [ ] **AutoFish** (Player) — cast, wait, reel, repeat.
      Detection: `fishing_bobber_splash` sound packet (we already listen to
      sound packets for SoundLocator) with bobber-motion fallback.
      Options: *Recast* (on), *Recast delay*, *Reel delay min/max*
      (randomized window), *Stop without rod / switch to next rod*.

## Phase 5 — Search (1 module, reuses the ESP stack)

- [ ] **Search** (World) — find any block, show it through walls.
      Options: *Blocks* (BlockPickerPopup — exists), *Range* (chunks),
      *Max results*, *Tracers* (off), *Occlusion cull* (on — we have it),
      *Color* (+ per-block color stretch goal).
      Implementation: incremental time-sliced chunk scan on the tick thread
      (StorageESP's proven pattern — mind the §6 SectionCompiler threading
      trap), cached boxes re-emitted per frame like TreasureESP.

## Phase 6 — Nuker (the flagship interact module)

- [ ] **Nuker** (World) — Future/Meteor grade, fully featured.
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

- [ ] **NameTags** (Render) — vanilla-look tags, maximum information, fully
      customizable. Cancel the vanilla name-tag submit for players and draw
      our own billboard through the ESP 2D projection pass (the PlayerESP
      CS-boxes pipeline — world→screen already solved).
      Options:
      - *Health* (Off / Number / Hearts), *Ping*, *Distance*, *Gamemode*
      - *Held item* (with enchant lines toggle), *Armor row* (icons above)
      - *Scale*, *Constant size at distance* (on), *Background opacity*
      - *Through walls* (on), *Self in third person* (off)
      - Friend-colored names — lights up when the Friends system lands
      Research first: the 26.2 name-tag path in the extract/submit split
      (name is captured in `extractRenderState`; our `EntityRendererMixin`
      already lives there).

## Phase 8 — InventoryInfo (the tooltip suite)

- [ ] **InventoryInfo** (Misc) — make every tooltip informative.
      Feature set (each toggleable):
      - *Shulker preview* — 9×3 grid tooltip on hover (the flagship; vanilla
        bundles already do grid tooltips, reuse that ClientTooltipComponent
        pattern + `DataComponents.CONTAINER`)
      - *Ender chest preview* — last-seen cache of your own ender chest
      - *Map preview*, *Book preview* (first page), *Banner preview*
      - *Byte size* — encode the stack (registry ops) and show KB, the
        classic kit-size readout
      - *Fullness bar* on containers (stretch)
      Research spike first: 26.2 tooltip-component registration + how the
      container component exposes contents client-side.

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

---

## Suggested release cadence

- **v1.2** after Phase 2 (8 quick modules — a fat changelog on its own)
- **v1.3** after Phase 4 (movement trio + eat/fish + item picker)
- **v1.4** after Phase 6 (Search + Nuker — the anarchy workhorse release)
- **v1.5** after Phase 8 (NameTags + InventoryInfo — the pretty release)
- Baritone lands whenever upstream makes it possible.

## Backlog (deferred by choice — do not start unprompted)

- [ ] NoSlow — user deferred ("we will add it later").
- [ ] StorageESP Phase 4 time-slicing (only if perf ever demands it).
- [ ] Friends system + Configs manager (ClickGUI toolbar buttons are
      placeholders; Friends unlocks NameTags/ESP friend colors).
- [ ] Per-block colors for Search/XRay.
- [ ] NoRender: portal/nausea overlay toggle — needs the 26.2 post-effect path
      located (the spin constants are inlined, so there's no call site to hook).

## Notes (carried over)

- BookTools § stripping = vanilla server-side limit, not a bug (works on anarchy).
- Build + boot test each batch: watch for "Unlucky Client initialized", no Mixin errors.
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
