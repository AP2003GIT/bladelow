# Bladelow Builder (Fabric Mod)

Bladelow is a Fabric mod for Minecraft `1.21.11` (Java `21`) focused on assisted building, district-based town filling, layout-aware HUD planning, and increasingly automated city construction with local learning signals.

## What Exists Right Now

- Selection/marker area tools with visual minimap selection and in-world marker overlays.
- 1-3 block palette placement (slot fallback supported).
- Blueprint build/load workflows plus town template filling.
- District zoning with five types: `residential`, `market`, `workshop`, `civic`, `mixed`.
- Runtime controls: pause/continue/cancel, move tuning, diagnostics, profiles, and local intent scans.
- Automatic block safety: inventories, redstone, crops, doors, rails, utility blocks, and other sensitive blocks are skipped by default.
- Semi-autonomous planner services for queued building phases and recovery.
- City Director autopilot with ordered district queue, terrain-prep stage, material auto-resolution, and resume persistence.
- HUD with explicit flow tabs: `AREA -> BLOCKS -> SOURCE -> RUN`.
- HUD mode buttons: `SEL`, `BP`, `CITY`.
- Planning minimap that renders layout categories instead of only coordinates.
- Suggested plot overlay on the minimap for open build footprints.
- Build intent scan wired into the HUD city flow.
- Typed HUD action bridge: the active UI path is packet/action based, not chat-command based.
- Local ML-style datasets for placements, environment scans, build-intent examples, and curated style examples.
- Offline training script that turns JSONL datasets into reusable planner priors.
- HUD model status page for checking sample counts, learned themes, and offline model state.

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
- In `CITY -> SOURCE`, left-click a suggested plot to snap the current selection to that footprint.
- Suggested plots now show small preview labels such as `house`, `shop`, or `civic`.
- In `CITY` mode, applying the marker box automatically requests a build-intent scan for the selected area.
- The city status bar now shows the latest inferred intent summary from the planner.
- In `CITY` mode, inferred intent can auto-fill `S1/S2/S3` with a matching palette when the HUD has not been manually overridden.

### Main Run Controls

- `Start Build` (or `Run City Build` in `CITY` mode)
- `Pause`
- `Resume`
- `Stop Build`

### City Automation (HUD)

- `Preset`: cycles district layout preset (`medieval`, `balanced`, `harbor`, `adaptive`).
- `Auto Zones`: applies the current district preset to the selected area.
- `Director Start`: starts the staged city autoplay pipeline for the selected area.
- `Auto Build Here`: snaps to the preferred suggested plot and runs the city build flow.
- `Save Style Area`: stores the current marked area as a curated style example for later offline training.
- `Director Status`: checks the active city director state.
- `Director Stop`: pauses the city director.
- `Director Continue`: resumes the city director.
- `Residential`, `Market`, `Workshop`, `Civic`, `Mixed`: select the active district brush for map painting.
- `Model`: opens the in-HUD model status page with dataset counts and learned themes.

### HUD Hotkeys (while HUD open)

- `P`: close HUD
- `R`: run active mode
- `M`: mark point
- `C`: pause
- `V`: continue

### HUD State File

- `config/bladelow/hud-state.properties`

## Manual Command Surface

Bladelow is now `HUD-first`. Planning, zoning, blueprint execution, and model scans are driven by the HUD and minimap rather than typed commands.

The small public manual command surface is:
- `/bladepause`
- `/bladecontinue`
- `/bladecancel`
- `/bladestatus [detail]`

`#` auto-converts only for:
- `bladepause`
- `bladecontinue`
- `bladecancel`
- `bladestatus`

Everything else is now handled through the internal HUD action bridge and direct Java action services rather than a public command tree.

## Internal HUD Action Path

The active planning/build flow now goes through:

1. HUD widgets and minimap selection
2. typed `HudAction` payloads
3. packet bridge
4. direct server-side action handlers
5. planner/runtime/build services

