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

## v2.1 module batch: mace, rides, reach, projectiles ✅ DONE (2026-08-09, v2.1)

Fourteen modules and one rebuild. The findings worth keeping:

**Mace damage is a function of fall distance, so all three mace modules are about falling,
not swinging.** `LegitMaceKill` only amplifies a fall you are actually in. `BlatantMaceKill`
banks a server-side fall through `MaceKillPackets.prime`, attacks, then restores the real
position — **the client entity is never teleported**, which is the whole reason it looks
like nothing happened locally. Both share one bracket helper rather than each growing their
own packet sequence. `MaceCombo` relaunches with wind charges to chain smashes.

**Velocity was rebuilt around the observation that each force has different packet
semantics**, and treating them alike is what made the old anti-knockback erase jumps.
`ClientboundSetEntityMotionPacket` carries an *absolute* velocity, so it has to be scaled as
`current + (incoming - current) * factor`; explosion knockback and fluid flow are *additive*
vectors and are multiplied where they're produced. Entity push, suffocation push, passive
sinking and the fishing-rod pull each needed their own hook — the rod pull in particular is
a velocity source with **no motion packet behind it** (entity event 31), so nothing on the
packet path could have caught it. Full note in ARCHITECTURE §6.

**Criticals now survives thorns.** While the crit spoof claims airborne, the server can echo
a stale vertical velocity for thorns damage. The fix arms a correction only when the damage
is thorns from the exact entity the crit just hit, then rebuilds *only* the Y component from
vanilla's grounded knockback formula. Horizontal knockback, other attackers and every
unrelated motion packet are left alone — an important scope, because the first instinct
(clamp Y on any suspicious packet) breaks ordinary combat.

**`Entity.move` is hooked once and dispatched by type.** BoatFly and EntitySpeed both want
to rewrite vehicle movement, and `move` is far too hot to mixin twice — so `EntityMixin`
takes one `@ModifyVariable` at HEAD and branches on `AbstractBoat` / `LivingEntity`. HEAD
matters: the rewrite lands *before* vanilla resolves collisions, so the ride still collides
honestly instead of clipping.

**EntityControl is two RETURN overrides, not a ride reimplementation.** Vanilla gates
steering on `Mob.getControllingPassenger` recognising the passenger and jumping on
`isSaddled`. Answering those two questions is the entire module; replacing the ride logic
would have been much larger and much easier to desync.

**InfiniteInteract brackets the vanilla call, not the tick.** HEAD+RETURN pairs around
`useItem`, `useItemOn`, `interact`, `startDestroyBlock` and `continueDestroyBlock` in
`MultiPlayerGameModeMixin`, so the packet-step is open for exactly the call that needs the
reach and shut before anything else observes the position.

**`ProjectilePathUtil` is shared by Trajectories and PearlChecker** — one
allocation-conscious simulation returning `Path(points, hit)`. They are the same question
asked about a held item and about a pearl already in flight, and the simulation ran every
frame, so two copies would have been two allocation profiles to keep honest.

**`ActionSetting` is a `Setting<Void>`** wrapping a `Runnable`, drawn as a button by
`ActionComponent` and skipped by `ConfigManager` because it has no value to persist. Added
for verbs rather than states (DonkeyRitual's "Preload hotbar.nbt").

**Future got a search panel** — draggable, scrollable, filters every module with the
description on hover — and `ScrollingText` for its narrow columns: text that doesn't fit is
clipped to its slot and ping-pongs with a pause at each edge. Outside a Future pass the
helpers preserve the normal ClickGUI's unclipped placement, so one text path serves both
styles without the plain GUI inheriting Future's clipping.

**Tablist markers insert after the username without disturbing server styling.** Flattening
resolves inherited styles first, so a server that colours the name and its custom HP
separately keeps both, and neither leaks into our marker. The name token is matched from the
*last* complete occurrence, because prefixes routinely repeat the name.

**The Azure client id is now overridable in `alts.json`** — and the default deliberately
stays a grandfathered one. Microsoft gates apps registered after ~2022 behind an approval
form, and the gate is enforced on the *Minecraft* leg only: MSA sign-in and both Xbox
Live/XSTS legs succeed and then `login_with_xbox` returns 403 `"Invalid app registration"`.
Anyone swapping in a self-registered app will hit that and it will look like a token bug.

## Saved creative hotbars in survival ✅ DONE (2026-08-09, v2.1)

`hotbar.nbt` is vanilla's, it holds full component stacks, and nothing in the protocol will
put those stacks back on a server: `handleSetCreativeModeSlot` is the only handler that
accepts a client-authored `ItemStack` and `abilities.instabuild` gates it. So the transport
is the feature. Three routes shipped — **Creative spoof** (bridge channel, negotiated at
handshake, one write per stack, no size cap), **Script run** (`commandScriptACE 0`), **App
command** (`spawnart.sc`). See ARCHITECTURE §4.1.

**Size is what separates them.** The command packet is a bare `readUtf()` — 32767 — so a
single component-heavy stack can need chunking, and chunking is different per route: Script
run has to escape for Scarpet's single-quoted strings (`\\` and `\'`, load-bearing because
SNBT reaches for single quotes whenever a value contains a double quote) and accumulate into
a global it joins once and then clears, so a 5 MB payload isn't left sitting in the app host.
App command rides raw through `begin`/`chunk`/`commit` because the app takes `text`
greedily — correspondingly more payload per command. The spoof route has none of this.

