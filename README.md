# Bladelow Builder (Fabric Mod)

Bladelow is a Fabric mod for Minecraft `1.21.11` (Java `21`) focused on assisted building, blueprint workflows, web import, and district-based town filling.

## What Exists Right Now

- Selection/marker area tools (`markerbox`, `box`, `build`, `buildh`).
- 1-3 block palette placement (slot fallback supported).
- Blueprint build/load/list/info + town template workflows.
- BuildIt web import (`catalog`, `import`, `importload`, URL variants).
- District zoning with five types: `residential`, `market`, `workshop`, `civic`, `mixed`.
- Runtime controls: pause/continue/cancel, preview/confirm, move tuning, diagnostics, profiles, model config.
- Semi-autonomous planner (`/bladeauto`) with queue -> plan -> phased confirm/skip flow.
- HUD with explicit flow tabs: `AREA -> BLOCKS -> SOURCE -> RUN`.
- HUD mode buttons: `SEL`, `BP`, `CITY`.

## Requirements

- Minecraft `1.21.11`
- Fabric Loader
- Java `21`

## Install (Users)

1. Download the latest mod jar from releases:
   - `https://github.com/AP2003GIT/bladelow/releases`
2. Put the jar in your Fabric mods folder.
   - Lunar example:
   - `C:\Users\<YourUser>\.lunarclient\profiles\vanilla\1.21\mods\fabric-1.21.11\`
3. Restart client.
4. Open world and press `P` to open Bladelow HUD.

## Build (Developers)

```bash
cd "/home/p90lo/project bladelow/minecraft-bladelow"
./gradlew clean build
```

Jar output:
- `build/libs/minecraft-bladelow-0.1.0.jar`

WSL -> Lunar copy example:

```bash
cp "build/libs/minecraft-bladelow-0.1.0.jar" "/mnt/c/Users/<YourUser>/.lunarclient/profiles/vanilla/1.21/mods/fabric-1.21.11/"
```

Helper scripts:
- `scripts/install-lunar-wsl.sh`
- `scripts/deploy-lunar-wsl.sh`
- `scripts/install-lunar-windows.ps1`

## HUD Guide (Current)

### Mode Buttons

- `SEL`: selection/marker-based builds.
- `BP`: blueprint-driven builds/import.
- `CITY`: district/town tools.

### Flow Tabs

- `AREA`: coordinates, markers, axis/height inputs.
- `BLOCKS`: choose S1/S2/S3 palette blocks.
- `SOURCE`: blueprint/web/city source controls.
- `RUN`: execution controls.

### Main Run Controls

- `Start Build` (or `Run Town Fill` when in `CITY` mode)
- `Stop` -> `bladepause`
- `Continue Build` -> `bladecontinue`
- `Cancel` -> `bladecancel`

### HUD Hotkeys (while HUD open)

- `P`: close HUD
- `R`: run active mode
- `M`: mark point
- `C`: pause
- `V`: continue

### HUD State File

- `config/bladelow/hud-state.properties`

## Chat Prefix Rules

Bladelow supports normal slash commands (`/blade...`) and selective `#` shortcuts.

`#` auto-converts for:
- `bladehelp`
- `bladeplace`
- `bladeselect`
- `bladecancel`
- `bladepause`
- `bladecontinue`
- `bladeconfirm`
- `bladepreview`
- `bladestatus`
- `blademove`
- `bladesafety`
- `bladeprofile`
- `bladeblueprint`
- `bladeweb`
- `blademodel`

Alias:
- `#bladelow` -> `#bladehelp`

Not auto-converted by `#`:
- `bladezone`
- `bladediag`
- `bladeauto`

Use slash form for those:
- `/bladezone ...`
- `/bladediag ...`
- `/bladeauto ...`

## Command Reference

### Core

- `/bladehelp`
- `/bladeplace <x> <y> <z> <count> [axis] <blocks_csv>`

### Selection

- `/bladeselect add <pos>`
- `/bladeselect addhere`
- `/bladeselect markerbox <from> <to> <height> [solid|hollow]`
- `/bladeselect box <from> <to> [solid|hollow]`
- `/bladeselect remove <pos>`
- `/bladeselect undo`
- `/bladeselect clear`
- `/bladeselect size`
- `/bladeselect list`
- `/bladeselect build <top_y> <blocks_csv>`
- `/bladeselect buildh <height> <blocks_csv>`
- `/bladeselect export <name> <block_id>`
- `/bladeselect exportscan <name>`
- `/bladeselect copybox <name> <from> <to>`

