# Unlucky Client — implementation TODO

This is the implementation plan for the next client modules after v2.2. It is intentionally tied to the current architecture instead of being a feature wishlist.

## Scope and non-goals

- Current baseline: 150 modules registered by ModuleManager.
- Planned additions: 28 modules, for a target of 178.
- Baritone is explicitly out of scope. AutoWalk remains simple key-driven travel and must not become a pathfinder.
- The entire addon/script/macro proposal is out of scope.
- Do not add anti-cheat-specific modes, packet-disabler behavior, timer abuse, rubber-banding, or named bypasses. Keep the existing vanilla/anarchy-server policy.
- New modules must use the existing Setting classes, ConfigManager, notification system, theme, friend/target systems, and shared action owners.
- Every module must declare accurate ServerVisibility. A CONDITIONAL module must implement isServerObservableNow().
- Every implementation slice must pass the existing module smoke tests before the next slice starts.

## Required shared work

Complete these foundations before the modules that depend on them. These are internal utilities, not user-facing modules.

- [ ] Add InputActionCoordinator.
  - Own temporary movement/use/attack key states by priority and release them at end-of-tick, disable, disconnect, and panic.
  - Migrate AutoEat's direct use-key ownership to it.
  - Consumers: AntiAFK, AutoWalk, AutoEat, and future reactive movement.
- [ ] Add MiningActionCoordinator and MiningTracker.
  - Track the local target, mining mode, progress, start time, tool, rotation request, and packet lifecycle.
  - Expose one isModuleMining() query to MinecraftMixin; replace the current Printer-or-VeinMiner hard-coded check.
  - Consumers: SpeedMine, BreakIndicators, AutoTool, Nuker, Printer, and VeinMiner.
- [ ] Extract PlacementExecutor from the proven Surround/Scaffold flow.
  - It must call PlacementSolver, RotationManager, InventoryActionCoordinator, and the existing swing/render helpers.
  - It must support per-tick budgets, delay, silent/visible rotation, inventory selection, swap-back, air-place policy, pause-on-eat, planned rendering, and placed rendering.
  - Consumers: Surround, Scaffold, HoleFill, Burrow, and AutoTrap.
- [ ] Add HoleUtil.
  - Classify single, double, and quad holes.
  - Return floor/wall material as bedrock, obsidian-resistant, mixed, or unsafe.
  - Centralize web occupancy, headroom, depth, and replaceability checks.
  - Consumers: HoleESP, HoleFill, Burrow, and AutoTrap.
- [ ] Add EquipmentScorer and InventoryPolicy.
  - EquipmentScorer ranks armor using attributes, durability, enchantments, curses, and user preferences.
  - InventoryPolicy classifies protected, useful, capped, excess, and explicitly disposable stacks.
  - Consumers: AutoArmor, ElytraSwap, InventoryCleaner, and ChestCleaner.
- [ ] Extend InventoryActionCoordinator priorities without creating a second click owner.
  - Keep MANUAL 110, TOTEM 100, SAFETY 90, COMBAT 80, PLACEMENT 70, TOOL 60, REPLENISH 50, LOOT 40, and FARMING 30.
  - Add ELYTRA_SAFETY 85, EQUIPMENT 65, CLEANER 45, and AUTOMATION 35.
  - Preserve one menu click per granted action window and the existing manual-input backoff.
- [ ] Add HealthChangeTracker.
  - Normalize confirmed damage/heal events from packets and entity health changes.
  - Feed both HealthIndicators and HitEffects so they cannot disagree or render duplicate numeric effects.
- [ ] Add RecipeAutomation.
  - Provide shared visible-menu validation, delay/budget handling, inventory reservation, output-space checks, and stop reasons.
  - Consumers: AutoCraft and AutoSmelt.
- [ ] Add persistent world-record storage for StashFinder.
  - Store data separately from module config.
  - Key records by server identity, dimension, and chunk coordinates.
  - Use atomic writes and tolerate old or malformed records without blocking client startup.

## Shared implementation rules

- [ ] Extend an existing mixin when it already owns the hook.
  - StashFinder joins ClientPacketListenerMixin's existing chunk-load path.
  - SafeWalk joins PlayerMixin's existing isStayingOnGroundSurface hook used by Scaffold.
  - BetterTab extends PlayerTabOverlayMixin and preserves friend/unlucky markers and face badges.
  - SpeedMine extends MultiPlayerGameModeMixin and the shared mining coordinator.
  - BlockOutline replaces the selected-block render decision at one hook only.
- [ ] Keep a single owner for each global render/action pipeline.
  - Shader remains the only silhouette/outline mask owner; ItemESP may configure or annotate it, not duplicate it.
  - OffhandManager remains the only offhand request arbiter.
  - InventoryActionCoordinator remains the only menu-click arbiter.
  - MovementActionCoordinator remains the final velocity-transform arbiter.
  - RotationManager remains the only silent/visible rotation arbiter.
- [ ] Add config migration whenever an existing option changes owner.
  - In particular, migrate AutoTotem fallback behavior into Offhand without silently changing existing profiles.
- [ ] Use bounded caches and scan budgets. No module may rescan every block/entity in render callbacks.
- [ ] Add a short README entry and update ARCHITECTURE.md/module totals in the same change as each module.

---

## 1. StashFinder

- [ ] Add StashFinder in WORLD with CLIENT_ONLY visibility.
- Contract: record storage-heavy chunks when chunk data arrives, notify once per meaningful change, and render or export recorded locations without rescanning the world every frame.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Storage types | Toggle group; chest, trapped chest, barrel, shulker on | Chest, trapped chest, barrel, shulker box, ender chest, furnace, blast furnace, smoker, hopper, dispenser, dropper, chest minecart, hopper minecart |
| Minimum containers | Number 4; 1–64 | Minimum enabled storage count in a cluster |
| Minimum shulkers | Number 0; 0–64 | Optional separate shulker requirement |
| Cluster radius | Number 0 chunks; 0–2 | Merge adjacent chunks before applying thresholds |
| Minimum spawn distance | Number 0; 0–10000 blocks | Ignore records too close to world spawn |
| Support blacklist | BlockList empty | Ignore clusters primarily supported by listed natural/structure blocks |
| Update known records | Boolean on | Refresh counts and last-seen time instead of duplicating a record |
| Notifications | Boolean on | Notify only when a record is new or its qualifying count increases |
| Notification mode | Mode Chat | Chat / Toast / Both |
| Sound | Mode Pling | Off / Pling / Level-up, implemented through PingSound |
| Persist records | Boolean on | Save records across sessions |
| Render mode | Mode Recorded | Off / Nearest / Recorded |
| Maximum render distance | Number 2000; 64–10000 | Cull distant records |
| Hide near distance | Number 16; 0–128 | Avoid a large marker around the player |
| Tracer | Boolean on | Draw a tracer to each rendered record |
| Chunk column | Boolean on | Draw the qualifying chunk/cluster column |
| Label | Boolean on | Show count, distance, dimension, and last-seen age |
| Marker color | Color theme accent | Shared tracer/column base color |
| Export JSON | Action | Export currently selected server/dimension records |
| Export CSV | Action | Export currently selected server/dimension records |
| Clear current dimension | Action | Confirm, then remove only the active dimension's records |
| Clear current server | Action | Confirm, then remove only the active server's records |

