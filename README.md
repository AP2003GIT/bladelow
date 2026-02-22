# Bladelow Builder (Fabric Mod)

Minecraft auto-building prototype for Fabric `1.21.11` (Java `21`).

## Quick Start

1. Download latest `minecraft-bladelow-*.jar` from GitHub Releases:
   - `https://github.com/AP2003GIT/bladelow/releases`
2. Put the jar in your Fabric mods folder (Lunar example):
   - `C:\Users\<YourWindowsUser>\.lunarclient\profiles\vanilla\1.21\mods\fabric-1.21.11\`
3. Restart Lunar Client.
4. Open a world (Creative recommended), press `P` to open Bladelow HUD.

## Developer Build (Optional)

Clone + build:
- `git clone https://github.com/AP2003GIT/bladelow.git`
- `cd bladelow`
- `./gradlew clean build`

Installer scripts:
- WSL + Lunar copy: `./scripts/install-lunar-wsl.sh`
- Short one-command alias (WSL): `./scripts/deploy-lunar-wsl.sh`
- Native Windows PowerShell installer: `./scripts/install-lunar-windows.ps1`

Manual WSL copy:
- `cp build/libs/minecraft-bladelow-*.jar "/mnt/c/Users/<YourWindowsUser>/.lunarclient/profiles/vanilla/1.21/mods/fabric-1.21.11/"`

## HUD Guide

1. Picker + slots
- Pick up to 3 slot blocks (`S1`, `S2`, `S3`) from the visual block grid.
- `Favorites` and `Recent` rows are above the grid for fast re-selection.
- `+F` adds active-slot block to favorites, `-F` removes it.
- Slot badges show inventory readiness (`OK`/`MISS`) per slot.

2. Modes and inputs
- Tabs: `LINE`, `SEL`, `BP`.
- `LINE` uses `X Y Z`, `Count`, and `Axis` (`X/Y/Z`).
- `SEL` uses `Height` and marked selection points (`Mark`).
- `BP` uses `X Y Z`, blueprint name, and optional block slots for override.

3. Run controls
- Cluster: `Run`, `Prev`, `Confirm`, `Cancel`.
- `Run` is guarded: it stays disabled until required inputs are valid.
- Inline validation tells you exactly what is missing (for example `count 1..4096`).

4. Automation + presets
- `Mode`: `WALK / AUTO / TELEPORT`
- `Smart`: smart movement on/off
- `Reach`: placement reach
- `Prof`: movement profile cycle
- Presets: `L20X`, `L20Z`, `SEL6`, `BP20`

5. Status + hotkeys
- Bottom panel shows compact progress and the latest Bladelow log lines.
- Hotkeys in HUD: `P` close, `R` run, `M` mark, `C` cancel, `V` preview toggle.

6. Scale + persistence
- `Scale: S/M/L` cycles HUD size.
- HUD state now saves per world/server profile (`config/bladelow/hud-state.properties`).

7. Blueprint + Web
- `BP Load` / `BP Build` for local blueprints.
- `Cat` syncs BuildIt catalog with limit field (`1..50`).
- `Imp` imports by catalog index or URL and loads it.

## Chat Commands (Manual)

Core:
- `#bladehelp`
- `#bladestatus`
- `#bladestatus detail`
- `#bladecancel`
- `#bladeconfirm`
- `#bladepreview show`

Placement:
- `#bladeplace <x> <y> <z> <count> [axis] <blocks_csv>`

Selection:
- `#bladeselect addhere`
- `#bladeselect add <x> <y> <z>`
- `#bladeselect remove <x> <y> <z>`
- `#bladeselect undo`
- `#bladeselect clear`
- `#bladeselect size`
- `#bladeselect list`
- `#bladeselect box <x1> <y1> <z1> <x2> <y2> <z2> [solid|hollow]`
- `#bladeselect buildh <height> <blocks_csv>`
- `#bladeselect export <name> <block_id>`

Movement/runtime tuning:
- `#blademove show`
- `#blademove on|off`
- `#blademove mode walk|auto|teleport`
- `#blademove reach <2.0..8.0>`
- `#blademove scheduler on|off`
- `#blademove lookahead <1..96>`
- `#blademove defer on|off`
- `#blademove maxdefer <0..8>`

Safety:
- `#bladesafety show`
- `#bladesafety strict on|off`
- `#bladesafety preview on|off`

Profiles:
- `#bladeprofile list`
- `#bladeprofile save <name>`
- `#bladeprofile load <name>`