**A null count is what makes the stored count survive** on the Scarpet routes:
`inventory_set` only overrides the count when the argument isn't null, and the count is
already inside the serialised stack. The item name is ignored whenever NBT is supplied.

**`DonkeyRitual`** is the same restore as theatre, and its one real engineering problem is
timing. A container write applies a tick late (main-thread hop), so the kill is held
`SWAP_SETTLE` ticks after the swap; `LETHAL_MARGIN` 1.5 covers `ATTACK_DAMAGE` not knowing
the weapon's enchantments. Both biases point the same way — swap early, never late, because
late means the donkey dies holding cobblestone. Mounted attackers never crit, so there's no
crit term in the estimate.

Renamed on the way out (2026-08-09): `DirectPlacementNet` → `CarpetBridge`, and the
`Method` mode `Direct` → `Creative spoof`. `isSpoofMethod()` treats unknown values as the
spoof path and normalizes on first use, so configs written before the rename still load.

## Block picker: the whole registry ✅ DONE (2026-08-04, post-v2.0)

Asked for directly: the picker was "really limited". It was — its catalog was the three
XRay presets plus whatever was already selected, so **any block nobody had anticipated
could not be added from the GUI at all**, for either XRay or Search. Now the catalog is
the block registry (~1.1k), the presets are tabs filtering it, and there's a `TextBox`
search over name *and* registry id.

The three preset buttons became preset *tabs*, and their old apply-the-preset action is
now **Add all**, which adds everything currently listed. That is strictly more capable
than what it replaced: the old buttons called `setAll`, so ores + storage was not
expressible; add-all on two tabs in turn is.

**What the registry actually contains, once you list all of it.** Display names are not
unique — 51 names cover 102 blocks, and every group is `{x, x_wall_*}`: `acacia_sign` and
`acacia_wall_sign` are both "Acacia Sign". Two identical rows with independent checkboxes
is worse than not listing them, so the wall variant is marked `(wall)`. Marking one of the
pair is enough, and marking both was actively worse — the first attempt appended the full
registry path to *both*, which ran the label under the checkbox. Row labels are now
clipped with `Font.plainSubstrByWidth`, because modded block names aren't bounded by
vanilla's.

The catalog is built once and cached across opens, rebuilt only when
`ItemUtil.componentsBound()` flips: icons come back empty with no world, and a catalog
built on the title screen would otherwise keep blank icons after joining. Names come off
the block, so the whole picker works from the main menu.

Verified by driving the real input path in a client gametest — `typeChars` through
`ClickGuiScreen.charTyped` into the search field, and a real click on the Ores tab — then
reading the screenshots. That is also how the duplicate-name problem was found: it was
visible in the first capture and in no code review.

**3DSkinLayers visual check** closed the same day: Lucien confirmed the voxel layers
render correctly — no floating layers, no z-fighting — so the last open item from Phase 13
is gone and the module's default-off is now a preference rather than a hold.

## Screen smoke test ✅ DONE (2026-08-04, post-v2.0)

Prompted by counting the crash reports rather than by a feature request. Of the 16 in
`run/crash-reports` up to v2.0, **11 were ours and 10 of those were a screen or widget
throwing while rendering** — and one of them, `ItemPickupWidget.drawPlaceholder` on the
title screen, produced four separate reports across three weeks and shipped through
v1.9.1, v1.9.2 and v1.9.3 before anyone diagnosed it. Not because it was subtle, but
because opening the HUD editor from the main menu is not something you think to do
before tagging a release.

`src/gametest/ScreenSmokeTest` (Fabric's client gametest API, already on the classpath
via `fabric-api`; `fabricApi.configureTests` in `build.gradle` creates the source set and
the `runClientGameTest` task). It opens both ClickGUI styles, all four picker popups, the
HUD editor, Configs, Console, Friends, Alts and Skins — **once with no world and once
inside a generated one** — then enables every HUD widget and renders the plain in-game
HUD, which is a different path from the editor's (`editing = true` swaps world-dependent
widgets for placeholders, so the code that runs while you actually play would otherwise
go untested). ~30s end to end.

It asserts nothing about layout. The claim is only that the frame does not throw, and
that is deliberate: it's the cheap half of correctness and it is the half we keep
shipping broken.

**Proved by A/B, not by reasoning.** Reintroducing the exact `new ItemStack(Items.DIAMOND)`
line fails the run in 16 seconds, at `[smoke] no world — HUD editor`, with the original
stack trace. A test that has never been seen to fail is not evidence of anything.

Two things worth knowing if you extend it:
- Popups are **static global state**, so they're opened inside the screen supplier and
  closed after; leaving one open leaks it into the next screen's render.