Implementation notes:

- Reuse ClientPacketListenerMixin's chunk-load callback rather than adding a competing injection.
- Reuse the existing storage classification from Shader where practical.
- Add .stashes list, nearest, export, clear, and remove commands; do not reuse .stash because Printer already owns that name.
- Never upload or globally combine records. Singleplayer saves and multiplayer servers must remain isolated.

Acceptance:

- [ ] Revisiting an unchanged chunk does not duplicate or re-notify.
- [ ] Dimension/server changes never leak records into the wrong world.
- [ ] JSON/CSV export round-trips negative coordinates and all enabled storage types.
- [ ] A corrupt record file is quarantined or skipped with a notification, not a crash.

## 2. AutoArmor

- [ ] Add AutoArmor in PLAYER with SERVER_OBSERVABLE visibility.
- Contract: equip the safest preferred armor through the shared inventory click owner without fighting AutoTotem, ElytraSwap, or manual inventory use.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Profile | Mode Balanced | Balanced / Protection / Blast / Fire / Projectile |
| Per-slot override | Toggle group off | Helmet, chestplate, leggings, boots each enable a custom profile |
| Blast leggings | Boolean on | Prefer blast protection on leggings when scores are otherwise close |
| Prefer mending | Boolean on | Small score bonus, never enough to beat materially stronger armor |
| Avoid items | ItemList empty | Never auto-equip listed armor |
| Minimum durability | Number 10%; 1–90 | Reject candidates at or below threshold |
| Anti-break action | Mode Replace | Ignore / Unequip / Replace |
| Binding curse | Mode Keep equipped | Keep equipped / Ignore candidate / Allow |
| Elytra policy | Mode Grounded | Never replace / Replace when grounded / Always armor |
| Delay | Number 2 ticks; 0–20 | Base delay between inventory actions |
| Random delay | Number 1 tick; 0–10 | Added random delay |
| Minimum score gain | Number 1; 0–20 | Prevent equal-score armor churn |
| Pause while moving | Boolean off | Do not click while movement input is held |
| Pause while using | Boolean on | Pause while eating, drinking, drawing, or placing |
| Inventory only | Boolean off | Require an inventory/container screen |
| One action per tick | Boolean on | Safety cap; cannot be disabled in the first release |

Implementation notes:

- Use EquipmentScorer and EQUIPMENT priority.
- ElytraSwap's ELYTRA_SAFETY request wins; AutoTotem's TOTEM request remains unaffected.
- Score actual item components/attributes; do not hard-code only vanilla material names.

Acceptance:

- [ ] No oscillation between equal-scored armor.
- [ ] Manual clicks immediately take ownership.
- [ ] A nearly broken elytra is never replaced mid-flight with a chestplate.
- [ ] Curse and durability policies are covered by unit tests.

## 3. InventoryCleaner

- [ ] Add InventoryCleaner in PLAYER with SERVER_OBSERVABLE visibility.
- Contract: sort and optionally discard only policy-approved excess items; default behavior must never throw an item.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Mode | Mode Sort only | Sort only / Cleanup only / Sort and cleanup |
| Run condition | Mode Inventory open | Inventory open / Any supported menu |
| Delay | Number 2 ticks; 0–20 | Base click delay |
| Random delay | Number 1 tick; 0–10 | Added random delay |
| Pause while moving | Boolean on | Yield while movement input is held |
| Hotbar layout | 9 mode slots; Preserve | Preserve / Sword / Axe / Pickaxe / Bow / Blocks / Food / Pearls / Crystals / Totems / Gapples / Any weapon / Any tool / Empty |
| Greedy layout | Boolean off | Fill missing categories with the next-best valid item |
| Merge stacks | Boolean on | Consolidate partial stacks before cleanup |
| Protect named items | Boolean on | Never discard custom-named stacks |
| Protect enchanted items | Boolean on | Never discard enchanted stacks unless explicitly in Drop list |
| Protect equipped/hotbar | Boolean on | Keep current equipment, offhand, and assigned hotbar slots |
| Keep list | ItemList empty | Always keep; wins over caps and automatic classification |
| Drop list | ItemList empty | Explicit disposable list, but never beats Keep list |
| Excess action | Mode Keep | Keep / Drop |
| Maximum blocks | Number 512; 0–2304 | Cap only when Excess action is Drop |
| Maximum arrows | Number 128; 0–2304 | Includes normal arrows only in v1 |
| Maximum throwables | Number 64; 0–2304 | Snowballs and eggs |
| Maximum food points | Number 512; 0–10000 | Sum of nutrition, not stack count |
| Maximum pearls | Number 64; 0–2304 | Ender pearl cap |
| Maximum crystals | Number 128; 0–2304 | End crystal cap |
| Maximum totems | Number 8; 0–64 | Totem cap |
| Maximum gapples | Number 64; 0–2304 | Golden and enchanted golden apples |
| Low-durability tools | Mode Keep | Keep / Move out of hotbar / Drop |
| Preview | Action | Report planned moves/drops without executing them |

Implementation notes:

- Use InventoryPolicy and CLEANER priority.
- Keep-list precedence is absolute. Drop-list precedence over automatic usefulness applies only after protected/equipped checks.
- Do not absorb AutoArmor or Offhand behavior; their owners decide equipment.

Acceptance:

- [ ] Default config performs zero throw actions.
- [ ] Preview and execution produce the same ordered action plan.
- [ ] Named, equipped, and keep-listed stacks survive every cleanup mode.

## 4. ChestCleaner

- [ ] Add ChestCleaner in PLAYER with SERVER_OBSERVABLE visibility.
- Contract: discard selected contents from the currently open supported container; it does not search for or open containers.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Filter | Mode Blacklist | Blacklist / Whitelist |
| Items | ItemList empty | Blacklist means throw matches; whitelist means throw non-matches |
| Container types | Toggle group; chest, barrel, shulker on | Chest, trapped chest, barrel, shulker box |
| Ignore named containers | Boolean on | Skip custom-titled menus |
| Initial delay | Number 5 ticks; 0–40 | Wait after menu opens |
| Delay | Number 2 ticks; 0–20 | Base throw delay |
| Random delay | Number 1 tick; 0–10 | Added random delay |
| Auto close | Boolean off | Close after no matching container slots remain |
| Close delay | Number 5 ticks; 0–40 | Delay before auto-close |
| Stop when inventory full | Boolean on | Stop even though throwing does not require inventory space, to avoid surprising ChestStealer interaction |
| Preview | Boolean on | Highlight planned removals and require the module key once to confirm |

