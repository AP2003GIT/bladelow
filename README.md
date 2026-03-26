# Bladelow Builder

Bladelow is a Fabric mod for Minecraft `1.21.11` on Java `21` focused on HUD-first building automation.

The current direction is:
- select an area from the in-game planning map
- let Bladelow scan terrain, nearby structures, and district context
- infer a fitting build intent and palette
- generate or queue a build
- run the build with pathing, safety checks, and recovery controls

Bladelow is no longer command-first. The normal workflow is driven through the HUD opened with `P`.

## Current State

Bladelow currently includes:
- visual area selection through an in-game planning map and marker overlay
- three main HUD modes: `SEL`, `BP`, `CITY`
- district zoning with `residential`, `market`, `workshop`, `civic`, and `mixed`
- suggested build plots inside a selected city area
- intent scanning that reads nearby style, scale, and context
- single-plot `Auto Build Here` generation for one road-facing building at a time
- city director automation for staged district building across a larger area
- automatic safety rules to avoid damaging sensitive blocks
- local learning datasets for style, environment, and build-intent memory
- offline training support for turning JSONL logs into reusable planning priors

## What Bladelow Is Not Yet

Bladelow is not yet:
- raw image-to-build generation
- video-to-build generation
- a full autonomous city designer that invents everything from scratch without constraints
- a replacement for human review on important builds

The current strength is structured automation guided by world scans, saved style examples, and local training data.

## Requirements

- Minecraft `1.21.11`
- Fabric Loader
- Java `21`

## Install

1. Download the latest jar from:
   - `https://github.com/AP2003GIT/bladelow/releases`
2. Put the jar into your Fabric mods folder.
3. Launch Minecraft.
4. Press `P` in-game to open the Bladelow HUD.

Lunar example path:
- `C:\Users\<YourUser>\.lunarclient\profiles\vanilla\1.21\mods\fabric-1.21.11\`

## Build From Source

```bash
cd "/home/p90lo/project bladelow/minecraft-bladelow"
./gradlew clean build
```

Jar output:
- `build/libs/minecraft-bladelow-0.1.0.jar`

WSL to Lunar copy example:

```bash
cp "build/libs/minecraft-bladelow-0.1.0.jar" "/mnt/c/Users/<YourUser>/.lunarclient/profiles/vanilla/1.21/mods/fabric-1.21.11/"
```

Helper scripts:
- `scripts/install-lunar-wsl.sh`
- `scripts/deploy-lunar-wsl.sh`
- `scripts/install-lunar-windows.ps1`

## Core Workflow

The intended flow now is:

1. Open the HUD with `P`
2. Select `CITY`
3. Drag on the planning map to mark an area
4. Click a suggested plot or let Bladelow choose the best one
5. Let Bladelow infer build intent and palette
6. Press `Auto Build Here` for one generated structure
7. Use the director flow when you want a larger staged district pass

For direct/manual workflows:
- `SEL` is for marker-based selection and controlled builds
- `BP` is for blueprint-oriented workflows and town template loading
- `CITY` is for district planning, plot selection, generation, and automation

## HUD Overview

### Modes

- `SEL`: selection tools and controlled build setup
- `BP`: blueprint/town-template oriented flow
- `CITY`: district planning, intent scanning, and automation

### Flows

Bladelow uses flow tabs instead of chat commands.

General flow:
- `AREA`
- `BLOCKS`
- `SOURCE`
- `RUN`

Compact city flow:
- `AREA`
- `SOURCE`
- `RUN`

In `CITY` mode, `BLOCKS` is hidden by default because palette selection is automated. It only reappears when palette override is enabled.

### Planning Map

The planning map is the main interaction surface for non-block steps.

What it does:
- drag to select or resize the active area
- right-click to clear the current area
- show a layout-style map rather than raw coordinates
- render water, roads, structures, vegetation, open ground, and terrain separately
- show plot suggestions inside the selected city area
- let you click a suggested plot to snap the selection to that footprint
- show small plot labels such as `house`, `shop`, or `civic`

Map colors are layout-oriented, not chunk-map exact. The point is planning clarity.

### CITY Mode

`CITY` is the most automated workflow.

The right-side panel currently supports:
- district brush selection
- district preset cycling
- auto zoning
- city director start, stop, continue, and status
- single-plot `Auto Build Here`
- saving the current area as a style example
- model status page

### Palette Automation

In `CITY` mode, palette selection is automatic by default.

Bladelow treats the three palette roles as:
- `Primary`
- `Trim`
- `Roof`

Default behavior:
- infer palette from local style and current intent
- auto-fill the palette roles when useful
- build without requiring manual block selection

Optional behavior:
- enable `Palette: OVERRIDE`
- manually pick `Primary`, `Trim`, and `Roof`
- those overrides will be passed into `Auto Build Here`

## Auto Build Here

`Auto Build Here` is the current bridge between ML-style intent prediction and actual structure generation.

When used, Bladelow:
1. picks the selected or best suggested plot
2. scans terrain and nearby structures
3. infers a `BuildIntent`
4. chooses footprint, facing, floors, roof, and palette
5. generates one exterior-first building plan
6. adds support/foundation blocks where needed
7. queues the build through the normal runtime

Current limitations:
- focused on one generated building at a time
- exterior quality is prioritized over rich interiors
- overlapping plots are rejected rather than forced

## City Director

The city director is the staged automation path for larger district work.

The current pipeline is:
1. apply or read district zoning for the selected area
2. build district run order
3. resolve district plan
4. map/fallback materials
5. queue terrain preparation
6. queue build jobs
7. persist session state for resume

Director session state file:
- `config/bladelow/city-director.properties`

## Manual Recovery Commands

Bladelow is HUD-first, but a tiny safety-valve command surface remains.

Supported commands:
- `/bladepause`
- `/bladecontinue`
- `/bladecancel`
- `/bladestatus`
- `/bladestatus detail`

The `#` shortcut only maps to those same recovery actions.

