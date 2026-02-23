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

1. Block picker + slots
- Visual block picker with search + page arrows.
- Pick up to 3 blocks in slots `S1`, `S2`, `S3`.
- Slot indicator dot: green = ready, red = missing, gray = empty.

2. Modes
- Tabs: `AREA` and `BP`.
- `AREA` is marker-based build mode (default).
- `BP` is blueprint/web-import build mode.
- Old `LINE` mode is removed from HUD flow.

3. Marker area workflow (`AREA`)
- Set coordinates (`Auto` from player or manual `X/Y/Z`).
- Click `Set A`, then `Set B`.
- Set `Height` (`1..256`).
- Click `Mark Box` to generate the build base selection.
- Click `Clear Mk` to reset markers/selection.

4. Run controls
- `Start Build`: queue and start build from current mode.
- `Stop`: pauses active build (`#bladepause`).
- `Continue Build`: resumes paused build (`#bladecontinue`).
- `Cancel`: cancels active/pending build (`#bladecancel`).
- Start is guarded and stays disabled until required inputs are valid.

5. Blueprint + BuildIt workflow (`BP`)
- Paste BuildIt URL (or catalog index) in URL field.
- Click `Import URL` (runs web import + load).
- Click `Start Build` to execute at marker `A` (or current XYZ).

6. Status + hotkeys
- Bottom panel shows validation + latest status.
- HUD hotkeys: `P` close, `R` start, `M` mark, `C` stop/pause, `V` continue.

7. Persistence
- HUD state saves per world/server profile:
  - `config/bladelow/hud-state.properties`

## Chat Commands (Manual)

Core:
- `#bladehelp`
- `#bladestatus`
- `#bladestatus detail`
- `#bladecancel`
- `#bladepause`
- `#bladecontinue`
- `#bladeconfirm`
- `#bladepreview show`

Placement:
- `#bladeplace <x> <y> <z> <count> [axis] <blocks_csv>`

Selection:
- `#bladeselect markerbox <x1> <y1> <z1> <x2> <y2> <z2> <height> [solid|hollow]`
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
#bladeselect markerbox 8 -60 8 12 -60 12 6 solid
#bladeselect buildh 6 minecraft:stone,minecraft:glass,minecraft:oak_planks
#bladestatus detail
```

Direct place build:

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
- `bladeweb importload` imports and selects blueprint for immediate `BP` run.
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
- Use `#bladecontinue` if paused
- Use `#bladecancel` to discard
- `#bladeconfirm` is only needed when preview-before-build is enabled

4. Build doesn’t place expected blocks
- Fill at least one slot block in HUD (`S1/S2/S3`) before `Start Build`.
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