Implementation notes:

- Use CLEANER priority and THROW clicks only on container-owned slots.
- ChestStealer has LOOT priority. ChestCleaner must not run in the same tick, and its preview state must refresh after any steal.
- QuickStash buttons remain manual and take MANUAL priority.

Acceptance:

- [ ] Player inventory slots can never be thrown by this module.
- [ ] Whitelist/blacklist behavior is tested with renamed and nested shulker items.
- [ ] Closing or changing menus cancels the pending action plan.

## 5. SpeedMine

- [ ] Add SpeedMine in WORLD with SERVER_OBSERVABLE visibility.
- Contract: accelerate the one block the player manually mines. Nuker continues to own area mining, VeinMiner owns connected mining, and Printer owns schematic mining.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Mode | Mode Vanilla | Vanilla / Packet |
| Speed multiplier | Number 1.20; 1.00–2.00 | Scales local break progression in Vanilla mode |
| Finish threshold | Number 0.85; 0.00–1.00 | Packet mode STOP threshold based on predicted progress |
| Instant mine | Boolean on | Immediately finish blocks that vanilla predicts as instant |
| Filter | Mode All | All / Whitelist / Blacklist |
| Blocks | BlockList empty | Used by Whitelist/Blacklist |
| Range | Number 6; 1–8 | Abort targets outside interaction range |
| Auto tool | Boolean on | Request AutoTool before progress calculation |
| Rotation | Mode Off | Off / Silent / Visible |
| Swing | Mode Client | Client / Packet / None |
| Pause while using | Boolean on | Pause while eating/drinking/drawing |
| Abort on slot change | Boolean on | Abort packet target if effective tool changes unexpectedly |
| Reset on target change | Boolean on | Send a correct abort before starting a new target |
| Client remove | Boolean off | Hide only after the shared tracker predicts completion |
| Render target | Boolean on | Delegate progress visuals to BreakIndicators when enabled |

Implementation notes:

- No DoubleMine, Grim, bypass, retry-spam, crystal, or timer modes.
- Extend MultiPlayerGameModeMixin and MiningActionCoordinator.
- AutoTool remains the source of tool selection; SpeedMine must not implement a second tool scorer.
- Replace MinecraftMixin's Printer/VeinMiner mining special case with MiningActionCoordinator.isModuleMining().

Acceptance:

- [ ] Exactly one START/ABORT/STOP lifecycle exists per target.
- [ ] Changing dimension, disconnecting, disabling, or panic clears the target.
- [ ] Nuker, Printer, and VeinMiner cannot run a second mining lifecycle concurrently.

## 6. BreakIndicators

- [ ] Add BreakIndicators in RENDER with CLIENT_ONLY visibility.
- Contract: visualize local and server-reported block-break progress without changing it.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Sources | Toggle group; local and others on | Local vanilla, SpeedMine, other players |
| Shape | Mode Both | Outline / Fill / Both |
| Progress style | Mode Shrink | Shrink / Grow / Static |
| Start side color | Color theme accent low alpha | Fill color at 0% |
| End side color | Color theme accent medium alpha | Fill color at 100% |
| Start line color | Color theme accent | Outline color at 0% |
| End line color | Color warning | Outline color at 100% |
| Line width | Number 1.5; 0.5–5.0 | Outline width |
| Text | Mode Percent | Off / Percent / Time / Tool |
| Through walls | Boolean on | Depth-test toggle |
| Completion fade | Number 250 ms; 0–2000 | Fade after completion/abort |

Implementation notes:

- Local progress comes from MiningTracker; other-player progress comes from the existing block-destruction packet state.
- Do not infer progress from repeated render ticks.

Acceptance:

- [ ] Abort, replacement, chunk unload, and dimension change remove stale indicators.
- [ ] Local progress agrees with SpeedMine's finish threshold.

## 7. HoleESP

- [ ] Add HoleESP in RENDER with CLIENT_ONLY visibility.
- Contract: render safe holes from cached HoleUtil results with a bounded incremental scan.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Horizontal radius | Number 12; 1–32 | Scan radius |
| Vertical radius | Number 6; 1–16 | Scan above/below player |
| Hole types | Toggle group; single/double on | Single / Double / Quad |
| Materials | Toggle group all on | Bedrock / Obsidian-resistant / Mixed |
| Minimum depth | Number 1; 1–3 | Required safe wall depth |
| Minimum headroom | Number 2; 2–3 | Required passable blocks |
| Allow webs | Boolean off | Treat occupied web holes as displayable |
| Ignore own hole | Boolean on | Hide the hole containing the player |
| Shape | Mode Both | Outline / Fill / Both |
| Height | Number 0.25; 0.05–1.00 | Render-box height |
| Top | Boolean off | Draw top face |
| Bottom | Boolean on | Draw bottom face |
| Through walls | Boolean on | Depth-test toggle |
| Distance fade | Boolean on | Fade near scan boundary |
| Bedrock color | Color green | Material color |
| Obsidian color | Color red | Material color |
| Mixed color | Color orange | Material color |

Acceptance:

- [ ] HoleUtil tests cover single/double/quad, mixed walls, webs, headroom, and unsafe floors.
- [ ] Scanner invalidates only affected cache cells on block updates.

## 8. HoleFill

- [ ] Add HoleFill in COMBAT with SERVER_OBSERVABLE visibility.
- Contract: place approved blocks into target-relevant holes through PlacementExecutor.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Blocks | BlockList obsidian | Placement preference order |
| Hole types | Toggle group; single/double on | Single / Double / Quad |
| Mode | Mode Smart | Smart / All in range |
| Target range | Number 8; 1–16 | Target acquisition range |
| Target priority | Mode Closest | Closest / Lowest health / Lowest distance to hole |
| Fill range | Number 5; 1–8 | Placement reach |
| Walls range | Number 3; 0–8 | Reach without line of sight |
| Target-to-hole range | Number 3; 0–8 | Smart-mode activation |
| Predict movement | Boolean on | Project target position |
| Prediction ticks | Number 2; 0–10 | Projection horizon |
| Only moving targets | Boolean off | Require target motion |
| Ignore safe targets | Boolean on | Skip targets already in a safe hole |
| Blocks per tick | Number 2; 1–8 | Placement budget |
| Delay | Number 1 tick; 0–20 | Base placement delay |
| Random delay | Number 0; 0–10 | Added random delay |
| Rotation | Mode Silent | Off / Silent / Visible |
| Rotation speed | Number 180°/tick; 10–180 | RotationManager limit |
| Auto switch | Boolean on | Request a valid block |
| Swap back | Boolean on | Restore displaced slot |
| Swing | Mode Client | Client / Packet / None |
| Air place | Boolean off | Allow only when PlacementSolver approves air place |
| Pause on eat | Boolean on | Shared pause behavior |
| Allow self-fill | Boolean off | Never place in the player's own occupied hole by default |
| Disable when target lost | Boolean off | Optional self-disable |
| Render planned | Boolean on | Placement preview |
| Render placed | Boolean on | Timed confirmation render |
| Planned color | Color theme accent low alpha | Preview color |
| Placed color | Color theme accent | Completion color |

