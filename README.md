![visitors](https://visitor-badge.laobi.icu/badge?page_id=lucieneth.unluckyutilitymod&left_text=visitors&format=true&logo=github)

# Unlucky Client

A visuals-first Minecraft utility client for Fabric — **pretty above all else**.
Two ClickGUI styles (Skeet and Future), fully draggable custom HUD, an ESP suite, XRay, movement
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
open pickers with live previews. Pick the renderer in **Theme -> ClickGUI
style**: Skeet keeps the category tabs, Future puts every category on screen at
once with panel glass. The icon bar up top switches between the ClickGUI, HUD
editor, Friends and Configs, and works from the title screen too. Everything
saves to `config/unlucky/config.json`.

**HUD editor:** drag widgets anywhere; they snap to edges, centres and each
other, with a placement grid and safe-area guides for the chat, hotbar, boss
bars and potion icons (hold `Ctrl` for pixel-exact placement). Right-click a
widget for its settings popup; the central panel toggles widgets on and off, and
the side rail aligns, locks, hides, resets or **duplicates** the selected widget
— a copy is independent and keeps its own settings and position.

## Modules

**Render** — PlayerESP (shader silhouette, CS-style 2D boxes with HP/armor
bars, skeleton, tracers), MobESP (hostile/neutral/passive), StorageESP,
Chams (see-through tinted silhouettes), XRay (block picker, presets,
fullbright ores), Freecam, ElytraPhysics (cape-like elytra sway), Zoom,
Fullbright, NoFog (distance / Nether / End), NoHurtCam, AutoDrawDistance
(holds an FPS target), NoWeather, ViewClip (third-person camera through
walls), NoRender (fire/water/pumpkin overlays, totem animation, boss bars,
break particles, and situational fog: water, lava, powder snow, blindness,
darkness), NameTags (rich billboards: gamemode/health/ping/distance, armor
with enchants, the below-name scoreboard restyled), Heads (player heads in
chat and on the locator bar), FoodOverlay (full AppleSkin: saturation,
exhaustion, restore previews, food tooltips — resource packs can reskin via
`assets/unlucky/textures/gui/sprites/food/`), Trajectories (held, remote and fired
projectile paths with impact/entity highlighting), PearlChecker (labels pearls, predicts the landing), NBTTooltip (raw
data components, copyable from the tooltip)

**Combat** — Aura, LegitAimbot (gentle visible aim assistance that preserves mouse input),
TriggerBot, AutoClicker, TargetStrafe, AutoTotem (holds a
totem when something is about to kill you — not just when your health is
already low: it counts the fall you're committed to and the crystals in range),
AutoLog (leaves before that happens), Criticals,
LegitMaceKill (amplifies a real fall), BlatantMaceKill (spoofs one),
MaceCombo (chains smashes with wind charges). Rotations are silent: the body
turns while your camera stays free.

**Movement** — ElytraFly, BoatFly, EntitySpeed, EntityControl,
RocketMan (easy firework flight), FakeFly,
CreativeFlight, Jetpack, Speed, BunnyHop, Velocity (anti-knockback),
NoJumpDelay, RocketJump, Updraft, AutoSprint (omni), RoadTrip (AFK travel
safeties), AFKVanillaFly, NoFall, AntiLevitation (ignore shulker levitation),
Yaw (lock your facing), Jesus (walk on water), TridentFly (riptide without
rain), ClickTP (teleport to the block you click), EventlessFly,
WindChargeJump, Phase (move through blocks)

**World** — Search (find any block, saved presets), Nuker, TreasureESP
(buried chests), VanityESP (maparts + banners), Archaeology (suspicious
blocks), VeinMiner (break one ore, get the whole vein — follows deepslate
variants too), Scaffold (Bridge, Tower and safe one-block Descend), ChatSigns,
BannerData, AutoDoors (with close-behind), AutoFarm,
AutoWither, ObsidianFarm, BlockAirPlace, WaxAura, VillagerRoller (rerolls
librarian books)

**Player** — Capes (custom capes, streamed — see below), AutoExtinguish,
AutoXPRepair, PagePirate (reads books around you), Honker, AntiHunger,
FastUse (no right-click delay), AutoEat (with a food blacklist), AutoFish,
AutoTool (swaps to the best tool for the block, with Silk Touch for ender
chests and an anti-break floor), AutoReplenish (refills a hotbar stack before
it runs out, keeping the exact item variant),
HotbarLoadout (restores a saved creative hotbar — Ctrl+1..9 — into survival,
full components intact), DonkeyRitual (the same thing as a performance: feed
a chested donkey filler blocks, kill it, and it drops the hotbar),
InfiniteInteract (reach distant blocks and entities)

**Misc** — AutoReconnect (puts you back on the server you fell off, and knows
the difference between a kick and you choosing to leave), Panic (bind one key: **Minimal** turns off everything the server
can currently see and leaves your ESP, chat and HUD running; **All** turns off
everything that can be turned off), HUD, Theme (live accent recolor + menu blur), Friends
(middle-click players; dots in tablist/nametags/chat/locator, chibi sprite
icons in the Friends GUI, optional green self-dot), InventoryInfo (tooltip
previews: containers, ender chest, maps, banners, books, byte size),
BetterChat (timestamps, collapses repeated lines into one with a counter,
hide/highlight filtering with plain or regex patterns), AdBlocker,
AntiToS (word blacklist: `config/unlucky-antitos.txt`), BookTools,
SoundLocator, Spinbot (visual-only, CS:GO style). Plus a CS:GO-style console
(`;`) and an in-game **skin & cape changer** on the title screen (real
account changes via Mojang's API, not spoofed).

## HUD widgets

Watermark, ArrayList (animated gradient), TargetHUD (live model, health,
gear + enchants, potions), Keystrokes (with CPS), ArmorHUD, PotionHUD,
Coords (with cross-dimension line), Speedometer (with sparkline), Radar,
InventoryViewer, PlayerModel, ItemCounter, ItemPickup notifier, PopCounter,
SessionInfo (kills/deaths/K-D), Info (FPS/ping/TPS/time rows), CompassBar
(with player heads), CustomText, Greeter, Brewing (AutoBrew read-out), Printer,
Materials and Layers. Every widget carries its own settings — layout, colors,
scale, opacity, anchor and enter/leave animation — on top of the shared panel
styling in the HUD module. Module toggles announce through native Minecraft
toasts.

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