- With no world, don't close to a null screen — vanilla doesn't expect that state. The
  sweep restores `TitleScreen` between phases instead.

CI runs it as its own job under Xvfb with mesa's llvmpipe, uploading logs and crash
reports on failure.

### Coverage expansion (2026-08-10)

The smoke test now explicitly selects every Skeet category (Future already draws all
categories together) and opens the generated settings popup for every HUD widget in both
title-screen and in-world contexts. `ClickGuiScreen.selectCategory` and
`HudEditorScreen.openSettings` make those state transitions deterministic without brittle
pixel-coordinate input. This closes the last UI-coverage items in `plan.md`.

Search also now reuses StorageESP's camera-stamped `BoxGeom` pattern, so a large
occluded result set recomputes clipped geometry only after a rescan, camera move, or
occlusion-toggle change. Friend colours now flow through PlayerESP's fill, outlines,
tracers, skeleton, names and glow pass; Nametags has its own configurable friend-name
colour. NoRender's portal/nausea option zeros the combined visual projection strength in
`GameRenderer.renderLevel`, leaving portal travel and the nausea status effect intact.

## Future ClickGUI, HUD ownership, chat completion ✅ DONE (v2.0, 2026-08-04)

Unplanned batch again, all asked for directly. The through-line: three features each
wanted a resource the frame only has one of, and each one crashed the game before it
was arbitrated.

**A second ClickGUI (`FutureClickGuiScreen`).** Picked by `ThemeModule.clickGuiStyle`;
`ClickGuiScreen.create(parent)` is the only call sites should use. It shares **no layout
code** with the Skeet-style screen on purpose — Future's identity is every category on
screen at once, and one layout serving both makes both mediocre — but it shares every
`GuiComponent`, so behaviour cannot drift apart. That sharing is exactly what exposed
the bug worth writing down: components reached for `Theme.accent1` by hand, so Future's
aqua glass was full of Skeet-green checkboxes, sliders and dropdown marks. Every control
the user actually touches was themed by the wrong client. `ClickGuiPalette` resolves the
accent against the active style instead; Future is one accent rather than a gradient, so
both ends collapse onto it and the ramp flattens. Only the accents move — the recessed
greys and border blacks are neutral enough for both looks.

**`FrameBlur`, and why it exists.** `GuiRenderState.blurBeforeThisStratum` records one
stratum per frame and throws on the second call, so a blurred HUD widget under a blurred
menu was an outright crash (`Can only blur once per frame`) — three crash reports on
2026-08-03, two from the HUD editor and one from the Future screen. Everything now asks
`FrameBlur.claim`, and the loser goes without. Which one loses is not arbitrary and is
the part that took the thinking: the blur applies to everything below the claiming
stratum, and the HUD extracts a stratum earlier than the screen above it, so one claim
genuinely cannot serve both. The HUD stands down via `screenWillClaim()`, which costs
nothing visually because every client screen bar Future blurs the whole frame anyway.
The claim is reopened in `Gui.extractRenderState` HEAD — the only hook that runs every
frame regardless of what is on screen. Clearing it in `GuiRendererMixin` on the way out
looks equivalent and silently isn't: those injections sit around `processBlurEffect`,
which vanilla skips entirely on frames where nothing blurred, so the claim would stick
and no menu would ever blur again. The HUD element is no good either — F1 skips it.
Future's own panel-clipped blur is the same one blur, snapshotted sharp, kept blurred,
and replayed through a scissor per column.

**The HUD stopped being a module setting.** Widgets already owned their settings
(v1.9.2); this batch gave them the rest of a real editor. Every widget inherits scale,
padding, opacity, anchor, transition and background/border — appended *after* its own
settings, because a setting added in a base constructor always sorts first and would
displace the toggle-first convention. Shared panel treatment (opacity, radius, border,
animated accent) lives on `HudModule` and is mirrored into `Theme` statics, since
`Render2D.hudPanel` is called from paths with no widget in scope. Widgets can now be
**duplicated**: settings copy by name *and* concrete type, and the copy takes a generated
`duplicate:<uuid>` config key while the primary keeps its legacy name key — which is what
stops several instances of one widget from overwriting each other in the JSON. Restore is
deliberately narrow (only types already registered as primaries), so config data can never
name an arbitrary class to instantiate.

**Chat completes our own commands.** `ClientCommandChatUi` + `ClientCommandChatMixin`
suggest module names, binds, friends, waypoints, registry, stash and printer-base
arguments as you type a dot command. Scoped hard: the popup only ever appears for the
syntax `ChatCommandMixin` already claims, so vanilla chat and slash commands keep their
own `CommandSuggestions` path untouched.

**Freecam's F5 proxy.** The real player stays extracted at its true world position — it
can be far outside the freecam frustum, and culling it is what made the body vanish — and
the translucent spectator head near the camera is a second, independent extraction marked
by `FreecamRenderProxy`. First person keeps no proxy at all, exactly like vanilla.