Acceptance:

- [ ] Friends/self are never selected as targets.
- [ ] Every placed position came from HoleUtil and PlacementSolver.
- [ ] Surround and HoleFill respect the shared PLACEMENT budget.

## 9. Burrow

- [ ] Add Burrow in COMBAT with SERVER_OBSERVABLE visibility.
- Contract: perform a vanilla-valid jump/lift and place one approved block at the player's feet; no rubber-band or timer modes.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Blocks | BlockList obsidian, ender chest | Placement preference order |
| Activation | Mode Manual | Manual / Enemy near / Crystal near / Low health |
| Enemy range | Number 5; 1–10 | Automatic enemy trigger |
| Crystal range | Number 4; 1–10 | Automatic crystal trigger |
| Health threshold | Number 12; 1–36 | Automatic health trigger |
| Lift mode | Mode Jump | Jump / Packet lift using vanilla position increments |
| Trigger height | Number 1.00; 0.50–1.20 | Minimum feet clearance before placement |
| Only in hole | Boolean on | Require HoleUtil-safe starting position |
| Center first | Boolean on | Use the existing centering behavior |
| Rotation | Mode Silent | Off / Silent / Visible |
| Auto switch | Boolean on | Request a valid block |
| Swap back | Boolean on | Restore displaced slot |
| Swing | Mode Client | Client / Packet / None |
| Air place | Boolean off | PlacementSolver remains authoritative |
| Delay | Number 0 ticks; 0–10 | Delay after trigger height |
| Self disable | Boolean on | Disable after success or terminal failure |
| Render | Boolean on | Planned/placed position |

Implementation notes:

- Exclude timer, rubber-band, fake-lag, bypass, and server-profile modes.
- Automatic activation must include a cooldown and cannot retrigger while PlacementExecutor owns the request.

Acceptance:

- [ ] No placement occurs if headroom, collision, block, or trigger-height checks fail.
- [ ] Disable/panic restores slot and clears centering/movement requests.

## 10. AutoTrap

- [ ] Add AutoTrap in COMBAT with SERVER_OBSERVABLE visibility.
- Contract: place a selected trap preset around one valid target through the same solver/executor as Surround.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Preset | Mode Head | Head / Face / Anti-step / Full / Custom |
| Custom positions | Toggle group off | Feet sides, waist sides, face sides, top, bottom |
| Blocks | BlockList obsidian | Placement preference order |
| Target range | Number 6; 1–12 | Acquisition range |
| Target priority | Mode Closest | Closest / Lowest health / Lowest armor |
| Predict movement | Boolean on | Project target block position |
| Prediction ticks | Number 1; 0–5 | Projection horizon |
| Only moving targets | Boolean off | Optional filter |
| Place range | Number 5; 1–8 | Placement reach |
| Walls range | Number 3; 0–8 | Reach without line of sight |
| Blocks per tick | Number 2; 1–8 | Shared placement budget |
| Delay | Number 1 tick; 0–20 | Base delay |
| Random delay | Number 0; 0–10 | Added random delay |
| Rotation | Mode Silent | Off / Silent / Visible |
| Rotation speed | Number 180°/tick; 10–180 | RotationManager limit |
| Auto switch | Boolean on | Request a valid block |
| Swap back | Boolean on | Restore displaced slot |
| Swing | Mode Client | Client / Packet / None |
| Air place | Boolean off | PlacementSolver remains authoritative |
| Pause on eat | Boolean on | Shared pause behavior |
| Disable when complete | Boolean off | Optional self-disable |
| Disable when target lost | Boolean on | Prevent blind placement |
| Render planned | Boolean on | Placement preview |
| Render placed | Boolean on | Timed confirmation render |

Acceptance:

- [ ] TargetingUtil friend and validity rules are honored.
- [ ] Custom preset cannot generate duplicate positions.
- [ ] Surround, HoleFill, Burrow, and AutoTrap do not exceed one shared click/rotation/placement grant.

## 11. Offhand

- [ ] Add Offhand in PLAYER with SERVER_OBSERVABLE visibility.
- Contract: provide the baseline and contextual non-totem offhand choice. AutoTotem always has final safety priority.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Normal item | Mode Previous | Previous / Golden apple / Shield / Crystal / Pearl / Custom |
| Custom items | ItemList empty | Preference order for Custom |
| Sword override | Mode Golden apple | Off / Golden apple / Shield |
| Right-click override | Mode Off | Off / Golden apple / Shield |
| Low-health override | Mode Golden apple | Off / Golden apple / Shield |
| Low-health threshold | Number 14; 1–36 | Includes absorption in the safety calculation |
| CrystalAura override | Mode Crystal | Off / Crystal |
| Search hotbar | Boolean on | Allow hotbar source slots |
| Search inventory | Boolean on | Allow main-inventory source slots |
| Switch delay | Number 1 tick; 0–20 | Base delay |
| Restore previous | Boolean on | Restore the first displaced item when an override ends |
| Pause in containers | Boolean on | Do not baseline-swap in non-player menus |
| Notify missing | Boolean on | Throttled missing-item notification |

Implementation notes:

- Baseline requests use REPLENISH, combat overrides use COMBAT, and AutoTotem uses TOTEM.
- Move AutoTotem's fallback setting/behavior here. Add a ConfigManager migration that recreates the old fallback outcome for existing profiles.
- OffhandManager remains the sole swap owner and must remember only the first displaced item across nested overrides.

Acceptance:

- [ ] Priority tests cover totem > contextual override > normal item.
- [ ] Nested overrides restore the original item exactly once.
- [ ] Existing AutoTotem configs retain their fallback behavior after migration.

## 12. ElytraSwap

- [ ] Add ElytraSwap in PLAYER with SERVER_OBSERVABLE visibility.
- Contract: manually swap chestplate/elytra and replace a worn elytra before breakage without fighting AutoArmor.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Swap key | Keybind unbound | Dedicated press action; module toggle remains separate |
| Manual swap mode | Mode Toggle | Toggle / Elytra only / Chestplate only |
| Auto replace | Boolean on | Replace worn elytra from inventory |
| Replace threshold | Number 5 durability; 1–100 | Remaining durability trigger |
| Spare preference | Mode Highest durability | Highest durability / Mending first / First found |
| Ground restore | Boolean off | Restore chestplate after landing |
| Chestplate preference | Mode Best armor | Best armor / First found / Custom |
| Custom chestplates | ItemList empty | Preference order for Custom |
| Delay | Number 1 tick; 0–20 | Inventory action delay |
| Pause while moving | Boolean off | Optional click pause |
| Inventory only | Boolean off | Require player inventory screen |
| Close inventory | Boolean off | Close after successful manual swap |
| Rocket after swap | Mode Off | Off / Held only / Hotbar |
| Warn no spare | Boolean on | Warn before durability reaches zero |