Everything else should be done through the HUD.

## Safety Rules

Bladelow automatically avoids editing sensitive blocks.

Protected categories include:
- storage and block-entity blocks
- doors, trapdoors, gates, beds, signs, banners
- redstone parts and rails
- crops, saplings, farmland, vines, and similar growables
- portals and sensitive utility blocks such as bells, campfires, beehives, and lodestones

This safety logic is designed to keep automation from wrecking existing bases and infrastructure.

## Blueprint and Town Templates

Blueprint support still exists, even though the project is moving toward more automated generation.

Relevant paths:
- `config/bladelow/blueprints/*.json`
- `src/main/java/com/bladelow/builder/BlueprintLibrary.java`
- `src/main/java/com/bladelow/builder/TownPlanner.java`

Blueprints and town templates are still useful for:
- controlled testing
- district filling
- comparing template-driven versus generated results
- bootstrapping the planner while the ML and generation systems mature

## Local Learning and Training

Bladelow now collects local data so it can make better decisions over time.

Data lives under:
- `config/bladelow/ml/`

Current datasets:
- `placement_style_events.jsonl`
  - successful automated placements
  - tracked manual player placements
- `environment_observations.jsonl`
  - scanned terrain/build/style summaries
- `build_intent_examples.jsonl`
  - accepted planner/build-intent decisions
- `style_examples.jsonl`
  - curated style areas saved from the HUD
- `style_refs/`
  - optional style reference images and sidecar metadata
- `offline_model.json`
  - offline-trained priors loaded back into runtime

### What Gets Learned

Bladelow currently learns from:
- successful and failed placement context
- nearby building scale and style
- saved style areas
- repeated build-intent choices for similar plots
- optional image style references

This is still structured learning, not raw generative AI.

### Save Style Examples

Use `CITY -> SOURCE -> Save Style Area` when you have selected a good example area.

Good style examples are:
- consistent in theme
- mostly real structures, not noise or terrain
- close to the style you want future builds to follow
- representative of district scale and palette

### Offline Trainer

Run from the repo root:

```bash
python3 scripts/train_bladelow_model.py
```

It reads:
- `config/bladelow/ml/placement_style_events.jsonl`
- `config/bladelow/ml/environment_observations.jsonl`
- `config/bladelow/ml/build_intent_examples.jsonl`
- `config/bladelow/ml/style_examples.jsonl`
- `config/bladelow/ml/style_refs/`

It writes:
- `config/bladelow/ml/offline_model.json`

### Model Status Page

Open the HUD and use `Model` to inspect:
- placement sample count
- environment sample count
- build intent sample count
- style example count
- style reference image count
- whether an offline model exists
- learned themes
- available zone priors

## Recommended Training Loop

A practical loop for improving Bladelow is:

1. Build or keep a few good example structures in one area
2. Mark clean city areas with the HUD
3. Save especially good areas with `Save Style Area`
4. Let Bladelow run `Auto Build Here` on nearby plots
5. Use the recovery commands only if something gets stuck
6. Periodically run the offline trainer
7. Repeat in one style at a time so the data stays coherent

## Important Files

User-facing core pieces:
- `src/main/java/com/bladelow/client/ui/BladelowHudScreen.java`
- `src/main/java/com/bladelow/client/BladelowSelectionOverlay.java`
- `src/main/java/com/bladelow/network/HudAction.java`
- `src/main/java/com/bladelow/network/HudCommandPayload.java`
- `src/main/java/com/bladelow/network/HudCommandBridge.java`
- `src/main/java/com/bladelow/network/HudActionService.java`

Planning and generation:
- `src/main/java/com/bladelow/builder/IntentStructurePlanner.java`
- `src/main/java/com/bladelow/builder/BuildSiteAnalyzer.java`
- `src/main/java/com/bladelow/builder/TownPlanner.java`
- `src/main/java/com/bladelow/builder/TownAutoLayoutPlanner.java`
- `src/main/java/com/bladelow/auto/CityAutoplayDirector.java`

Learning and training:
- `src/main/java/com/bladelow/ml/BladelowLearning.java`
- `src/main/java/com/bladelow/ml/BuildIntentPredictor.java`
- `src/main/java/com/bladelow/ml/StyleExampleLogger.java`
- `src/main/java/com/bladelow/ml/OfflineTrainingModel.java`
- `scripts/train_bladelow_model.py`

Recovery/runtime:
- `src/main/java/com/bladelow/command/ManualRecoveryCommands.java`
- `src/main/java/com/bladelow/builder/PlacementJobRunner.java`
- `src/main/java/com/bladelow/builder/BuildSafetyPolicy.java`

## Config and State Files

Common files you may care about:
- `config/bladelow/hud-state.properties`
- `config/bladelow/model.properties`
- `config/bladelow/city-director.properties`
- `config/bladelow/ml/offline_model.json`
- `config/bladelow/blueprints/*.json`

## Troubleshooting

### HUD looks wrong or misaligned

- Make sure you are on the latest jar.
- Reopen the HUD with `P`.
- If needed, reset the HUD state file:
  - `config/bladelow/hud-state.properties`

### Auto Build Here does nothing

- Make sure an area or suggested plot is selected.
- Check that the chosen plot is not overlapping an existing structure.
- Try another suggested plot.
- Use `Model` to confirm the intent system is active.

### No suggested plots appear

- Select a larger area.
- Avoid water-heavy or clutter-heavy spaces.
- Flatten obvious obstacles if needed.

### Build seems stuck

- Run `/bladestatus detail`
- Use `/bladepause`, `/bladecontinue`, or `/bladecancel`
- Try another plot or simplify the selected area

### Palette is wrong

- Leave `Palette: AUTO` on if you want Bladelow to infer style
- Switch to `Palette: OVERRIDE` if you want to force `Primary`, `Trim`, and `Roof`

## Direction

The target direction for Bladelow is:
- open HUD
- select area on the minimap
- let Bladelow understand the terrain and surrounding style
- generate or choose a fitting build
- build it safely
- stop when finished

That is the path the current systems are being built toward.
