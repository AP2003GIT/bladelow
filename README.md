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

1. Block selection
- Pick up to 3 block slots (`S1`, `S2`, `S3`) from visual block tiles.
- Slots rotate during placement (`S1 -> S2 -> S3 -> repeat`).
- Clicking a tile auto-advances to the next slot for faster 3-block setup.
- Use search + page arrows to browse blocks.

2. Build inputs
- `X Y Z`: start position.
- `Count`: line length.
- `Height`: selection column height above each marked point.
- `Axis`: line direction (`X/Y/Z`).

3. Main actions
- `Run`: run active mode (`LINE`, `SEL`, or `BP`).
- `Prev`: preview mode toggle.
- `OK`: confirm pending preview.
- `Stop`: cancel active/pending job.
- `Mark`: add selection point from current XYZ fields.

4. Automation controls
- `Mode`: `WALK / AUTO / TELEPORT`
- `Smart`: smart movement on/off
- `Reach`: adjust reach distance
- Tabs: `LINE / SEL / BP`

5. Blueprint + Web
- `BP Load` / `BP Build` for local blueprints.
- `Cat` syncs BuildIt catalog using the `lim` field (`1..50`).
- `ImpLoad` imports and auto-loads blueprint from catalog index or URL input (`idx/url`).
- URL imports auto-generate a safe blueprint name when BP name is blank.
- `Prof` cycles movement profile presets.
- `Mark` adds selection point from XYZ.
- `Stat` runs `#bladestatus detail` in chat.

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