**Four crashes and two silent wrongs, fixed:**
- **The main-menu HUD editor** (2026-08-04). 26.2 binds item components on the registry
  `Holder` only once a world syncs them, so `new ItemStack(Items.DIAMOND)` in the pickup
  placeholder threw `Components not bound yet` on the first rendered frame. Same cause
  took out the module-toggle toast and both ClickGUI pickers. Written up in ARCHITECTURE
  §6; `ItemUtil` is the fix. Verified by probe rather than by reasoning: a temporary
  `TitleScreenMixin` inject opened the editor straight from the title screen and logged
  `componentsBound=false diamondIconEmpty=true`, then rendered clean for 15s.
- **Media keys.** GLFW reports them as `KEY_UNKNOWN`, which is also our unbound sentinel,
  so one press dispatched to every unbound module at once and rebinding onto one silently
  cleared the bind. Guarded at both ends.
- **AutoSprint** was sending START_SPRINTING and STOP_SPRINTING every tick, forever, for
  as long as you leaned on a block. It re-asserted the flag *after* `aiStep` cancelled it,
  and the next `sendIsSprintingIfNeeded` saw a flip. It now mirrors vanilla's own cancel
  conditions and simply stops asking during the ticks vanilla would say no.
- **InventoryMove arrow-look** ran on the tick loop, so the camera snapped at 20 Hz —
  `LocalPlayer`'s view rotation is not interpolated by the camera. Moved to `Camera.update`
  (per frame); `realtimeDeltaTicks` keeps "degrees per tick" meaning the same thing.
- **The printer stash list** was a default 64-character `StringSetting` — exactly five
  chests. A sixth silently dropped a digit off the end and the coordinate still parsed:
  970 read back as 97, and the survey lap flew 877 blocks north to a chest that had never
  been there. Sized explicitly at 4096, and `markStash` now refuses rather than clips.
- **`Invalid path in mod resource-pack unlucky`** on every launch — an uppercase
  `README.txt` under `textures/capes/`. Resource paths must be lowercase.

**Aura Auto switch** picks a weapon from the hotbar before attacking (hotbar only —
pulling from the inventory means container clicks mid-fight, a different and far louder
feature) and returns to the previous slot afterwards. The attack on the switching tick is
skipped on purpose: a slot change only reaches the server on the next tick's sync, so
hitting immediately lands with the old item. Costs one tick, once per fight.

**Friend marks moved onto the face.** `HeadRenderer.badge` draws a 3×3 corner dot over a
black square with a 1px edge, so it stays readable against a light skin, and the tablist,
locator bar, compass strip and nametag heads all use it. The head is already the row's
identity, so the mark costs no width. Vanilla only draws tablist faces on online-mode
servers — on a cracked server there is no head to badge, so `Friends.tablistNameColor`
hands the mark back to the name rather than letting friends silently lose it.

## GUI polish + anarchy chat ✅ DONE (v1.9.3, 2026-08-02)

Unplanned batch, all asked for directly. Four things shipped; two of them were
bug fixes to code written earlier in the same session, which is the pattern worth
noting.

**Shared `ui/ColorPicker`.** Both color UIs — `ColorComponent` and the HUD editor
popup — had their own copy of the HSB bar drawing and dragging, and they had already
drifted. One class now serves both, with a Picker / HEX / RGB tab strip and the mode
stored globally in `ThemeModule.colorMode`. Merging them surfaced a latent bug in the
HUD editor's copy: it derived HSB fresh from the ARGB every frame, so dragging Val to
0 lost the hue and the color snapped to red on the way back up. The ClickGUI copy
cached HSB to avoid exactly that but never noticed external edits. The shared picker
caches *and* re-syncs when the stored color changes underneath it — which is what
makes typing a hex code and then dragging a bar behave at all.

**Conditional settings** (`Module.add(setting, condition)`). 25 rows across 13 modules
now hide when their mode doesn't apply. The constraint that kept it safe: hiding is
*cosmetic only* — value stays live, stays saved, still read — so a hidden row can never
change behaviour. Each one was checked against its call sites before being hidden,
not against its description, because the descriptions had drifted: Chams' "Through
walls" claimed "(Flat/Image modes)" when Portal uses it too.

**ElytraFly Static.** Technique read off Meteor's `elytrafly` package and reimplemented
against 26.2 — their code is GPL and targets 1.21 mappings. Deliberately *not* their
Packet mode, which spams `START_FALL_FLYING` and forces `abilities.flying`. Replacing
the return of `updateFallFlyingMovement` gets the same feel through the normal movement
path. The hook location is the whole trick and is written up in ARCHITECTURE §6.

**Spam / BibleBot / Greentext.** Presets are ours, not lifted — a lot of the actually
famous 2b2t lines are slurs aimed at specific people. `ChatFont` gives eight Unicode
styles, and **every one of its 78 non-ASCII codepoints was checked against the bundled
unifont before shipping** rather than trusted; that check is what caught the
Script/Fraktur hole problem (§6). Greentext created the first case in this codebase of
two injections at the same HEAD where order actually mattered — resolved by making the
transform skip whatever the other injection would claim, so both orders emit the same
bytes. Forcing an order was never on the table; mixin doesn't offer one.

