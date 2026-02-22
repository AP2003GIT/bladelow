# Bladelow Builder (Fabric Mod)

Minecraft auto-building prototype for Fabric `1.21.11` (Java `21`).

## Quick Start

1. Download latest `minecraft-bladelow-*.jar` from GitHub Releases:
   - `https://github.com/AP2003GIT/bladelow/releases`
2. Put the jar in your Fabric mods folder (Lunar example):
   - `C:\Users\<YourWindowsUser>\.lunarclient\profiles\vanilla\1.21\mods\fabric-1.21.11\`
3. Restart Lunar Client.
4. Open world (Creative recommended), press `P` to open HUD.

## Developer Build (Optional)

1. Clone the repo and enter it:
   - `git clone https://github.com/AP2003GIT/bladelow.git`
   - `cd bladelow`
2. Run one command installer (WSL + Lunar):
   - `./scripts/install-lunar-wsl.sh`

Manual install (if you do not use the script):
1. Build:
   - `./gradlew clean build`
2. Copy the jar to your Lunar Fabric mods folder:
   - `cp build/libs/minecraft-bladelow-*.jar "/mnt/c/Users/<YourWindowsUser>/.lunarclient/profiles/vanilla/1.21/mods/fabric-1.21.11/"`

## HUD Guide

1. Pick up to 3 blocks:
   - Click `S1/S2/S3` to choose active slot.
   - Click block tiles to assign.
   - Slot palette rotates across placed targets (`S1 -> S2 -> S3 -> repeat`).
2. Set target values:
   - `X Y Z` start position.
   - `Count` for line builds.
   - `TopY` for selection-column builds.
3. Use `Shape` to cycle:
   - `LINE`
   - `SEL` (build up from marked selection to `TopY`)
   - Selection points are scoped to your player and current dimension.
4. Click `Run` to start current shape mode.
5. Optional controls:
   - `Axis` for line direction (`X/Y/Z`)
   - `Smart` ON/OFF
   - `Mode` WALK/AUTO/TELEPORT
   - `Reach` controls (`-` / `+`) for placement pathing distance
   - `Prev` OFF/ON (preview before build)
   - `Confirm` / `Cancel` for pending preview jobs
   - `Blueprint` name + `BP Load` / `BP Build`
   - `Prof` applies movement/safety profile
   - `Mark` adds one selection point from XYZ

## Chat Commands (Manual)

- `#bladeplace <blocks_csv> <x> <y> <z> <count> [axis]`
- `#bladeselect add <x> <y> <z>`
- `#bladeselect remove <x> <y> <z>`
- `#bladeselect undo`
- `#bladeselect list`
- `#bladeselect build <blocks_csv> <top_y>`
- `#bladeselect clear`
- `#blademove on|off|show`
- `#blademove mode walk|auto|teleport`
- `#blademove reach <2.0..8.0>`
- `#blademodel show|configure|reset|save|load`
- `#bladecancel`
- `#bladeconfirm`
- `#bladestatus`
- `#bladepreview show`
- `#bladesafety show`
- `#bladesafety strict on|off`
- `#bladesafety preview on|off`
- `#bladeprofile list`
- `#bladeprofile save <name>`
- `#bladeprofile load <name>`
- `#bladeblueprint reload`
- `#bladeblueprint list`
- `#bladeblueprint load <name>`
- `#bladeblueprint info`
- `#bladeblueprint info <name>`
- `#bladeblueprint build <x> <y> <z> [blocks_csv]`
- `#bladeblueprint build <name> <x> <y> <z> [blocks_csv]`
- `#bladeweb catalog [limit]`
- `#bladeweb import <index> [name]`
- `#bladeweb import <url>`

`#` messages are auto-converted by Bladelow into real commands. Slash commands still work.

`<blocks_csv>` supports 1 to 3 blocks, example:
- `minecraft:stone,minecraft:glass,minecraft:oak_planks`

## Command Examples

`#bladeselect` workflow examples:

```text
#bladeselect add 10 -60 10
#bladeselect add 11 -60 10
#bladeselect add 12 -60 10
#bladeselect list
#bladeselect undo
#bladeselect remove 11 -60 10
#bladeselect build minecraft:stone,minecraft:glass -50
```

Basic line build example:

```text
#bladeplace minecraft:stone,minecraft:cobblestone 10 -60 10 20 x
```

Blueprint build example:

```text
#bladeblueprint load line20
#bladeblueprint build line20 10 -60 10 minecraft:stone,minecraft:glass
```

Build commands are queued async:
- one target is processed each server tick
- use `/bladestatus` to view progress
- use `/bladecancel` to stop current build
- each target retries before skip/fail (path/placement backoff)
- completion now reports reasons (`already`, `blocked`, `noReach`, `mlSkip`)

## Troubleshooting

1. If command parsing fails (`trailing data`):
   - use current jar and format: `#bladeplace minecraft:stone 10 -60 10 20`
   - optional axis: `#bladeplace minecraft:stone 10 -60 10 20 z`
2. If builds skip many blocks (`noReach`):
   - set mode to `AUTO` or `TELEPORT`
   - increase reach: `#blademove reach 6.0`
3. If only one block is used:
   - ensure `S1/S2/S3` all have assigned blocks
   - for blueprints use `BP Build` after assigning slots; slot palette now overrides template blocks
4. If build is pending and not executing:
   - `#bladeconfirm` to start pending preview job
   - `#bladecancel` to discard
5. If block is not visible in picker:
   - use search box (picker now loads full block registry, excluding air/fluid-only entries)
6. If selection seems missing after changing dimension/world:
   - this is expected; selection is isolated per player per dimension
   - mark points again in the current dimension before `#bladeselect build`

Preview/confirm workflow:
- enable with `#bladesafety preview on`
- run any build command to generate pending preview markers
- use `#bladeconfirm` to execute, `#bladecancel` to discard

Blueprint files are loaded from:
- `config/bladelow/blueprints/*.json`

BuildIt website integration:
- `bladeweb catalog` pulls a selectable list from `builditapp.com`.
- `bladeweb import <index>` imports a picked catalog entry into local blueprints.
- In HUD, `WebCat` syncs catalog and `WebImp` imports using the input field value (index or URL).

Example blueprint format:
```json
{
  "name": "line20",
  "placements": [
    { "x": 0, "y": 0, "z": 0, "block": "minecraft:stone" }
  ]
}
```

## Notes

- This is a mod, not an injector/apk.
- HUD buttons now send real command packets (not plain chat text).
- Model state saves in `config/bladelow/model.properties`.
- Installer script can be configured with env vars:
  - `WIN_USER` (Windows username override)
  - `MC_PROFILE_VERSION` (default `1.21`)
  - `FABRIC_SUBDIR` (default `fabric-1.21.11`)
- CI builds on every push/PR and uploads jar artifacts in GitHub Actions.
- Tag a version like `v0.1.1` to auto-publish a downloadable jar in Releases.