Key internal files:
- `src/main/java/com/bladelow/network/HudAction.java`
- `src/main/java/com/bladelow/network/HudCommandPayload.java`
- `src/main/java/com/bladelow/network/HudCommandBridge.java`
- `src/main/java/com/bladelow/network/HudActionService.java`
- `src/main/java/com/bladelow/client/BladelowSelectionOverlay.java`

District types used by the planner:
- `residential`
- `market`
- `workshop`
- `civic`
- `mixed`

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
- `style_examples.jsonl`
  - player-curated areas saved from the HUD as explicit style examples
- `style_refs/`
  - optional `.png/.jpg/.jpeg` style reference images
  - optional sidecar `.json` files for tags/labels
- `offline_model.json`
  - offline-trained zone/theme priors produced from the datasets below

The current runtime uses these datasets as local memory, scoring hints, and offline-trained priors. It is not raw image-to-build generation yet.

## ML Workflow

Bladelow now has two learning paths:

1. Live learning in-game
- placement success/failure updates the lightweight placement model
- manual player placements are logged
- environment scans are logged
- accepted lot/build choices are logged

2. Offline training
- save curated style examples from the HUD
- run the offline trainer over the JSONL datasets
- Bladelow loads the generated priors back into lot intent prediction

### Save Style Examples

Use `CITY -> SOURCE -> Save Style Area` after selecting an area that represents a good local style.

Good style examples should be:
- clean
- consistent in theme
- close to the style you want Bladelow to imitate
- mostly real structures, not random terrain

### Run Offline Training

From the repo root:

```bash
python3 scripts/train_bladelow_model.py
```

This reads:
- `config/bladelow/ml/placement_style_events.jsonl`
- `config/bladelow/ml/environment_observations.jsonl`
- `config/bladelow/ml/build_intent_examples.jsonl`
- `config/bladelow/ml/style_examples.jsonl`
- `config/bladelow/ml/style_refs/`

And writes:
- `config/bladelow/ml/offline_model.json`

### Check Model Status

Open the HUD and use the `Model` button to see:
- placement sample count
- environment sample count
- build intent sample count
- style example count
- style reference image count
- whether an offline model exists
- top learned themes
- available zone priors

## City Director Flow

`Director Start` in the HUD executes this pipeline:

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
2. If the green suggested plots look good, left-click one in `CITY -> SOURCE` to snap to it.
3. In `CITY` mode, paint district zones or run `Auto Zones`.
4. Let the HUD request an intent scan for the selected area.
5. Save especially good local structures with `Save Style Area` so they become curated ML examples.
6. Use the city preview/fill flow from the HUD if you want a safe check first.
7. Run `Run City Build` for a direct district fill or `Director Start` for staged city autoplay.
8. Periodically run `python3 scripts/train_bladelow_model.py` to refresh offline priors.

## Troubleshooting

1. `#` command does nothing
- Only recovery shortcuts are supported through `#`: `bladepause`, `bladecontinue`, `bladecancel`, `bladestatus`.
- For planning/building actions, open the HUD with `P`.

2. Build seems stuck
- Check `/bladestatus detail`.
- Use the HUD `Pause`, `Resume`, or `Stop Build` controls.
- Try changing move mode from the HUD runtime controls.

3. No blocks placed
- Select at least one palette slot (`S1/S2/S3`).
- In survival, ensure required blocks are in inventory.

4. Minimap shows no suggested plots
- Make the selected area larger.
- Flatten or clear obvious clutter/water overlap first.
- Suggested plots only appear when the selected area contains reasonably open, buildable ground.

## Internal Runtime Modules

`src/main/java/com/bladelow/command/`:
- `ManualRecoveryCommands`
- `MaterialResolver`
- `PaletteAssigner`
- `PlacementPipeline`

`src/main/java/com/bladelow/network/`:
- `HudAction`
- `HudCommandPayload`
- `HudCommandBridge`
- `HudActionService`