Implementation notes:

- Auto replacement uses ELYTRA_SAFETY and wins over AutoArmor's EQUIPMENT request.
- AutoArmor may restore chest armor only after ElytraSwap reports grounded and safe.
- Reuse EquipmentScorer for chestplate selection.

Acceptance:

- [ ] Never equip a chestplate while actively gliding.
- [ ] Failed swaps do not consume a rocket or lose the original chest item.

## 13. ElytraRecast

- [ ] Add ElytraRecast in MOVEMENT with CONDITIONAL visibility.
- Contract: retry vanilla fall-flying after a legitimate interruption; it is not an exploit/takeoff module.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Trigger | Mode Any interruption | Any interruption / Jump held |
| Retry delay | Number 2 ticks; 0–20 | Delay between vanilla start-flying attempts |
| Maximum attempts | Number 5; 1–20 | Stop retrying after the window |
| Auto jump | Boolean off | Jump from a grounded edge before retrying |
| Minimum fall distance | Number 0.2; 0–3.0 | Require a real fall state |
| Minimum durability | Number 5; 1–100 | Do not recast a worn elytra |
| Allow damaged elytra | Boolean off | If off, require damage below threshold |
| Pause in water | Boolean on | No retry in water |
| Pause with levitation | Boolean on | No retry under levitation |
| Pause on collision | Boolean on | No retry when horizontal collision is active |

Implementation notes:

- Use the vanilla start-fall-flying action only.
- ElytraFly keeps ownership of auto-takeoff and rocket boost. Recast starts only after a previously active glide is interrupted.
- isServerObservableNow() returns true only during the retry window.

Acceptance:

- [ ] No retry on ground, ladder, water, invalid equipment, or after attempts expire.
- [ ] ElytraFly enabled/disabled combinations do not double-send a recast.

## 14. Step

- [ ] Add Step in MOVEMENT with SERVER_OBSERVABLE visibility.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Height | Number 1.0; 0.6–2.5 | Maximum vanilla step height |
| Active when | Mode Always | Always / Sneaking / Not sneaking |
| Safe health | Boolean on | Disable below health threshold |
| Health threshold | Number 8; 1–36 | Includes absorption |
| Pause in liquids | Boolean on | Restore vanilla height |
| Pause while gliding | Boolean on | Restore vanilla height |
| Vehicles | Boolean off | Apply to ridden entities only when enabled |
| Reset delay | Number 0 ticks; 0–20 | Cooldown after a successful step |

Implementation notes:

- Modify the vanilla step-height calculation; no packet/timer modes.
- Reset the overridden value on disable, disconnect, vehicle change, and panic.

Acceptance:

- [ ] Heights above one block still require collision-valid vanilla movement.
- [ ] No permanent entity attribute/state survives disable.

## 15. ReverseStep

- [ ] Add ReverseStep in MOVEMENT with CONDITIONAL visibility.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Fall speed | Number 1.0; 0.1–5.0 | Downward motion request |
| Minimum drop | Number 1.0; 0.5–3.0 | Ignore tiny ledges |
| Maximum drop | Number 3.0; 1.0–10.0 | Do not accelerate unsafe drops |
| Safe landing only | Boolean on | Require a solid collision surface below |
| Pause while sneaking | Boolean on | Preserve deliberate edge control |
| Pause in liquids | Boolean on | No downward request in liquids |
| Pause while gliding | Boolean on | No downward request with elytra |
| Vehicles | Boolean off | Apply while riding |

Implementation notes:

- Request a TRAVEL transform from MovementActionCoordinator.
- isServerObservableNow() is true only while the downward transform is active.

Acceptance:

- [ ] Never accelerates into void, lava, cactus, powder snow, or an unloaded chunk when Safe landing only is on.

## 16. Parkour

- [ ] Add Parkour in MOVEMENT with CONDITIONAL visibility.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Edge distance | Number 0.15; 0.01–0.50 | Trigger distance from the supporting edge |
| Require forward | Boolean on | Require forward input |
| Require sprint | Boolean off | Optional sprint-only activation |
| Safe landing only | Boolean on | Require a reachable support surface |
| Maximum gap | Number 1.0; 1.0–3.0 | Maximum supported jump gap |
| Minimum health | Number 6; 1–36 | Do not auto-jump below threshold |
| Pause while sneaking | Boolean on | Sneak always wins |
| Pause in liquids | Boolean on | No auto-jump in liquids |
| Pause while gliding | Boolean on | No auto-jump with elytra |

Implementation notes:

- Call the vanilla jump action once per edge; do not inject velocity.
- isServerObservableNow() is true only on the automatic jump tick.

Acceptance:

- [ ] One jump per ledge, with a grounded re-arm requirement.
- [ ] No jump into an unloaded or unsafe landing when Safe landing only is on.

## 17. NoPush

- [ ] Add NoPush in MOVEMENT with SERVER_OBSERVABLE visibility.
- Contract: control collision/body push only. Velocity keeps ownership of knockback and explosion scaling.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Player push | Boolean on | Ignore body push from players |
| Mob push | Boolean on | Ignore body push from mobs |
| Vehicle push | Boolean off | Ignore body push from vehicles |
| Block suffocation push | Boolean on | Suppress the inside-block escape push |
| Horizontal only | Boolean on | Preserve vertical collision corrections |
| While sneaking only | Boolean off | Restrict entity-push suppression |

Implementation notes:

- Do not add knockback, explosion, water, piston, or packet-cancel options; those belong to Velocity or vanilla safety rules.
- Prefer one entity push hook with clear source classification.

Acceptance:

- [ ] Velocity behavior is unchanged with NoPush disabled or enabled.
- [ ] Collision boxes and server position correction packets are never canceled.

## 18. SafeWalk

- [ ] Add SafeWalk in MOVEMENT with CONDITIONAL visibility.
- Contract: expose Scaffold's edge-clamp behavior as a standalone module without adding a second Player mixin hook.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Active when | Mode Always | Always / Sneaking / Not sneaking |
| Minimum fall distance | Number 2; 1–10 | Allow stepping down smaller drops |
| Require solid support | Boolean on | Reject fluids and replaceable support |
| Pause while jumping | Boolean on | Do not clamp intentional jumps |
| Scaffold precedence | Mode Scaffold | Scaffold / SafeWalk |
| Render edge | Boolean off | Highlight the clamped support edge |
| Render player box | Boolean off | Show the collision box used for the decision |

Implementation notes:

- Join PlayerMixin.isStayingOnGroundSurface and extend Scaffold.groundSurfaceOverride().
- Scaffold Descend must still allow intentional downward building when Scaffold precedence is selected.
- isServerObservableNow() is true only while the edge clamp changes vanilla behavior.

Acceptance:

- [ ] SafeWalk and Scaffold combinations have table-driven tests.
- [ ] Disable/panic returns the hook to vanilla immediately.