**Three bugs the tooling found, not the reader:**
- A scratch harness over `ChatFont` caught `fit(text, 0)` indexing `charAt(-1)`. Not
  reachable from either caller, but it's a public util.
- `NumberSetting.display()` formatted every fractional step `%.1f`, so a 0.05-step
  slider showed 0.05 and 0.10 identically — the number freezes while the handle moves.
  Already wrong for ElytraFly's 0.02-step Acceleration (default 0.08 displayed "0.1").
- Opening a dropdown near the bottom of a folded module box grew the content past the
  line limit and the list vanished behind the expander dots. The click registered and
  the setting was reachable blind, so it read as "nothing happened".

**Method note.** The unifont check and the `fit` harness both cost a few minutes and
both found something. The pattern from the v1.9.2 retro — *fix the instrument first* —
generalises: when a claim is cheap to verify (does this glyph exist? does this trim
hold at every limit?), verifying beats reasoning, and the reasoning was wrong twice
here. An earlier grep in this same session said the math alphanumerics were absent;
they weren't, the `.hex` just pads past-BMP codepoints to six digits.

## Printer LP3b+LP4 — survival restocking ✅ DONE (v1.9.2, 2026-08-02)

The other half of the map-art ask: a print that runs **fully AFK** has to fetch its
own material. Creative refills from a packet; survival has to fly somewhere, open
something and come back — a different problem, so it ended up a separate planner
rather than more conditions inside the creative one. Design in ARCHITECTURE §4.1.

Shipped: material passes (one block type at a time, commonest first, frozen ranking),
support-block ordering read off the schematic, carried-shulker cycles
(`ShulkerRestock`), the chest stash (`ChestStash`, `.stash` / `.stash list` /
`.stash check`), an opening survey of every chest, exact on-demand band counting,
a Layers HUD widget, AutoEat coordination ("Pause on AutoEat"), automine with the
right tool, and a trip route overlay.

**What this cost, and why it is worth writing down.** The policy above was right
early and barely changed. Roughly a dozen bug reports later, *every* failure had
been in the machinery underneath it — and all of them were the same shape: two
correct components disagreeing about what a shared word meant.

- `wanted` meant "this trip's shopping list" to the depositor and "what the print
  needs" to everything else, so a trip deposited exactly what the previous one had
  flown out to fetch. Cobblestone, carpets, cobblestone, carpets, with a
  `supply run done: 0 blocks aboard` in the middle.
- `gained` meant "net bag change" when the question was "did it get what it came
  for". A trip deposits before it withdraws, so a run that put back 30 and took 41
  scored 11, fell under the worthwhile bar, and earned a **60-second lockout** for
  succeeding.
- `laneIndex` meant "flown past" to the driver and "done" to the forecast, which is
  `forecast.from(laneIndex)`. Work flown over without material silently stopped
  being asked for: a trip list of 59 against a band still owing 545.
- Four different counts of "what this band needs" — scan snapshot, rolling tally,
  route forecast, exact walk — with different code reading different ones. Replaced
  by the one exact count, taken on demand.

None of those are visible in a single file, and none would fail a review of the
function they live in. **Three method lessons**, all learned the hard way:

1. **Fix the instrument first.** For most of the cycle the event log was actively
   lying — `note()` dropped any event already in the trail, which hid *repetition*,
   which was the entire symptom under investigation; and both helpers kept a
   single-slot `event` field that overwrote itself. Diagnoses took three reports
   each until that was fixed and one report each afterwards.
2. **One symptom, many causes.** "It goes back too often" was six independent bugs.
   Fixing one left the symptom at ~80% and read as a failed fix.
3. **Prediction was the wrong tool.** `MaterialForecast`'s coverage/bisection layer
   answers "which colour runs out first on a mixed route" — a real question in
   creative, a non-question for a pass carrying one material. Worse, its threshold
   (`coverage < min(restockAt, routeLength)`) is *unsatisfiable* once the route is
   shorter than the margin, which is how the printer flew to the stash and back for
   ever over blocks it was already carrying.

Also in v1.9.2, found through the same reports: NoFall's Packet mode **cannot**
protect printer flight (it watches `fallDistance`, which vanilla holds at zero while
`abilities.flying` is set), so the Printer asserts the ground spoof itself while it
is the one flying. AutoEat closes containers, suppresses block interaction via
`useItemOn` → PASS rather than a real sneak (shift is *descent* on a flying printer),
and verifies the meal actually started — a blocked eat used to freeze every module
with "Pause on AutoEat", permanently. Plus ClickGUI resizing (reflow + zoom), a
themeable top bar, and `LogSpam` for Litematica's per-frame render logging.

## Printer LP1+LP2+LP5 — Litematica schematic printer ✅ DONE (2026-07-30)