Blueprints:
- `#bladeblueprint reload`
- `#bladeblueprint list`
- `#bladeblueprint load <name>`
- `#bladeblueprint info`
- `#bladeblueprint info <name>`
- `#bladeblueprint build <x> <y> <z> [blocks_csv]`
- `#bladeblueprint build <name> <x> <y> <z> [blocks_csv]`

Web import:
- `#bladeweb catalog [limit]`
- `#bladeweb import <index> [name]`
- `#bladeweb import <url>`
- `#bladeweb importload <index> [name]`
- `#bladeweb importloadurl <name> <url>`

Model:
- `#blademodel show|configure|reset|save|load`

Notes:
- `#` messages are auto-converted by Bladelow for known blade commands.
- Normal slash commands still work.
- `#bladelow` is an alias for `#bladehelp`.
- `<blocks_csv>` supports 1 to 3 blocks, e.g. `minecraft:stone,minecraft:glass,minecraft:oak_planks`.

## Command Examples

Selection workflow:

```text
#bladeselect add 10 -60 10
#bladeselect add 11 -60 10
#bladeselect addhere
#bladeselect box 8 -60 8 12 -60 12 hollow
#bladeselect list
#bladeselect buildh 10 minecraft:stone,minecraft:glass
#bladeselect export my_selection minecraft:stone
```

Line build:

```text
#bladeplace 10 -60 10 20 x minecraft:stone,minecraft:cobblestone
```

Blueprint build:

```text
#bladeblueprint load line20
#bladeblueprint build line20 10 -60 10 minecraft:stone,minecraft:glass
```

Pathing/scheduler tuning:

```text
#blademove mode auto
#blademove reach 6.0
#blademove scheduler on
#blademove lookahead 20
#blademove defer on
#blademove maxdefer 3
#blademove show
```

## Build Execution Model

- Jobs run async, one target processed each server tick.
- Runtime now includes:
  - dynamic target scheduler (lookahead reprioritization)
  - deferred unreachable retry pipeline (tail defers before final skip)
- Completion/status include diagnostics like `deferred`, `replan`, `already`, `blocked`, `noReach`, `mlSkip`.

## BuildIt Website Integration

- `bladeweb catalog` syncs a list from BuildIt WordPress API endpoints.
- If remote catalog fails but local cache exists, cached entries are reused.
- Catalog is persisted locally at `~/.bladelow/catalog-cache/<player-uuid>.json` for offline reuse.
- `bladeweb import` accepts catalog index or URL.
- `bladeweb importload` imports and selects blueprint for immediate `BP Build`.
- `bladeweb importloadurl` does the same for URL-based imports with explicit name.
- Import parser supports:
  - direct blueprint JSON
  - JSON links in pages (absolute/relative)
  - script/code-block embedded JSON with `placements`
- Allowed sources include BuildIt + GitHub/Gist raw hosts.

## Blueprint Format

Blueprint files are loaded from:
- `config/bladelow/blueprints/*.json`

Example:

```json
{
  "name": "line20",
  "placements": [
    { "x": 0, "y": 0, "z": 0, "block": "minecraft:stone" }
  ]
}
```

## Troubleshooting

1. Parsing errors (`trailing data`)
- Use command format exactly:
  - `#bladeplace 10 -60 10 20 minecraft:stone`
  - optional axis: `#bladeplace 10 -60 10 20 z minecraft:stone`

2. Many `noReach` skips
- Try:
  - `#blademove mode auto` or `#blademove mode teleport`
  - `#blademove reach 6.0`
  - `#blademove scheduler on`
  - `#blademove lookahead 20`
  - `#blademove defer on`

3. Build pending but not executing
- `#bladeconfirm` to start pending preview
- `#bladecancel` to discard

4. Build doesn’t place expected blocks
- Fill all slot blocks in HUD (`S1/S2/S3`) before `Run`.
- `#bladeplace` expects `x y z count [axis] blocks_csv`.
- Use `#bladestatus` to inspect `last=...` reason:
  - `no_item`
  - `out_of_reach`
  - `blocked`
  - `protected`
  - `attempts/defers` in `#bladestatus detail`

5. Missing blocks in picker
- Use search field and page arrows (registry-based block list).

6. Selection disappears after dimension change
- Expected behavior: selection is scoped per player per dimension.

## Notes

- Bladelow is a Fabric mod, not an injector/apk.
- HUD sends real command packets (not plain chat text).
- Model state persists in `config/bladelow/model.properties`.
- CI builds jar artifacts on push/PR.
- Tag a version (example `v0.1.1`) to publish a downloadable release jar.
