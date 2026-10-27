# testing

Shortcuts for running a slice of the client gametests instead of the whole
`runClientGameTest` suite (about 2.5 minutes). Double-click one, or run it from the
repo root. Each one prints `PASS` or `FAIL`, exits with Gradle's exit code and saves
its output to `build\test-<filter>.log`.

| Script | Runs |
| --- | --- |
| `test-compile.bat` | `compileClientJava compileGametestJava` only, no client launched. The fast check. |
| `test-all.bat` | Everything, same as a plain `gradlew runClientGameTest`. |
| `test-modules.bat` | Every module, the all-at-once pass and the panic sweep. No screens or input. |
| `test-combat.bat` | Combat modules, plus the contracts that cover them. |
| `test-player.bat` | Player modules. |
| `test-movement.bat` | Movement modules. |
| `test-render.bat` | Render modules. |
| `test-world.bat` | World modules. |
| `test-misc.bat` | Misc modules. |
| `test-client.bat` | Client modules (theme, HUD, friends, capes). |
| `test-module.bat <Name>` | One module by name, ignoring case, e.g. `test-module.bat AutoTrap`. Asks for the name if you leave it out. |
| `test-ui.bat` | `ScreenSmokeTest` and the ClickGUI button checks. |
| `test-panic.bat` | Visibility metadata and the panic sweep. |
| `test-input.bat` | `InputTest`: keys, binds, mouse buttons, text input. |

Arguments after the filter go to Gradle, so `test-combat.bat -PwithSodium` runs the
combat slice with Sodium loaded.

A window opened by double-clicking pauses at the end so you can read the result. Set
`NOPAUSE=1` to turn that off.

## The filter itself

The scripts are wrappers around one system property, read by
`src/gametest/java/unlucky/utility/test/TestFilter.java`:

```bash
gradlew runClientGameTest -Dunlucky.test=combat,module:Freecam
```

Values: `all` (the default), `modules`, a category (`combat`, `player`, `movement`,
`render`, `world`, `misc`, `client`), `module:<Name>`, `ui` or `screens`, `panic`, `input`,
or a comma-separated list of them. The `UNLUCKY_TEST` environment variable works too.
An unknown value or a module name that matches nothing fails the run, so a typo can't
pass as a run that tested nothing.

A narrow run skips the shared steps it doesn't need. A category or single-module run
builds the scene and runs only the module tests that cover its modules. It skips the mixin
audit, the derived-group checks, the all-at-once pass and the panic sweep.

## Module tests

The sweep only checks that each module turns on, renders and turns off without
throwing. The tests that drive specific modules in the world and check what they
actually do live in `src/gametest/java/unlucky/utility/test/module/`, one class each:

| Class | Covers |
| --- | --- |
| `TargetingTest` | The whole combat category, plus AntiVoid, ChestStealer, NoRotate |
| `MovementTest` | ElytraRecast, ElytraSwap, NoPush, Parkour, ReverseStep, SafeWalk, Step |
| `VisualsTest` | BetterTab, BlockOutline, Breadcrumbs, HitEffects, ItemESP, Tracers, ViewModel |
| `RainbowEnchantTest` | RainbowEnchant |
| `AutomationTest` | AntiAFK, AutoCraft, AutoSmelt, AutoWalk |
| `HeldAttacksTest` | Criticals, BlatantMaceKill |
| `InfiniteInteractTest` | InfiniteInteract |
| `ElytraMaceTest` | ElytraMace |

`ModuleSmokeTest` runs them in the shared world, after the scene is built and before
the sweep, in the order listed in `ModuleTests.ALL`. A test runs when the filter
selects any module in its `covers()` list, or its `category()` if it has one. So
`test-module.bat Step` runs `MovementTest`, and `test-combat.bat` runs `TargetingTest`,
`HeldAttacksTest` and `ElytraMaceTest`.

To add one:

1. Create a class in `test/module` that implements `ModuleTest`. `covers()` lists the
   modules it tests, and `run(context, server)` does the work. Throw an `AssertionError`
   when a check fails.
2. Shared helpers are in `ModuleTestSupport`: `LOGGER`, `DWELL`, `summonAt`,
   `summonHeldTarget`, `attack`, `serverHealth`, `damageTaken`, `serverDrift` and `stackOf`.
3. Add it to `ModuleTests.ALL`. Put anything that kills the scene's mobs or changes the
   game mode after the tests that need them, which means after `HeldAttacksTest`.