Requested for **map art on survival servers** ("printing art takes so much time").
Recreated from two references per the read-the-original rule: kkllffaa/meteor-litematica-printer
(scan → filter → sort → place shape) and Nippaku-Zanmu/Seija-Printer (randomised
timing, recently-placed blacklist, sneak-place, and the vanilla-simulation idea kept
for LP5). Remaining phases in [plan.md](plan.md).

- [x] **LP1 — soft dependency + `util/LitematicaBridge`.** Litematica 0.28.4 + MaLiLib
      0.29.3 for 26.2 exist on Modrinth; pulled from the Modrinth maven via an
      `exclusiveContent` filter. Declared **`compileOnly`, not `modCompileOnly`** — the
      discovery that made this simple is that **26.2 mods ship Mojang-mapped**: the
      Litematica jar contains zero intermediary refs (`net/minecraft/core/RegistryAccess`,
      not `class_5455`), and so does our own remapped output, so there is nothing to
      remap and calls link directly at runtime. Verified `unzip -l … | grep -c fi/dy/masa`
      → `0`: nothing is bundled.
      Bridge surface is deliberately tiny and read-only — `present()`, `hasSchematic()`,
      `required(pos)`, `withinLayerRange(x,y,z)` — with all `fi.dy.masa` references inside
      a nested `Impl` class so the JVM never resolves them when the mod is absent.
- [x] **Init-order trap found the hard way** (the reason a TEMP-VERIFY probe was worth
      writing): `SchematicWorldHandler.getSchematicWorld()` is *not* a getter — it lazily
      builds `WorldRendererSchematic` → `SchematicRenderState` → `ChunkFixUniform`, which
      calls `RenderSystem.getDevice()`. Called from `onInitializeClient` it throws
      `IllegalStateException: Can't getDevice() before it was initialized`. This matters
      because **config load re-enables saved modules at init time**, so the module's
      `onEnable` would have crashed for anyone who left the Printer on. Fixed by keeping
      `onEnable` to the loader lookup only and deferring the "load a schematic" notice to
      the first in-world tick. Also noted: `LayerRange` moved to
      `fi.dy.masa.malilib.util.position` (older addons import it from `litematica.util`).
- [x] **LP2 — `Printer` module** (`modules/world/`, Category.WORLD, 19 settings). Per tick:
      decay the fade + blacklist maps, scan a reach-limited sphere, ask the ghost world
      what belongs, drop anything already correct / out of Litematica's layer range /
      blacklisted / unsupported / inside the player, sort (Nearest, Furthest, Bottom up,
      Top down — the vertical modes tie-break by distance so a layer still fills outward),
      then place up to *Blocks/tick* with `useItemOn` on a computed `BlockHitResult`.
      Notable decisions: the **click point** is range-checked, not the target block, since
      that's what the server checks; **container faces are skipped outright** rather than
      trusting sneak, because a click that opens a screen also stalls the printer; the
      creative restock sets the stack **locally as well as** sending
      `ServerboundSetCreativeModeSlotPacket`, since the click we send the same tick is
      predicted against the client's own held stack; and a **recently-placed blacklist**
      (300 ms) stops a laggy server getting duplicate clicks for a position whose block
      hasn't arrived yet.
