![visitors](https://visitor-badge.laobi.icu/badge?page_id=lucieneth.unluckyutilitymod&left_text=visitors&format=true&logo=github)

# Unlucky Client

A visuals-first Minecraft utility client for Fabric — **pretty above all else**.
Skeet-style ClickGUI, fully draggable custom HUD, an ESP suite, XRay, movement
and combat modules, and a pile of quality-of-life tools.

- Minecraft **26.2** / Fabric Loader **0.19.3+** / **Fabric API** / Java **25**
- Mod id: `unlucky` · License: **CC0-1.0**

> **Fair warning:** this is a cheat client. It is only meant for **singleplayer
> and anarchy servers**. There is **no anticheat bypass** and there never will
> be — don't take it somewhere it isn't welcome and then act surprised.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.2.
2. Grab the jar from the
   [Releases page](https://github.com/lucieneth/UnluckyUtilityMod/releases) and
   drop it plus [Fabric API](https://modrinth.com/mod/fabric-api) into your
   `mods` folder.
3. Launch — the window title says `Unlucky Client` when it's loaded.

## Controls

| Key | Action |
| --- | --- |
| `Right Shift` | Open the ClickGUI |
| `Right Ctrl` | Open the HUD editor (drag widgets, right-click for settings) |
| `C` (hold) | Smooth zoom (Zoom module) |
| `G` | RocketJump (when enabled) |

Any module can be bound to a key from its **Bind** row in the ClickGUI.

**ClickGUI:** pick a category tab in the sidebar, check **Enabled** in a
module's group box, scroll when a tab overflows. Color swatches expand into
Hue/Sat/Val pickers, mode settings open dropdowns, and block/mob list settings
open pickers with live previews. The icon bar up top switches between the
ClickGUI and the HUD editor. Everything saves to `config/unlucky.json`.

**HUD editor:** drag widgets anywhere; they snap and justify to whichever
screen edge they hug. Right-click a widget for its settings popup; the central
panel toggles widgets on and off.

## Modules

**Render** — PlayerESP (shader silhouette, CS-style 2D boxes with HP/armor
bars, skeleton, tracers), MobESP (hostile/neutral/passive), StorageESP,
Chams (see-through tinted silhouettes), XRay (block picker, presets,
fullbright ores), Freecam, ElytraPhysics (cape-like elytra sway), Zoom,
Fullbright, NoFog, NoHurtCam, AutoDrawDistance (holds an FPS target)

**Combat** — Aura, TriggerBot, AutoClicker, TargetStrafe. Rotations are
silent: the body turns while your camera stays free.

**Movement** — ElytraFly, RocketMan (easy firework flight), FakeFly,
CreativeFlight, Jetpack, Speed, BunnyHop, Velocity (anti-knockback),
NoJumpDelay, RocketJump, Updraft, AutoSprint (omni), RoadTrip (AFK travel
safeties), AFKVanillaFly

**World** — TreasureESP (buried chests), VanityESP (maparts + banners),
Archaeology (suspicious blocks), ChatSigns, BannerData, AutoDoors (with
close-behind), AutoFarm, AutoWither, ObsidianFarm, BlockAirPlace, WaxAura

**Player** — Capes (custom capes, streamed — see below), AutoExtinguish,
AutoXPRepair, PagePirate (reads books around you), Honker

**Misc** — HUD, Theme (live accent recolor + menu blur), AdBlocker,
AntiToS (word blacklist: `config/unlucky-antitos.txt`), BookTools,
SoundLocator, Spinbot (visual-only, CS:GO style)

## HUD widgets

Watermark, ArrayList (animated gradient), TargetHUD (live model, health,
gear + enchants, potions), Keystrokes (with CPS), ArmorHUD, PotionHUD,
Coords (with cross-dimension line), Speedometer (with sparkline), Radar,
InventoryViewer, PlayerModel, ItemCounter, ItemPickup notifier, PopCounter,
SessionInfo (kills/deaths/K-D), Info (FPS/ping/TPS/time rows), CustomText,
Greeter. Module toggles announce through native Minecraft toasts.

## Capes

The Capes module streams capes instead of bundling them — official capes come
from Mojang's own servers, community capes come live from the
[Capes repo](https://github.com/lucieneth/Capes). Add a PNG there and it shows
up in everyone's picker on next launch, no client update needed. Everything is
cached in `config/unlucky/capes/` for offline use. Capes are client-side:
only you see them.

## Development

```
run.bat             # double-click dev launcher (Windows)
gradlew runClient   # launch a dev client
gradlew build       # build the jar into build/libs/
build.bat           # Windows: build + copy the jar to the repo root
gradlew genSources  # decompiled MC sources for API reference
```

Requires JDK 25. Local builds are always versioned `dev`
(`unlucky-dev.jar`); real version numbers only exist on releases, which CI
cuts automatically when a `v*` tag is pushed (e.g. `git tag v1.1 &&
git push origin v1.1`). Architecture is Meteor-style: `module/` (modules + manager),
`settings/` (typed settings), `gui/clickgui/` (skeet window + components),
`gui/hud/` (widgets + editor), `util/` (Render2D/Render3D, world scan,
interaction), `mixin/` (game hooks). ESP shapes ride on the vanilla gizmo
system; entity outlines use the vanilla glow pipeline. See
[ARCHITECTURE.md](ARCHITECTURE.md) for the full map — every mixin, module,
and the hard-won 26.2 API notes.

## Notes

- BookTools § stripping on some servers is a vanilla server-side limit, not a
  bug (works fine on anarchy).
- Config lives in `config/unlucky.json`, saved automatically on exit.
