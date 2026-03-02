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
- `#bladeselect exportscan <name>`
- `#bladeselect copybox <name> <x1> <y1> <z1> <x2> <y2> <z2>`

Zoning:
- `#bladezone set residential|market|workshop|civic`
- `#bladezone box <type> <x1> <y1> <z1> <x2> <y2> <z2>`
- `#bladezone list`
- `#bladezone clear [type]`

Movement/runtime tuning:
- `#blademove show`
- `#blademove on|off`
- `#blademove mode walk|auto|teleport`
- `#blademove reach <2.0..8.0>`
- `#blademove scheduler on|off`
- `#blademove lookahead <1..96>`
- `#blademove defer on|off`
- `#blademove maxdefer <0..8>`
- `#blademove autoresume on|off`
- `#blademove trace on|off`
- `#blademove traceparticles on|off`

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
- `#bladeblueprint townlist`
- `#bladeblueprint load <name>`
- `#bladeblueprint info`
- `#bladeblueprint info <name>`
- `#bladeblueprint build <x> <y> <z> [blocks_csv]`
- `#bladeblueprint build <name> <x> <y> <z> [blocks_csv]`
- `#bladeblueprint townfill <x1> <y1> <z1> <x2> <y2> <z2>`
- `#bladeblueprint townfillsel`

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

## Town Fill Workflow

1. Mark a base area for the district:
- `#bladeselect markerbox <x1> <y1> <z1> <x2> <y2> <z2> <height>`
2. Optional: reserve districts before fill:
- `#bladezone set residential`
- `#bladezone box market <x1> <y1> <z1> <x2> <y2> <z2>`
- `#bladezone list`
3. Inspect available town blueprints:
- `#bladeblueprint townlist`
4. Auto-fill the selected area with fitting town buildings:
- `#bladeblueprint townfillsel`
5. Or fill any explicit bounds directly:
- `#bladeblueprint townfill <x1> <y1> <z1> <x2> <y2> <z2>`

The planner uses deterministic city-layout scoring, not generic ML. It detects or synthesizes a street grid, places road blocks for those corridors, expands major intersections into plaza cells, derives ordered lots from road edges, rotates blueprints to face their assigned street side, scores those lots against center, wall, gate, road-adjacent, and user-zoned districts, avoids overlap, and queues one combined build job.

## Command Examples

Selection workflow:

```text
#bladeselect markerbox 8 -60 8 12 -60 12 6 solid
#bladeselect buildh 6 minecraft:stone,minecraft:glass,minecraft:oak_planks
#bladestatus detail
```

Copy + recreate workflow:

```text
#bladeselect copybox my_house 100 64 100 124 78 124
#bladeblueprint load my_house
#bladeblueprint build my_house 200 64 200
```

Zoned town workflow:

```text
#bladeselect markerbox 10 -60 10 90 -60 90 8
#bladezone box civic 42 -60 42 58 -60 58
#bladezone box market 12 -60 36 26 -60 54
#bladezone set residential
#bladeblueprint townfillsel
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
  - candidate stand-position solver per target (face/edge based stand spots)
  - attempt-index fallback through candidate list before skip/defer
  - per-target pressure tracking for stuck/no-path hot spots (auto defer/skip to prevent loops)
  - task-node timeout guards for `move/align/place/recover`
  - auto-resume for pending non-preview jobs when player reconnects
- Completion/status include diagnostics like `deferred`, `replan`, `already`, `blocked`, `noReach`, `mlSkip`.
- `#bladestatus detail` now also reports:
  - `cand=<idx>/<count>` active candidate index and pool size
  - `cScore=<value>` selected candidate score
  - `cTag=<label>` selected candidate offset label
  - `retry=<reason>` latest retry/fallback reason

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
  "name": "town_house_small",
  "category": "town",
  "plotWidth": 7,
  "plotDepth": 9,
  "priority": 8,
  "entranceX": 3,
  "entranceZ": 0,
  "roadSide": "north",
  "themeTags": ["medieval", "oak", "village"],
  "tags": ["house", "residential", "small"],
  "placements": [
    { "x": 0, "y": 0, "z": 0, "block": "minecraft:stone_brick_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]" }
  ]
}
```

Optional metadata used by the town planner:
- `category: "town"` includes the blueprint in `townlist` / `townfill`.
- `plotWidth` / `plotDepth` define the reserved footprint.
- `priority` lets larger or more important buildings win first during placement.
- `entranceX` / `entranceZ` define the local entrance point the planner scores against roads and gates.
- `roadSide` tells the planner which plot edge should prefer road contact (`north|south|east|west`).
- `themeTags` describe the material/style family for future palette theming.
- `tags` drive zoning preferences such as house, market, smithy, residential, utility.
- `placements[].block` accepts a plain block id or a full block-state string.
- Exported blueprints now keep exact saved block states, so stairs, doors, trapdoors, furnaces, and other facing-sensitive blocks survive rebuilds.
- Town fill now places buildings on ordered lots beside detected or synthetic road corridors instead of scanning every cell as a free plot.
- Blueprint footprints and saved block states are rotated automatically so their entrance/road side faces the assigned street edge.
- Synthetic roads are built with stone-brick / cobblestone corridors when the selected area has no usable road network yet.
- Plaza cells are generated around major street intersections and built with polished-andesite accents.

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
  - inspect `#bladestatus detail`:
    - if `cand` stays low/empty, move closer or raise `reach`
    - if `retry=out_of_reach:*`, enable `auto` mode or teleport mode for tough terrain

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
- `attempts/defers/cand/cScore/retry` in `#bladestatus detail`

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