- [x] **LP5 — `util/PlacementSolver`, from Lucien's bug report.** He printed
      `waifu.litematic` and found "many error blocks that have wrong positioning" plus
      snow coming out one layer where three were wanted. **Both were the same root cause:**
      the scan compared *blocks* (`current.getBlock() == required.getBlock()`), so snow
      counted as finished after its first layer, and `placeAt` clicked the first
      geometrically valid face without regard for what state that click would produce.
      The fix is one mechanism, not two: enumerate plausible clicks (6 faces × 3 points up
      the face × the player's own facing then all 4 compass directions × level/up/down),
      run each through a `BlockPlaceContext` subclass that answers for a *simulated*
      rotation, and keep the click whose `Block.getStateForPlacement` matches. This is
      Seija's insight and the reason they deleted ~20 per-block handlers; we never wrote
      the quirk tables meteor-litematica-printer carries.
      The load-bearing detail is `distance(from, to)`: disagreeing properties count 1,
      **numeric properties count their difference**, so "snow needs 2 more layers" reads as
      progress rather than as equally wrong. A click is only sent when it strictly reduces
      that distance, which is also the loop guard — a wrongly-turned block no click can fix
      goes into an `unsolvable` map (3s) instead of being re-solved every tick forever.
      Falls out for free: slab→double, candles, sea pickles, and every orientation-sensitive
      block including ones we never considered. Chosen rotation is spoofed via
      `RotationManager`, so the server derives the state we predicted (the client's own
      prediction still uses the camera and can flicker; the server's update corrects it).
      New `Precise` setting (default on) refuses anything but an exact match.
- [x] **LP5 verified on the schematic that produced the report.** `waifu.litematic` was the
      right test by luck: its palette holds snow in 7 states, dark oak stairs in 4, slabs in
      3, and stained-glass panes in 10. Probe over a print run: **35 placements of
      property-bearing blocks, 35 exact matches, 0 near-misses**, including
      `snow[layers=1] → [layers=2]` ten times (the reported bug), `slab[bottom] → [double]`,
      panes with the right connection flags, and stairs solved to `facing=east` *and*
      `facing=west` from different rotations (`yaw=270` etc. — proof the facing search is
      doing real work). **Zero positions were attempted twice**, which is the strong signal:
      a misprediction would have left a wrong state behind and brought that position back.
- [x] **Verified in-world, not just compiled.** Dev client with both mods in `run/mods/`,
      Printer enabled via config, temp probe logging scan results: read real states from
      Lucien's own schematic (`green_terracotta`, `green_wool` at BlockPos{355,73,413}),
      **679 blocks placed** over the run, candidates correctly returning to 0 once an area
      completed (the already-correct check works), no errors from our code. Litematica's
      own `addTask: [EMPTY] Waking up threads...` churn logs at ERROR level and is normal.
      Probes and the config change were reverted afterwards.

## Printer LM — movement automation, HUD, and the rotation hunt ✅ DONE (2026-07-30)

Lucien's brief: *"I can trust that if I go away from the PC it will end the whole task by
itself"*, anticheat explicitly a non-concern (anarchy + singleplayer).

- [x] **Lane routing, after going in blind did not work.** The first attempt picked a
      vantage per tick from scored candidates and patched each failure mode with a reflex;
      the interaction of those reflexes *became* the failure mode (bobbing on flat mapart,
      flying to empty ground, sticking on 1-block lips). Lucien called it: *"lets think
      about the approach, because going in blind doesn't work"*. Rewritten around one
      principle — **the plan owns geometry, feedback owns only the throttle**: a band is
      scanned into a snapshot (`PLAN`), a serpentine lane is built over the *work* (not a
      fixed grid, strips centred on what they cover and split where the gap exceeds 16),
      flown plainly at one fixed height (`DRIVE`), and the only per-tick decision is speed
      — 25% while anything is in reach, so moving never outruns placing. Bobbing and
      dithering are not fixed here; they are unrepresentable. `SETTLE` then rescans; a pass
      that placed nothing books what is stuck and moves the band up, so it always
      terminates. `VantagePlanner` was deleted.
- [x] **`util/FlightPath`** — bounded 3D A* for detours, with `fitsAt(Vec3)` body checks.
      Lucien diagnosed the bug that made smoothing worse: sampling **floored to blocks**
      offsets the path down into the floor.
- [x] **Auto layers** drives Litematica's own `LayerRange` band-by-band, so the ghost
      blocks on screen are exactly what is being built; the user's layer view is captured
      and restored on disable.
- [x] **`.report` (in-game forensics).** Lucien asked for a way to say *"it missed here"*
      and have the client explain itself: `ChatCommandMixin` intercepts `.`-commands
      client-side (never sent to the server), and `.report` dumps the phase, band, lane
      index, candidate/pending/unsolvable counts, an event trail, and a full solver trace
      for the block being looked at. It immediately found a real bug (wrong blocks placed
      — the once-per-tick carried-slot sync).
- [x] **Printer + Materials HUD widgets** (LP3's read-out half): status, blocks placed,
      blocks missing across every layer, rate, ETA, and **elapsed working time**. The
      counters come from a background whole-region tally that cycles continuously, so they
      self-correct rather than being bookkept. Elapsed is counted **in ticks, not wall
      clock**, which makes every pause free — a closed screen, a finished build, the module
      off, the game paused: none of them tick.
- [x] **Schematic picker.** `Schematic` lists every enabled placement (plus All); picking
      one scopes the route, the reach scan, the tally *and* the counters to it. The fence
      matters because the ghost world merges every placement's blocks — without it the
      printer would happily build the neighbour that drifted into reach. Counters key on
      the pick plus its region, so two schematics stay two jobs with two clocks.
- [x] **Third-person silent rotation — four rounds, three of them wrong.** Reported as
      *"the F5 rotate is still not visible"*. Three fixes went into the render path
      (entity fields → pose hold → render-state override), each justified by bytecode that
      was correct, none of which touched the cause. The fourth round shipped a **probe
      instead of a fix** (`RotationProbe` + `.rot`, both removed in v1.9.2 once the bug
      was closed), and it named the culprit in one run:
      the spoofed yaw matched the camera *to the decimal*, because `PlacementSolver`
      offered the player's own facing as its first candidate and every mapart block
      accepts it. Fixed by leading with "look at the click"; safe because a facing is only
      accepted when the simulation produces the wanted state. The duty-cycle half
      (`holdAim`, 22229/137590 frames posed) was found by the same counters. **Lesson
      recorded in ARCHITECTURE §6.**

### Round-up of the same session's smaller fixes

- [x] **HUD settings moved onto the widgets.** ~140 settings left `HudModule`; the editor's
      right-click popup is now `widget.settings()` and the hand-written `switch` is gone.
      Two ArmorHUD options that had never appeared in any menu showed up for free. Old
      configs still load (names unchanged, read from the old block as a fallback).
- [x] **ClickGUI module boxes fold.** Theme → *Module lines* (default 12); past that the box
      truncates to whole rows and gets three dots on its bottom border, drawn with a patch
      the way the title breaks the top border, so the affordance costs no height.
- [x] **ClickGUI scroll stopped ~20px early on the search page.** The input clamp measured
      against `windowHeight - 4` while the renderer measured the flow area — equal on a
      category tab, off by the search field's height on the search page. Both now read one
      cached flow height.
- [x] **Sodium broke NoFog.** Same injection point, same default priority; ours is now
      `priority = 500` so it is applied — and runs — first.
- [x] **Toasts vs movable widgets** (Lucien: *"they spawn top to bottom but the first one
      removed is the top one"*). Avoidance measured the toast *count*; vanilla never
      repacks the stack, so an expired top toast left a hole and widgets rode up under
      toasts that had not moved. Now measured from `occupiedSlots.length()` — the last
      occupied slot.

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

## Eleven modules from Meteor and Trouser Streak, and the sprint flag (2026-08-15)

**AutoSprint went wild jumping up blocks, and both my theories were wrong.** The class
already carried a note about a packet storm fixed once before; this was the same symptom
back again. Per the probe-before-fixing rule the first thing shipped was not a fix but
`.sprint` (`SprintProbe` + hooks at `aiStep` HEAD/RETURN, `sendIsSprintingIfNeeded`, and
every write to the flag with the frames behind it). The first recording killed both
candidates I had reasoned out — collision timing and omni mode — and showed something
neither predicted: `out=1` (vanilla kept the sprint), START_SPRINTING on the wire, and then
the flag *gone* before the next tick, in a window nothing was watching. The second
recording, with a witness on every writer, named it:
`SynchedEntityData.assignValues ← ClientboundSetEntityDataPacket`. **The server was
correcting us**, because the sprint bit in the input record it judges by was never set.
480 packets in 2228 ticks became 60 in 737 once AutoSprint started holding `keySprint` and
let vanilla's `aiStep` do the starting, cancelling and re-taking. Written up as a trap in
ARCHITECTURE §6, because anything that touches the flag will hit it.

**Ten modules ported from Meteor, one from Trouser Streak**, chosen off a screenshot of
Lucien's module list and cross-checked against our 189 so nothing was rebuilt: Tracers,
Storage ESP, Blur, Marker and Better Tooltips all looked like gaps and were already ours
inside ESP / ThemeModule / Waypoints / InventoryInfo.

- **ItemHighlight**, **CameraTweaks** (absorbed ViewClip; Freelook stayed separate),
  **VoidESP**, **TunnelESP**, **EntityOwner** — the render batch.
- **SpawnProofer**, **LiquidFiller**, **AutoSign**, **AutoNametag**, **AutoMount** — the
  world batch, the first two on `PlacementExecutor`.
- **BaseFinder** — the big one.

Meteor's master happens to sit on the same API generation we do (`GuiGraphicsExtractor`,
`extractSlot`, `EntityReference`), so these are ports rather than rewrites. Sources were
pulled from GitHub into `Original files/` and read, never guessed — Lucien's standing rule,
and it earned itself twice: `TunnelESP` is core Meteor rather than an addon, and the tier
lists below could not have been retyped correctly.

**What was deliberately not 1:1**, and the one time that was a mistake:

- Scans are budgeted sweeps on the client thread (LightOverlay's shape) rather than
  Meteor's per-chunk worker jobs. Our renderer emits gizmos from the tick, and a background
  thread reading chunk sections is a race this codebase already has a scar from.
- Adjacent cells are merged into boxes — VoidESP's rectangles, TunnelESP's runs — because a
  two-wide gap is one hole, the same call HoleESP already made.
- **TunnelESP's filter, which was wrong.** Meteor keeps a cell if any of its four
  neighbours is also a tunnel cell. I replaced that with "measure the run along this cell's
  own axis", and since the cell around a bend carries the *other* axis, it silently deleted
  every corner and T-junction. Lucien caught it as "funky detection". The filter is
  Meteor's connectivity rule again, merging is demoted to drawing, and the class doc says
  so: merging draws, it does not judge. Also added Meteor's surface heightmap ceiling,
  whose absence was letting ledges and roofed gaps qualify.

**BaseFinder's signatures were extracted, not transcribed.** Tier one is ~435 curated
blocks. A field-name check against 26.2's `Blocks` dropped 163 of them, because 26.2 folded
the dyed variants into colour collections — no `BLACK_CONCRETE`, no `WHITE_BED`. Validating
against the real registry ids instead (pulled from the client jar's own `en_us.json`) put
471 of 472 back verbatim. The one that failed is a bug in the original: it names
`potted_azalea`, which vanilla calls `potted_azalea_bush`. The lists live in
`BaseSignatures` with their original thresholds (1/6/4/2/12/12/1), and the module builds
its seven tiers of settings in a loop.

**`SpawnUtil`**, new: LightOverlay's spawn test moved out so SpawnProofer covers exactly
what LightOverlay draws. Same rule `HoleUtil` exists for — a marker one module honours and
the other ignores looks like a bug in both.
