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

## Fixes
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
- [x] **ClickGUI opens on the Search tab** on first open each launch; the sidebar
      still remembers your last pick afterwards.
- [x] **Jesus broke swimming while disabled** (2026-07-10, found by Lucien).
      `standsOn()` checked the mode but never `isEnabled()`, and the mode defaults
      to Solid — so `canStandOnFluid` was always true, `shouldTravelInFluid` always
      false, and you sank with jump doing nothing. Guarded in both the mixin and the
      module. Swept every other mixin for the same class of bug: none found
      (`Zoom.fovDivisor()` and the Chams path guard internally). See
      ARCHITECTURE.md §6, "Mixins run whether or not the module is on".

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
