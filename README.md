# Bladelow Builder (Fabric Mod)

Bladelow is a Minecraft Fabric mod (`1.21.11`, Java `21`) for assisted block placement, blueprint building, web blueprint import, and district-based town filling.

## Current Feature Set

- Marker-based area selection and vertical build tools.
- 1-3 block palette placement with inventory-aware fallback.
- Blueprint load/build with optional palette override.
- BuildIt website import pipeline (catalog + URL import).
- District zoning (`residential`, `market`, `workshop`, `civic`, `mixed`).
- City/town planner commands (`townfill*`, `townpreview*`, `townruncity`).
- Runtime controls (pause/continue/cancel, movement tuning, diagnostics, profiles, model).
- HUD with 4-step flow: `AREA -> BLOCKS -> SOURCE -> RUN`.

## Requirements

- Minecraft `1.21.11`
- Fabric Loader
- Java `21`

## Install (Users)

1. Download latest `minecraft-bladelow-*.jar` from Releases:
   - `https://github.com/AP2003GIT/bladelow/releases`
2. Place the jar in your Fabric mods folder.
   - Lunar example:
     - `C:\Users\<YourUser>\.lunarclient\profiles\vanilla\1.21\mods\fabric-1.21.11\`
3. Restart client.
4. In game, press `P` to open the Bladelow HUD.

## Build (Developers)

```bash
cd "/home/p90lo/project bladelow/minecraft-bladelow"
./gradlew clean build
```

Jar output:
- `build/libs/minecraft-bladelow-0.1.0.jar`

WSL copy to Lunar (example):
```bash
cp "build/libs/minecraft-bladelow-0.1.0.jar" "/mnt/c/Users/<YourUser>/.lunarclient/profiles/vanilla/1.21/mods/fabric-1.21.11/"
```

Helper scripts:
- `scripts/install-lunar-wsl.sh`
- `scripts/deploy-lunar-wsl.sh`
- `scripts/install-lunar-windows.ps1`

## HUD Guide

### Modes
- `MARKER`: marker selection + selection build.
- `BLUEPRINT`: selected/typed blueprint build.
- `CITY`: district zoning + town fill / preview.

### Flow Tabs
- `1 AREA`: set coords, marker A/B, apply marker box.
- `2 BLOCKS`: pick block slots (`S1`, `S2`, `S3`).
- `3 SOURCE`: blueprint/web/city controls.
- `4 RUN`: execute run controls.

### Main Buttons
- `Start Build` / `Run Town Fill`: runs active mode.
- `Stop`: sends `bladepause`.
- `Continue Build`: sends `bladecontinue`.
- `Cancel`: sends `bladecancel`.

### Hotkeys (HUD open)
- `P`: close HUD
- `R`: run active mode
- `M`: mark selection point
- `C`: pause
- `V`: continue

### HUD State
HUD UI state persists to:
- `config/bladelow/hud-state.properties`

## Chat Command Prefix Rules

Bladelow supports direct slash commands (`/blade...`) and partial `#` shortcuts.

- `#` auto-conversion is enabled for these roots:
  - `bladehelp`, `bladeplace`, `bladeselect`, `bladecancel`, `bladepause`, `bladecontinue`, `bladeconfirm`, `bladepreview`, `bladestatus`, `blademove`, `bladesafety`, `bladeprofile`, `bladeblueprint`, `bladeweb`, `blademodel`
- Alias:
  - `#bladelow` -> `#bladehelp`
- Not auto-converted by `#`:
  - `bladezone`, `bladediag`
  - Use slash form: `/bladezone ...`, `/bladediag ...`

## Command Reference

### Help
- `/bladehelp`

### Placement
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

### Blueprints
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
- `residential`, `market`, `workshop`, `civic`, `mixed`

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

### Safety
- `/bladesafety show`
- `/bladesafety strict on|off`
- `/bladesafety preview on|off`

### Profiles
- `/bladeprofile list`
- `/bladeprofile save <name>`
- `/bladeprofile load <name>`

### Model
- `/blademodel show`
- `/blademodel reset`
- `/blademodel save`
- `/blademodel load`
- `/blademodel configure <threshold> <learning_rate>`

## BuildIt Integration Notes

- Web operations are asynchronous (non-blocking for game ticks).
- Catalog cache path is inside game dir:
  - `config/bladelow/cache/<player-uuid>.json`
- Imports normalize external JSON to internal blueprint format before saving.

## Blueprints

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
    { "x": 0, "y": 0, "z": 0, "block": "minecraft:stone" }
  ]
}
```

## Town/City Workflow (Recommended)

1. Mark build bounds (`A`, `B`, `Height`) in HUD or with `/bladeselect markerbox`.
2. Save districts with `/bladezone set <type>` or `/bladezone box ...`.
3. Inspect templates with `/bladeblueprint townlist`.
4. Preview with `/bladeblueprint townpreviewsel`.
5. Run fill with `/bladeblueprint townfillsel`.
6. Optional phased run with `/bladeblueprint townruncity`.

## Troubleshooting

1. Command not running with `#`
- Use `/` for roots not in hash conversion (`bladezone`, `bladediag`).

2. Import issues
- Run `/bladeweb catalog` first.
- For URL import, ensure URL is valid and host is supported.

3. Build paused/stuck
- Check `/bladestatus detail`.
- Use `/bladecontinue`, `/bladepause`, `/bladecancel` as needed.
- Try `/blademove mode auto` or `/blademove mode teleport`.

4. No placement material
- Fill at least one slot block (`S1..S3`) in HUD.
- In survival, ensure inventory contains required/fallback blocks.

## Internal Command Modules

Current command split in `src/main/java/com/bladelow/command/`:
- `RuntimeCommands`
- `WebCommands`
- `ZoneCommands`
- `MaterialResolver`
- `PaletteAssigner`
- `PlacementPipeline`
- `BladePlaceCommand` (entry + remaining selection/blueprint commands)
