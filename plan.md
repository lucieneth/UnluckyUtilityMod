# Unlucky Client — Work Plan

**Open work only.** Anything finished moves to [done.md](done.md) rather than
staying here with a tick next to it — a plan that lists mostly-done work stops
being read as a plan, and stale entries get cited as if they were still true
(v1.9 shipped with the registry described as unbuilt in two places). One item,
one file.

Ground rules (unchanged):
- Every phase ends with: build, boot smoke-test, ARCHITECTURE.md §3/§4 sync.
- No anticheat bypass — vanilla/anarchy semantics only. Movement is
  client-authoritative on vanilla servers; that's what makes most of the
  movement modules work at all.
- New settings reuse existing components; **never hand-roll pickers or text
  input** — `BlockPickerPopup`, `MobPickerPopup`, `ItemPickerPopup`,
  `BrewQueuePopup` and `ui/TextBox` all exist.
- Read [ARCHITECTURE.md](ARCHITECTURE.md) first; §6 collects the 26.2 API traps.

---

## Status

Phases 1-17 are **done** (see [done.md](done.md)); v2.0 shipped 2026-08-04 (Future
ClickGUI, HUD widget settings + duplication, chat completion for client commands).

The **NewModules batch is complete** as of 2026-08-10 — Phases 0–4 raised the registry
from 141 to **151 modules**.
The last batch closed the Phase 2 gaps (Surround, ArrowDodge, ElytraTarget, LightOverlay),
Phase 3 (XCarry; the five movement modules had already landed) and Phase 4 (StrongholdFinder,
CrystalAura, AnchorAura). `runClientGameTest` passes: every module enabled individually, then
all at once, then a Panic-minimal sweep. Deliberate omissions, each documented at its call
site: ArrowDodge has no `Packet` mode and ElytraTarget no `Silent` rotation (elytra heading
comes from the player's real look angle, so a spoof cannot steer).

**EndermanLook** and **QuickStash** landed alongside it (neither came from NewModules.md).
The Printer landed 2026-07-30 (LP1, LP2, LP5); survival restocking — LP3b
(carried shulkers) and LP4 (chest stash) — shipped in v1.9.2. Nothing below is
scheduled; these are the open threads, roughly in the order they'd be worth
picking up.

---

## Verification — remaining

The screen smoke test now covers both ClickGUI styles, every Skeet category, all picker
tabs, and every HUD-widget settings popup (see [done.md](done.md)). What it does **not**
cover is the printer trip harness below — the other half of this problem, and
      the one that costs 30-minute live runs today.

## Printer — remaining phases

LP1 (Litematica soft dependency + `LitematicaBridge`), LP2 (core Printer module)
and LP5 (`PlacementSolver` precision placement) shipped 2026-07-30 — see
[done.md](done.md). What's left, in the order it's worth doing:

### LP3 / LP3b / LP4 — materials and restock ✅ **shipped v1.9.2**
Live material list, HUD widgets, carried shulkers and the chest stash all
shipped — see [done.md](done.md). One item never done:
- [ ] **Out-of-materials ping** via `PingSound` when the printer stalls for
      want of an item, so an AFK print doesn't silently idle. (Partly covered:
      it now says "no source for X — skipping it" in chat once per band, and
      moves on rather than stalling. A sound is still worth having.)

### LP3c — survival restock, remaining
The planner is in and correct in principle (ARCHITECTURE §4.1). What is left is
all *verification*, because almost none of it has been proven in a long run:
- [ ] **A full band, start to finish, one trip per pass.** The arithmetic says a
      two-layer mapart band is ~16 slots and should need exactly one run per
      group. Confirm against the report's `band left (exact, counted now)` vs
      what the trip actually fetched.
- [ ] **Material spent without landing.** `click lost` lines appear in every
      report and their true rate has never been established. With the counts now
      exact this is measurable: fetch N, place fewer than N, and the difference
      is the leak. Suspect the same cause as the wrong-block case in LP5.
- [ ] **A trip harness.** Every bug in the v1.9.2 restock cycle was a *seam*
      bug — two correct components disagreeing about a shared word (`wanted`,
      `gained`, `laneIndex`). None were visible in one file, and all of them cost
      a live 30-minute run to find. A fake chest + fake world that runs a whole
      trip (deposit → withdraw → unload → return) would catch that class in a
      second. **Do this before the next behaviour change here.**

### LP5 — break and replace wrong blocks
LP5's precision placement **shipped 2026-07-30** (`PlacementSolver`) — orientation
and stacking are handled. What's left is the case a click cannot fix:
- [ ] **Wrong-block / wrong-orientation repair**: break the offender, then let
      the normal loop re-place it. Litematica's `SchematicVerifier` already
      computes the mismatch lists (Seija mixes into it) — read those rather
      than re-deriving. Needs a guard so it never breaks blocks outside the
      placement, and an opt-in setting: on a shared server this is destructive.
      Until then such positions land in the Printer's `unsolvable` map and are
      skipped, which is why a bad print currently needs manual cleanup.
- [ ] Post-place fixers for state a placement click can't set at all: repeater
      and comparator delays, lever/trapdoor open state, campfire lit state.

### LP6 — polish
- [ ] Item frames / paintings for wall art.
- [ ] Sneak-place reliability: confirm on a real server whether forcing
      `keyPresses` + `setShiftKeyDown` around the click actually lands before
      the interaction check, or whether it needs a held sneak across ticks.
- [ ] Solver cost: worst case is 6 faces × 3 points × 13 facings of
      `getStateForPlacement` per position. Exact matches exit early so ordinary
      blocks cost one call, and failures are cached for 3s in `unsolvable`, but
      if a big print ever shows tick spikes, profile this first (`PerfDebug`).

---

## Baritone integration (research-gated stretch) — was Phase 9

Never started; blocked on a fact nobody has checked yet.
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

## FPS pass — remaining tiers — was Phase 10

Tiers 0-3 shipped (harness, gating, frame caches, tooltip caches) — see
[done.md](done.md) for what was measured and why. Tiers 4-5 are what's left.

### Tier 4 — tick-thread render work (main-thread frame time)
- Search now uses StorageESP's camera-stamped `BoxGeom` cache; it rebuilds only
      after a rescan, a meaningful camera move, or an occlusion-mode change.
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

## Registry — cross-server presence — was Phase 11.3

Phases 11.1 (friends core) and 11.2 (the registry itself) shipped; the registry
runs on Cloudflare + KV at `api.unlucky.life`. Read the `server/src/index.js`
header before touching this — it explains why there's no Mojang handshake
(their WAF 403s it from Cloudflare IPs) and what the tamper-proof upgrade is.

- [ ] Heartbeat (UUID + hashed server address) + friend polling, or a Durable
      Object WebSocket for instant "friend online" toasts. Privacy: opt-in only.

---

## 3DSkinLayers — remaining — was Phase 13

Mesh foundation, render integration and the on-screen look are all confirmed —
Lucien verified the voxel layers render correctly on 2026-08-04 (see
[done.md](done.md)), so the default-off hold is now a choice, not a caveat.
- [ ] **First-person hands** — separate renderer (their FIRSTPERSON offset
      providers); deferred to 13.3.

---

## InventoryInfo — remaining — was Phase 8

The tooltip suite shipped (container/banner/book previews, map preview, shulker
colors). One stretch item never done:

- [ ] *Fullness bar* on containers (stretch)

---

## Backlog (deferred by choice — do not start unprompted)

- [ ] StorageESP Phase 4 time-slicing (only if perf ever demands it).
- [ ] Per-block colors for Search/XRay.

---

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
