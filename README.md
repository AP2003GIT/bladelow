# Bladelow Builder (Fabric Mod)

Bladelow is a Fabric mod for Minecraft `1.21.11` (Java `21`) focused on assisted building, district-based town filling, layout-aware HUD planning, and increasingly automated city construction with local learning signals.

## What Exists Right Now

- Selection/marker area tools (`markerbox`, `box`, `build`, `buildh`).
- 1-3 block palette placement (slot fallback supported).
- Blueprint build/load/list/info + town template workflows.
- District zoning with five types: `residential`, `market`, `workshop`, `civic`, `mixed`.
- Runtime controls: pause/continue/cancel, preview/confirm, move tuning, diagnostics, profiles, model config.
- Automatic block safety: inventories, redstone, crops, doors, rails, utility blocks, and other sensitive blocks are skipped by default.
- Semi-autonomous planner (`/bladeauto`) with queue -> plan -> phased confirm/skip flow.
- City Director autopilot (`/bladeblueprint cityautoplay`) with ordered district queue, terrain-prep stage, material auto-resolution, and resume persistence.
- HUD with explicit flow tabs: `AREA -> BLOCKS -> SOURCE -> RUN`.
- HUD mode buttons: `SEL`, `BP`, `CITY`.
- Planning minimap that renders layout categories instead of only coordinates.
- Suggested plot overlay on the minimap for open build footprints.
- Build intent scan (`/blademodel intent`) wired into the HUD city flow.
- Local ML-style datasets for placements, environment scans, and build-intent examples.

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

The HUD now includes a planning map on the left side for non-block steps. Use it to drag-select areas visually instead of relying on raw coordinate commands.

### Mode Buttons

- `SEL`: selection/marker-based builds.
- `BP`: blueprint-driven builds.
- `CITY`: district/town tools with automation presets.

### Flow Tabs

- `AREA`: coordinates, markers, axis/height inputs, and drag-select planning map.
- `BLOCKS`: choose S1/S2/S3 palette blocks.
- `SOURCE`: blueprint and city source controls.
- `RUN`: execution controls.

### Planning Map

- Left-drag on the planning map to place or resize the active build area.
- Right-click on the planning map to clear the current marker area.
- In `CITY` mode, pick a district brush on the right and drag on the map to save that district visually.
- The map renders a layout view around the current area/player so selection is easier in-world.
- Layout colors are grouped by type: `water`, `road`, `structure`, `vegetation`, `open ground`, `terrain`.
- Hovering the map shows the surface category plus the `x,z` location.
- Suggested build plots are drawn as pale green rectangles inside the selected area.
- Hold `Shift` and left-click a suggested plot to snap the current selection to that footprint.
- In `CITY` mode, applying the marker box automatically requests a build-intent scan for the selected area.
- The city status bar now shows the latest inferred intent summary from the planner.

### Main Run Controls

- `Start Build` (or `Run City Build` in `CITY` mode)
- `Pause` -> `bladepause`
- `Resume` -> `bladecontinue`
- `Stop Build` -> `bladecancel`

### City Automation (HUD)

- `Preset`: cycles district layout preset (`medieval`, `balanced`, `harbor`, `adaptive`).
- `Auto Zones`: runs `/bladezone autolayout <preset>`.
- `Director Start`: runs `/bladeblueprint cityautoplay <preset>`.
- `Director Status`: runs `/bladeblueprint citystatus`.
- `Director Stop`: runs `/bladeblueprint citystop`.
- `Director Continue`: runs `/bladeblueprint citycontinue`.
- `Residential`, `Market`, `Workshop`, `Civic`, `Mixed`: select the active district brush for map painting.

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
- `/bladeblueprint townautocity <balanced|medieval|harbor|adaptive> [append]`
- `/bladeblueprint cityautoplay <balanced|medieval|harbor|adaptive> [append]`
- `/bladeblueprint citystatus`
- `/bladeblueprint citystop`
- `/bladeblueprint citycontinue`
- `/bladeblueprint citycancel`

### District Zones

- `/bladezone set <type>`
- `/bladezone box <type> <from> <to>`
- `/bladezone autolayout <balanced|medieval|harbor|adaptive> [append]`
- `adaptive` auto-orients district layout using detected road/water-heavy edges from the selected area.
- `/bladezone list`
- `/bladezone clear [type]`

District types:
- `residential`
- `market`
- `workshop`
- `civic`
- `mixed`

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
- `/bladeauto status`
- `/bladeauto plan`
- `/bladeauto confirm`
- `/bladeauto skip`
- `/bladeauto cancel`
- `/bladeauto clear`
- `/bladeauto remove <index>`

`/bladeauto confirm` now starts phased execution:
- `FOUNDATION -> WALLS -> ROOF -> DETAILS`

`/bladeauto cancel` now clears pending proposal and/or active phased plan.

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
- `/blademodel intent`

Automatic safety currently protects:
- storage and other block-entity blocks
- doors, trapdoors, beds, gates, signs, banners
- redstone and rails
- crops, saplings, farmland, vines, and similar growables
- portals and sensitive utility blocks like bells, campfires, beehives, and lodestones

## Local Learning Data

Bladelow now keeps local learning-oriented datasets under `config/bladelow/ml/` so build decisions can become more context-aware over time.

Files currently used:
- `placement_style_events.jsonl`
  - successful automated placements
  - manually detected player placements
- `environment_observations.jsonl`
  - terrain/build/style observations gathered from scanned city areas
- `build_intent_examples.jsonl`
  - accepted planner choices used to bias future lot/build intent decisions
- `style_refs/`
  - optional `.png/.jpg/.jpeg` style reference images
  - optional sidecar `.json` files for tags/labels

The current runtime uses these datasets as local memory and scoring hints. It is not raw image-to-build generation yet.

## City Director Flow

`/bladeblueprint cityautoplay <preset>` executes this pipeline:

1. Applies district zoning preset to selected marker area.
2. Builds district run order from zone coverage (scheduler).
3. Resolves district plan for next district.
4. Runs material auto-map/fallback from inventory.
5. Queues terrain-prep job (clear clutter + support fills).
6. Queues district build job (pathfinding + recovery handled by runtime task nodes).
7. Persists session state for resume after restart.

Director session state file:
- `config/bladelow/city-director.properties`

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

1. Open the HUD and drag the planning map to mark a build area.
2. If the green suggested plots look good, use `Shift + left-click` to snap to one.
3. In `CITY` mode, paint district zones or run `Auto Zones`.
4. Let the HUD request an intent scan for the selected area.
5. Preview (`/bladeblueprint townpreviewsel`) if you want a safe check first.
6. Run fill (`/bladeblueprint townfillsel`) or start the city director (`/bladeblueprint cityautoplay <preset>`).

## Troubleshooting

1. `#` command does nothing
- Use `/` for `bladezone` and `bladediag`.

2. Build seems stuck
- Check `/bladestatus detail`.
- Resume/pause/cancel with runtime commands.
- Try `/blademove mode auto` or `/blademove mode teleport`.

3. No blocks placed
- Select at least one palette slot (`S1/S2/S3`).
- In survival, ensure required blocks are in inventory.

4. Minimap shows no suggested plots
- Make the selected area larger.
- Flatten or clear obvious clutter/water overlap first.
- Suggested plots only appear when the selected area contains reasonably open, buildable ground.

## Internal Command Modules

`src/main/java/com/bladelow/command/`:
- `RuntimeCommands`
- `ZoneCommands`
- `MaterialResolver`
- `PaletteAssigner`
- `PlacementPipeline`
- `BladePlaceCommand` (entry + selection/blueprint registration)