## 19. BetterTab

- [ ] Add BetterTab in RENDER with CLIENT_ONLY visibility.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Maximum players | Number 1000; 1–5000 | Tab-list cap |
| Column height | Number 20; 1–100 | Rows before a new column |
| Sorting | Mode Vanilla | Vanilla / Ping / Name length / Display-name length / Alphabetical / Reverse alphabetical / None |
| Header/footer | Mode Show | Show / Hide |
| Names | Mode Full | Full / Name only |
| Gamemode | Mode Vanilla | Vanilla / Icon / Text / Hide |
| Latency | Mode Bars | Bars / Exact / Both / Hide |
| Latency suffix | String ms | Exact-latency suffix |
| Highlight self | Boolean on | Apply configured self background |
| Self color | Color theme accent low alpha | Self background |
| Highlight friends | Boolean on | Apply friend background |
| Friend color | Color friend low alpha | Friend background |
| Highlight regex | StringList empty | Java regular expressions matched against plain profile/display name |
| Regex color | Color warning low alpha | Regex-match background |
| Hide regex | StringList empty | Hide matching entries after friend/self checks |
| Show heads | Boolean on | Preserve player heads |
| Show server score | Boolean on | Preserve server-provided score display |

Implementation notes:

- Extend PlayerTabOverlayMixin. Preserve styled Components, friend/unlucky markers, face badges, and the existing marker gap.
- Invalid regex entries must be disabled with one notification rather than throwing during render.

Acceptance:

- [ ] Sorting is stable when keys tie.
- [ ] Self/friend markers, badge placement, exact latency, score, and hide filters compose correctly.

## 20. ItemESP

- [ ] Add ItemESP in RENDER with CLIENT_ONLY visibility.
- Contract: add filtering, labels, tracers, and value/rarity styling for dropped items. Shader remains the only silhouette owner.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Mode | Mode Labels | Labels / Tracers / Labels and tracers |
| Filter | Mode All | All / Whitelist / Blacklist |
| Items | ItemList empty | Used by Whitelist/Blacklist |
| Range | Number 128; 8–512 | Entity render range |
| Minimum age | Number 0 ticks; 0–200 | Hide newly dropped items |
| Show name | Boolean on | Item display name |
| Show count | Boolean on | Stack count |
| Show distance | Boolean off | Distance suffix |
| Show age | Boolean off | Entity age suffix |
| Text shadow | Boolean on | Label shadow |
| Text scale | Number 1.0; 0.5–2.0 | Label scale |
| Color mode | Mode Rarity | Fixed / Rarity / Item / Theme |
| Fixed color | Color theme accent | Used by Fixed |
| Tracer origin | Mode Bottom | Bottom / Crosshair |
| Tracer width | Number 1.0; 0.5–5.0 | Line width |
| Through walls | Boolean on | Label/tracer depth toggle |
| Silhouette | Boolean off | Delegate eligible items to Shader's item pass |

Implementation notes:

- Do not add a second framebuffer, glow mask, or item entity pass.
- ItemPhysics continues to own dropped-item model orientation.

Acceptance:

- [ ] Filter result is identical for labels, tracers, and delegated silhouettes.
- [ ] Large item piles respect entity/range caps without unbounded label overlap work.

## 21. Breadcrumbs

- [ ] Add Breadcrumbs in RENDER with CLIENT_ONLY visibility.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Source | Mode Player | Player / Freecam / Both |
| Maximum points | Number 2000; 100–20000 | Ring-buffer cap per source |
| Minimum point distance | Number 0.25; 0.05–5.0 | Sampling threshold |
| Maximum age | Number 0 seconds; 0–3600 | Zero keeps points until capacity/clear |
| Line width | Number 1.5; 0.5–5.0 | Trail width |
| Through walls | Boolean on | Depth-test toggle |
| Color mode | Mode Theme | Static / Theme / Rainbow / Speed |
| Static color | Color theme accent | Used by Static |
| Fade oldest | Boolean on | Alpha fade across retained points |
| Clear on teleport | Boolean on | Clear on large discontinuity |
| Clear on dimension change | Boolean on | Never connect dimensions |
| Clear on disable | Boolean off | Retain trail while toggled |
| Clear | Action | Clear all current trails |

Acceptance:

- [ ] Teleports and dimension changes never draw a world-spanning segment.
- [ ] Memory remains bounded at configured capacity.

## 22. BlockOutline

- [ ] Add BlockOutline in RENDER with CLIENT_ONLY visibility.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Box mode | Mode Vanilla shape | Vanilla shape / Full cube / Selected face |
| Shape | Mode Outline | Outline / Fill / Both |
| Color mode | Mode Theme | Static / Theme / Rainbow / Block color |
| Side color | Color theme accent low alpha | Fill color |
| Line color | Color theme accent | Outline color |
| Line width | Number 1.5; 0.5–5.0 | Outline width |
| Through walls | Boolean off | Depth-test toggle |
| Hide when inside | Boolean on | Hide if camera is inside the selected shape |
| Show fluids | Boolean off | Allow fluid hit results |
| Distance fade | Boolean on | Fade near interaction-range limit |
| Placement face marker | Boolean off | Highlight the exact placement face |

Implementation notes:

- Replace/cancel vanilla selected-block outline at one existing render point; do not render both.
- Use the actual collision/outline voxel shape for Vanilla shape.

Acceptance:

- [ ] Non-full blocks, multipart voxel shapes, fluids, and camera-inside behavior are tested.

## 23. ViewModel

- [ ] Add ViewModel in RENDER with CLIENT_ONLY visibility.
- Contract: alter first-person hand/item transforms only; never change gameplay rotation or reach.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Sync hands | Boolean off | Mirror main-hand settings to offhand |
| Main-hand position | 3 numbers 0; -2.0–2.0 | X/Y/Z translation |
| Main-hand scale | 3 numbers 1; 0.1–3.0 | X/Y/Z scale |
| Main-hand rotation | 3 numbers 0; -180–180 | X/Y/Z degrees |
| Offhand position | 3 numbers 0; -2.0–2.0 | X/Y/Z translation |
| Offhand scale | 3 numbers 1; 0.1–3.0 | X/Y/Z scale |
| Offhand rotation | 3 numbers 0; -180–180 | X/Y/Z degrees |
| Equip progress | Number 1.0; 0.0–1.0 | Equip-offset multiplier |
| Skip swap animation | Boolean off | Suppress slot-change equip dip |
| Swing mode | Mode Vanilla | Vanilla / Old / Slash / None |
| Swing speed | Number 1.0; 0.1–3.0 | Visual-only animation speed |
| Old block animation | Boolean off | Legacy blocking transform |
| Eat animation | Mode Vanilla | Vanilla / Reduced / Hidden |
| Drink animation | Mode Vanilla | Vanilla / Reduced / Hidden |
| Bow animation | Mode Vanilla | Vanilla / Reduced / Hidden |
| Eat position | 3 numbers 0; -2.0–2.0 | Additional eating translation |
| Eat rotation | 3 numbers 0; -180–180 | Additional eating rotation |
| Apply server rotations | Boolean off | If enabled, visually follow RotationManager; off by default |