### Blueprint + Town

- `/bladeblueprint reload`
- `/bladeblueprint list`
- `/bladeblueprint townlist`
- `/bladeblueprint load <name>`
- `/bladeblueprint info [name]`
- `/bladeblueprint build <start> [blocks_csv]`
- `/bladeblueprint build <name> <start> [blocks_csv]`
- `/bladeblueprint townfill <from> <to>`
- `/bladeblueprint townfillsel`
- `/bladeblueprint townpreview <from> <to>`
- `/bladeblueprint townpreviewsel`
- `/bladeblueprint townfillzone <type> [from to]`
- `/bladeblueprint townpreviewzone <type> [from to]`
- `/bladeblueprint townclearlocks`
- `/bladeblueprint townruncity [from to]`

### District Zones

- `/bladezone set <type>`
- `/bladezone box <type> <from> <to>`
- `/bladezone list`
- `/bladezone clear [type]`

District types:
- `residential`
- `market`
- `workshop`
- `civic`
- `mixed`

### Web Import

- `/bladeweb catalog [limit]`
- `/bladeweb import <index> [name]`
- `/bladeweb import <url>`
- `/bladeweb importnamed <name> <url>`
- `/bladeweb importload <index> [name]`
- `/bladeweb importloadurl <name> <url>`

### Runtime / Diagnostics

- `/bladecancel`
- `/bladepause`
- `/bladecontinue`
- `/bladeconfirm`
- `/bladepreview show`
- `/bladestatus [detail]`
- `/bladediag [show]`
- `/bladediag export [name]`

### AI Auto Planner

- `/bladeauto add <blueprint> [count]`
- `/bladeauto goals`
- `/bladeauto plan`
- `/bladeauto confirm`
- `/bladeauto skip`
- `/bladeauto cancel`
- `/bladeauto clear`
- `/bladeauto remove <index>`

`/bladeauto confirm` now starts phased execution:
- `FOUNDATION -> WALLS -> ROOF -> DETAILS`

### Movement

- `/blademove show`
- `/blademove on|off`
- `/blademove mode walk|auto|teleport`
- `/blademove reach <2.0..8.0>`
- `/blademove scheduler on|off`
- `/blademove lookahead <1..96>`
- `/blademove defer on|off`
- `/blademove maxdefer <0..8>`
- `/blademove autoresume on|off`
- `/blademove trace on|off`
- `/blademove traceparticles on|off`

### Safety / Profiles / Model

- `/bladesafety show`
- `/bladesafety strict on|off`
- `/bladesafety preview on|off`
- `/bladeprofile list`
- `/bladeprofile save <name>`
- `/bladeprofile load <name>`
- `/blademodel show`
- `/blademodel reset`
- `/blademodel save`
- `/blademodel load`
- `/blademodel configure <threshold> <learning_rate>`

## BuildIt Notes

- Web calls are async (no server tick blocking).
- Per-player catalog cache:
  - `config/bladelow/cache/<player-uuid>.json`
- Imported data is normalized before blueprint save.

## Blueprint Files

Path:
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
    { "x": 0, "y": 0, "z": 0, "block": "minecraft:stone" }
  ]
}
```

## Recommended Town Workflow

1. Mark build bounds in HUD (`AREA`) or use `/bladeselect markerbox`.
2. Save district zones (`/bladezone set` or `/bladezone box`).
3. Check available town templates (`/bladeblueprint townlist`).
4. Preview (`/bladeblueprint townpreviewsel`).
5. Run fill (`/bladeblueprint townfillsel`).
6. Optional multi-step city run (`/bladeblueprint townruncity`).

## Troubleshooting

1. `#` command does nothing
- Use `/` for `bladezone` and `bladediag`.

2. Import fails
- Run `/bladeweb catalog` first.
- Verify URL format/host.

3. Build seems stuck
- Check `/bladestatus detail`.
- Resume/pause/cancel with runtime commands.
- Try `/blademove mode auto` or `/blademove mode teleport`.

4. No blocks placed
- Select at least one palette slot (`S1/S2/S3`).
- In survival, ensure required blocks are in inventory.

## Internal Command Modules

`src/main/java/com/bladelow/command/`:
- `RuntimeCommands`
- `WebCommands`
- `ZoneCommands`
- `MaterialResolver`
- `PaletteAssigner`
- `PlacementPipeline`
- `BladePlaceCommand` (entry + selection/blueprint registration)
