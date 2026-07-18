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

Phases 1-17 are **done** (see [done.md](done.md)); v1.9 shipped 2026-07-17.
Nothing below is scheduled — these are the open threads, roughly in the order
they'd be worth picking up.

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

## Registry — cross-server presence — was Phase 11.3

Phases 11.1 (friends core) and 11.2 (the registry itself) shipped; the registry
runs on Cloudflare + KV at `api.unlucky.life`. Read the `server/src/index.js`
header before touching this — it explains why there's no Mojang handshake
(their WAF 403s it from Cloudflare IPs) and what the tamper-proof upgrade is.

- [ ] Heartbeat (UUID + hashed server address) + friend polling, or a Durable
      Object WebSocket for instant "friend online" toasts. Privacy: opt-in only.

---

## 3DSkinLayers — remaining — was Phase 13

Mesh foundation + render integration are in and boot-verified; the module ships
**default off** pending the visual check below.

- [ ] **Visual verification pending (Lucien)**: a headless first-person
      auto-join never renders the local player model, so the actual voxel draw
      wasn't exercised on screen. Needs F5 / another player to confirm
      alignment. **Likely tweaks:** floating/offset layers → nudge the Offset
      table; z-fighting → small outward scale bump.
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
- [ ] PlayerESP friend coloring (friends render the same as everyone else there;
      NameTags/tablist/chat already mark them). Last fragment of an old backlog
      bundle — the configs manager, Friends panel and chat dot in it all shipped.
- [ ] Per-block colors for Search/XRay.
- [ ] NoRender: portal/nausea overlay toggle — needs the 26.2 post-effect path
      located (the spin constants are inlined, so there's no call site to hook).

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