Acceptance:

- [ ] Main/offhand, maps, bows, food, shields, empty hand, and FOV changes render without matrix leakage.
- [ ] Module state cannot modify packet yaw/pitch, hit result, reach, or swing timing.

## 24. HitEffects

- [ ] Add HitEffects in RENDER with CLIENT_ONLY visibility.
- Contract: render non-numeric effects from confirmed HealthChangeTracker events. HealthIndicators keeps sole ownership of floating numbers.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Effect | Mode Particles | Particles / Rings / Sparks |
| Events | Mode Damage | Damage / Heal / Both |
| Entities | Toggle group; players and mobs on | Self / Players / Mobs |
| Own hits only | Boolean off | Damage must be attributable to the local player |
| Amount | Number 8; 1–50 | Spawn count per event |
| Lifetime | Number 750 ms; 100–5000 | Effect lifetime |
| Speed | Number 0.10; 0.01–1.00 | Initial velocity |
| Scale | Number 1.0; 0.1–5.0 | Visual scale |
| Gravity | Number 0.03; -0.20–0.20 | Per-tick acceleration |
| Physics | Boolean on | Bounce/settle against world collision |
| Color mode | Mode Damage/heal | Static / Damage-heal / Theme / Rainbow |
| Damage color | Color red | Damage event color |
| Heal color | Color green | Heal event color |
| Static color | Color theme accent | Static color |
| Sound | Mode Off | Off / Hit / Pling |
| Maximum live effects | Number 256; 32–1000 | Hard memory/render cap |

Acceptance:

- [ ] One confirmed health change creates one event regardless of duplicate packet paths.
- [ ] Effects expire and clear on world change; cap is enforced under combat spam.

## 25. AntiAFK

- [ ] Add AntiAFK in PLAYER with SERVER_OBSERVABLE visibility.
- Contract: perform bounded, reversible vanilla inputs after real user inactivity.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Activation | Mode After idle | Always / After idle |
| Idle time | Number 5 minutes; 1–120 | Time without keyboard/mouse input |
| Mode | Mode Random | Random / Custom |
| Minimum interval | Number 5 seconds; 1–300 | Random action interval floor |
| Maximum interval | Number 15 seconds; 1–600 | Random action interval ceiling |
| Actions | Toggle group; swing, yaw, jump on | Swing / Yaw / Pitch / Jump / Sneak / Strafe / Change slot |
| Custom swing | Boolean on | Custom-mode action |
| Custom jump | Boolean off | Custom-mode action |
| Custom sneak | Boolean off | Custom-mode action |
| Custom rotate | Boolean on | Custom-mode action |
| Custom strafe | Boolean off | Custom-mode action |
| Rotation amount | Number 30°; 1–180 | Maximum custom/random yaw/pitch change |
| Movement radius | Number 2 blocks; 0–16 | Maximum displacement from activation origin |
| Return to origin | Boolean on | Walk back within tolerance; no pathfinding |
| Safe movement | Boolean on | Require loaded, collision-safe, supported destination |
| Messages | Boolean off | Optional chat messages |
| Message list | StringList empty | Random message pool |
| Message interval | Number 15 minutes; 1–120 | Minimum message spacing |
| Stop on user input | Boolean on | Immediately release all owned inputs |
| Pause in GUI | Boolean on | No actions in screens |
| Pause in combat | Boolean on | Pause when recently damaged or a combat module is active |
| Pause in danger | Boolean on | Pause on low health, fire, void risk, or elytra flight |

Implementation notes:

- Use InputActionCoordinator; Change slot also uses InventoryActionCoordinator.
- Preserve original yaw/pitch/selected slot and restore only state this module changed.
- No command execution option in v1.

Acceptance:

- [ ] Disable, panic, disconnect, screen open, and real input release all synthetic keys in the same tick.
- [ ] Safe movement never steps into an unloaded chunk or unsupported drop.

## 26. AutoWalk

- [ ] Add AutoWalk in MOVEMENT with SERVER_OBSERVABLE visibility.
- Contract: hold a chosen direction with simple safety stops. It must not search, route, mine, bridge, or become Baritone.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Direction | Mode Forward | Forward / Backward / Left / Right |
| Lock heading | Boolean off | Maintain activation yaw through RotationManager visible requests |
| Auto sprint | Boolean on | Hold sprint when moving forward |
| Auto jump | Boolean off | Vanilla jump when horizontal collision has a safe landing |
| Avoid hazards | Boolean on | Stop before lava, fire, cactus, powder snow, void, and unsafe drops |
| Avoid unloaded chunks | Boolean on | Stop at unloaded boundaries |
| Stop on user input | Boolean on | Disable when conflicting manual input occurs |
| Stop on Y change | Boolean off | Disable after vertical displacement |
| Stop on collision | Boolean off | Disable rather than auto-jump |
| Stop on low food | Boolean on | Disable below food threshold |
| Food threshold | Number 6; 0–20 | Low-food stop threshold |
| Stop on low health | Boolean on | Disable below health threshold |
| Health threshold | Number 6; 1–36 | Includes absorption |
| Stop on elytra warning | Boolean on | Disable when worn elytra reaches ElytraSwap threshold |
| Pause in GUI | Boolean on | Release owned keys in screens |

Implementation notes:

- Use InputActionCoordinator. Do not set key states directly.
- Lock heading is a visible rotation request and yields to functional/placement rotations.
- No Goal, Follow, Highway, pathfinding, block-breaking, or auto-scaffold modes.

Acceptance:

- [ ] No synthetic key remains held after every stop/disable path.
- [ ] Safety checks stop before, not after, crossing an unloaded or hazardous edge.

## 27. AutoCraft

- [ ] Add AutoCraft in PLAYER with SERVER_OBSERVABLE visibility.
- Contract: craft configured recipe-book items only while the player has a supported crafting menu open.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Items to craft | ItemList empty | Ordered output preference list |
| Recipe priority | Mode List order | List order / Most craftable |
| Craft mode | Mode Single | Single / Stack |
| Sequential crafting | Boolean off | Permit outputs to feed later configured recipes |
| Supported menus | Toggle group; inventory and table on | Player inventory / Crafting table |
| Delay | Number 2 ticks; 0–20 | Base craft delay |
| Random delay | Number 1 tick; 0–10 | Added random delay |
| Maximum crafts | Number 0; 0–10000 | Zero means until a stop condition |
| On full inventory | Mode Wait | Wait / Close / Disable / Drop output |
| Stop out of ingredients | Boolean on | Stop when no configured recipe is craftable |
| Auto close | Boolean off | Close after terminal completion |
| Pause on eat | Boolean on | Shared pause behavior |
| Protect reserved items | Boolean on | Do not consume armor, offhand, hotbar assignments, or InventoryPolicy keep-list items |
| Queue status | Boolean on | Show current output, remaining cap, and stop reason |

