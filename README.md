# Bladelow Builder (Fabric Mod)

Minecraft auto-building prototype for Fabric `1.21.11` (Java `21`).

## Quick Start

1. Build the jar:
   - `cd "/home/p90lo/project bladelow/minecraft-bladelow"`
   - `./gradlew clean build`
2. Copy jar into Lunar Fabric mods:
   - `cp "/home/p90lo/project bladelow/minecraft-bladelow/build/libs/minecraft-bladelow-0.1.0.jar" "/mnt/c/Users/p90lo/.lunarclient/profiles/vanilla/1.21/mods/fabric-1.21.11/"`
3. Restart Lunar Client.
4. Open world (Creative recommended), press `P` to open HUD.

## HUD Guide

1. Pick up to 3 blocks:
   - Click `S1/S2/S3` to choose active slot.
   - Click block tiles to assign.
2. Set target values:
   - `X Y Z` start position.
   - `Count` for line builds.
   - `TopY` for selection-column builds.
3. Use `Shape` to cycle:
   - `LINE`
   - `EDGE` (7x9 hollow border)
   - `SQ9` (filled 9x9 square)
   - `CIR`, `DIA`, `RND`, `TRI`, `STR`, `HRT`
   - `SEL` (build up from marked selection to `TopY`)
4. Click `Build Shape` (or `Start Build` for direct line mode).
5. Optional controls:
   - `Axis` for line direction (`X/Y/Z`)
   - `Smart` ON/OFF
   - `Mode` WALK/TELEPORT
   - `Blueprint` name + `BP Load` / `BP Build`
   - `Mark XYZ` adds one selection point
   - `Sel Clear` clears selection points

## Chat Commands (Manual)

- `/bladeplace <blocks_csv> <x> <y> <z> <count> [axis]`
- `/bladeshape hollow79 <blocks_csv> <x> <y> <z>`
- `/bladeshape pattern <shape> <blocks_csv> <x> <y> <z>`
- `/bladeselect add <x> <y> <z>`
- `/bladeselect build <blocks_csv> <top_y>`
- `/bladeselect clear`
- `/blademove on|off|show`
- `/blademove mode walk|auto|teleport`
- `/blademove reach <2.0..8.0>`
- `/blademodel show|configure|reset|save|load`
- `/bladecancel`
- `/bladeconfirm`
- `/bladestatus`
- `/bladepreview show`
- `/bladesafety show`
- `/bladesafety strict on|off`
- `/bladesafety preview on|off`
- `/bladeprofile list`
- `/bladeprofile save <name>`
- `/bladeprofile load <name>`
- `/bladeblueprint reload`
- `/bladeblueprint list`
- `/bladeblueprint load <name>`
- `/bladeblueprint info`
- `/bladeblueprint info <name>`
- `/bladeblueprint build <x> <y> <z> [blocks_csv]`
- `/bladeblueprint build <name> <x> <y> <z> [blocks_csv]`
- `/bladeweb catalog [limit]`
- `/bladeweb import <index> [name]`
- `/bladeweb import <url>`

`<blocks_csv>` supports 1 to 3 blocks, example:
- `minecraft:stone,minecraft:glass,minecraft:oak_planks`

`<shape>` values:
- `square9`, `circle9`, `diamond9`, `round9`, `triangle9`, `star9`, `heart9`

Build commands are queued async:
- one target is processed each server tick
- use `/bladestatus` to view progress
- use `/bladecancel` to stop current build
- each target retries before skip/fail (path/placement backoff)
- completion now reports reasons (`already`, `blocked`, `noReach`, `mlSkip`)

## Troubleshooting

1. If command parsing fails (`trailing data`):
   - use current jar and format: `/bladeplace minecraft:stone 10 -60 10 20`
   - optional axis: `/bladeplace minecraft:stone 10 -60 10 20 z`
2. If shapes skip many blocks (`noReach`):
   - set mode to `AUTO` or `TELEPORT`
   - increase reach: `/blademove reach 6.0`
3. If only one block is used:
   - ensure `S1/S2/S3` all have assigned blocks
   - for blueprints use `BP Build` after assigning slots; slot palette now overrides template blocks
4. If build is pending and not executing:
   - `/bladeconfirm` to start pending preview job
   - `/bladecancel` to discard
5. If block is not visible in picker:
   - use search box (picker now loads full block registry, excluding air/fluid-only entries)

Preview/confirm workflow:
- enable with `/bladesafety preview on`
- run any build command to generate pending preview markers
- use `/bladeconfirm` to execute, `/bladecancel` to discard

Blueprint files are loaded from:
- `config/bladelow/blueprints/*.json`

BuildIt website integration:
- `bladeweb catalog` pulls a selectable list from `builditapp.com`.
- `bladeweb import <index>` imports a picked catalog entry into local blueprints.
- In HUD, `WebCat` syncs catalog and `WebImp` imports using the input field value (index or URL).

Example blueprint format:
```json
{
  "name": "square9",
  "placements": [
    { "x": 0, "y": 0, "z": 0, "block": "minecraft:stone" }
  ]
}
```

## Notes

- This is a mod, not an injector/apk.
- HUD buttons now send real command packets (not plain chat text).
- Model state saves in `config/bladelow/model.properties`.