Implementation notes:

- Use vanilla recipe/menu interactions and RecipeAutomation at AUTOMATION priority.
- Never open a crafting table, move the player, or discover recipes automatically.
- Sequential mode must detect recipe cycles and stop with a notification.

Acceptance:

- [ ] Unsupported/closed menu cancels pending clicks.
- [ ] Inventory-full modes and craft caps are deterministic.
- [ ] Sequential recipe cycles cannot loop indefinitely.

## 28. AutoSmelt

- [ ] Add AutoSmelt in WORLD with SERVER_OBSERVABLE visibility.
- Contract: load fuel/input and collect output only from the furnace menu the player currently has open.

| Option | Type and default | Choices / behavior |
|---|---|---|
| Furnace types | Toggle group all on | Furnace / Blast furnace / Smoker |
| Filter | Mode Whitelist | Whitelist / Blacklist / All smeltable |
| Smeltables | ItemList empty | Input filter |
| Fuels | ItemList coal, charcoal | Ordered fuel preference list |
| Input batch | Number 64; 1–64 | Maximum input stack target |
| Fuel refill count | Number 16; 1–64 | Target fuel-slot count |
| Keep fuel reserve | Number 0; 0–2304 | Never consume below reserve |
| Collect output | Boolean on | Quick-move output when space exists |
| Delay | Number 2 ticks; 0–20 | Base menu action delay |
| Random delay | Number 1 tick; 0–10 | Added random delay |
| On missing input | Mode Wait | Wait / Close / Disable |
| On missing fuel | Mode Wait | Wait / Close / Disable |
| On full inventory | Mode Wait | Wait / Close / Disable |
| Auto close complete | Boolean off | Close when no valid input/output work remains |
| Pause on eat | Boolean on | Shared pause behavior |
| Queue status | Boolean on | Show input, fuel estimate, output, and stop reason |

Implementation notes:

- Use RecipeAutomation and AUTOMATION priority.
- Do not search for, open, place, or refuel nearby furnaces without the player opening the menu.
- Respect furnace-specific recipes; do not place an input into an incompatible blast furnace or smoker.

Acceptance:

- [ ] Each furnace type rejects incompatible inputs.
- [ ] Fuel reserve and output-full behavior are unit tested.
- [ ] Menu close/change cancels every pending action.

---

## Delivery order

- [ ] Phase 0 — shared owners and utilities: InputActionCoordinator, MiningActionCoordinator/Tracker, PlacementExecutor, HoleUtil, EquipmentScorer, InventoryPolicy, HealthChangeTracker, RecipeAutomation, persistence.
- [ ] Phase 1 — observation/render base: StashFinder, BreakIndicators, HoleESP.
- [ ] Phase 2 — inventory safety: AutoArmor, InventoryCleaner, ChestCleaner, Offhand.
- [ ] Phase 3 — mining: SpeedMine and migration of Printer/VeinMiner/Nuker mining state.
- [ ] Phase 4 — combat placement: HoleFill, Burrow, AutoTrap, plus Surround/Scaffold migration to PlacementExecutor.
- [ ] Phase 5 — movement/equipment: ElytraSwap, ElytraRecast, Step, ReverseStep, Parkour, NoPush, SafeWalk.
- [ ] Phase 6 — visual polish: BetterTab, ItemESP, Breadcrumbs, BlockOutline, ViewModel, HitEffects.
- [ ] Phase 7 — bounded automation: AntiAFK, AutoWalk, AutoCraft, AutoSmelt.

## Definition of done for every module

- [ ] Registered once in ModuleManager and appears in the intended category.
- [ ] Accurate visibility classification; CONDITIONAL modules implement and test isServerObservableNow().
- [ ] All options above use existing Setting classes and survive config save/load.
- [ ] No direct inventory click, offhand swap, key hold, velocity transform, placement, or rotation bypasses its shared owner.
- [ ] Disable, panic, disconnect, dimension change, and null player/world states are safe.
- [ ] Existing ModuleSmokeTest passes with the module alone and with every module enabled.
- [ ] Add focused tests for settings boundaries, owner priority, config migration, and any cache/persistence format.
- [ ] Run ./gradlew compileJava and ./gradlew runClientGameTest.
- [ ] Update README.md, ARCHITECTURE.md, module count, and relevant command/help text.
- [ ] Verify manually in singleplayer and on a vanilla-compatible test server; document any behavior that is intentionally server-observable.

## References

Current codebase:

- [Architecture](https://github.com/lucieneth/UnluckyUtilityMod/blob/main/ARCHITECTURE.md)
- [ModuleManager](https://github.com/lucieneth/UnluckyUtilityMod/blob/main/src/client/java/unlucky/utility/client/module/ModuleManager.java)
- [InventoryActionCoordinator](https://github.com/lucieneth/UnluckyUtilityMod/blob/main/src/client/java/unlucky/utility/client/util/InventoryActionCoordinator.java)
- [OffhandManager](https://github.com/lucieneth/UnluckyUtilityMod/blob/main/src/client/java/unlucky/utility/client/util/OffhandManager.java)
- [MovementActionCoordinator](https://github.com/lucieneth/UnluckyUtilityMod/blob/main/src/client/java/unlucky/utility/client/util/MovementActionCoordinator.java)
- [RotationManager](https://github.com/lucieneth/UnluckyUtilityMod/blob/main/src/client/java/unlucky/utility/client/util/RotationManager.java)
- [PlacementSolver](https://github.com/lucieneth/UnluckyUtilityMod/blob/main/src/client/java/unlucky/utility/client/util/PlacementSolver.java)
- [Module smoke test](https://github.com/lucieneth/UnluckyUtilityMod/blob/main/src/gametest/java/unlucky/utility/test/ModuleSmokeTest.java)

Comparison references used for option coverage:

- [Meteor module sources](https://github.com/MeteorDevelopment/meteor-client/tree/master/src/main/java/meteordevelopment/meteorclient/systems/modules)
- [ThunderHack module sources](https://github.com/Pan4ur/ThunderHack-Recode/tree/main/src/main/java/thunder/hack/features/modules)
- [LiquidBounce AutoCrafter](https://liquidbounce.net/docs/modules/player/autocrafter)
- [LiquidBounce InventoryCleaner](https://liquidbounce.net/docs/modules/player/inventorycleaner)
- [LiquidBounce ChestCleaner](https://liquidbounce.net/docs/modules/player/chestcleaner)
- [LiquidBounce PacketMine](https://liquidbounce.net/docs/modules/world/packetmine)
- [LiquidBounce BetterTab](https://liquidbounce.net/docs/modules/render/bettertab)
- [LiquidBounce AntiAFK](https://liquidbounce.net/docs/modules/player/antiafk)
