package com.bladelow.builder;

import com.bladelow.ml.BladelowLearning;
import com.bladelow.ml.BuildIntent;
import com.bladelow.ml.BuildIntentContext;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Generates one concrete house plan from the current ML intent and local plot.
 *
 * This is the bridge between "the model thinks this lot wants a small market
 * house" and "here is the actual block plan to queue." The output stays fully
 * deterministic so it can be debugged and iterated on more easily than a fully
 * generative system.
 */
public final class IntentStructurePlanner {
    private static final Set<String> ROAD_HINTS = Set.of(
        "path",
        "gravel",
        "cobblestone",
        "stone_bricks",
        "stonebrick",
        "brick",
        "planks",
        "mud_bricks",
        "deepslate_tiles",
        "andesite"
    );

    private IntentStructurePlanner() {
    }

    public static GeneratedBuild planSelection(
        ServerWorld world,
        UUID playerId,
        BlockPos from,
        BlockPos to,
        String plotLabel,
        List<String> slotOverrides
    ) {
        if (world == null || from == null || to == null) {
            return GeneratedBuild.error("invalid auto-build bounds");
        }

        int minX = Math.min(from.getX(), to.getX());
        int maxX = Math.max(from.getX(), to.getX());
        int minY = Math.min(from.getY(), to.getY());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxZ = Math.max(from.getZ(), to.getZ());
        int plotWidth = maxX - minX + 1;
        int plotDepth = maxZ - minZ + 1;
        if (plotWidth < 5 || plotDepth < 5) {
            return GeneratedBuild.error("selected plot is too small for auto build");
        }

        List<TownZoneStore.Zone> zones = playerId == null ? List.of() : TownZoneStore.snapshot(playerId, world.getRegistryKey());
        TownPlanner.IntentSuggestion suggestion = TownPlanner.suggestBuildIntent(
            world,
            new BlockPos(minX, minY, minZ),
            new BlockPos(maxX, minY, maxZ),
            zones
        );
        if (!suggestion.ok()) {
            return GeneratedBuild.error(suggestion.message());
        }

        BuildSiteScan scan = BuildSiteAnalyzer.scan(
            world,
            new BlockPos(minX, minY, minZ),
            new BlockPos(maxX, minY, maxZ),
            minY,
            Set.of()
        );
        if (overlapsExistingStructure(scan, minX, maxX, minZ, maxZ)) {
            return GeneratedBuild.error("selected plot overlaps an existing structure");
        }

        BuildIntent intent = withPlotHint(suggestion.intent(), plotLabel);
        BuildIntentContext context = suggestion.context();
        String archetype = effectiveArchetype(intent, plotLabel);
        String roadSide = detectRoadSide(world, minX, maxX, minY, minZ, maxZ);
        MaterialSet materials = chooseMaterials(intent, scan, archetype, slotOverrides);

        int bodyWidth = chooseFootprintWidth(plotWidth, context, intent, archetype);
        int bodyDepth = chooseFootprintDepth(plotDepth, context, intent, archetype);
        int floors = Math.max(1, Math.min(3, intent.floors() <= 0 ? 1 : intent.floors()));

        int originX = placeOriginX(minX, maxX, bodyWidth, roadSide);
        int originZ = placeOriginZ(minZ, maxZ, bodyDepth, roadSide);
        int floorY = highestTerrainY(world, originX, originZ, bodyWidth, bodyDepth) + 1;

        TownBlueprint blueprint = generateBlueprint(
            archetype,
            roadSide,
            bodyWidth,
            bodyDepth,
            floors,
            intent,
            materials
        );
        BlueprintLibrary.BuildPlan buildPlan = resolveGeneratedPlan(world, blueprint, originX, floorY, originZ, materials.foundation());
        if (!buildPlan.ok()) {
            return GeneratedBuild.error(buildPlan.message());
        }

        BladelowLearning.buildIntentLogger().recordTownPlacement("auto_build_here", world, context, blueprint);
        String message = "auto-build " + blueprint.name()
            + " " + bodyWidth + "x" + bodyDepth
            + " floors=" + floors
            + " road=" + roadSide
            + " intent=" + intent.summary();
        return GeneratedBuild.ok(message, intent, context, blueprint, buildPlan.blockStates(), buildPlan.targets());
    }

    private static BlueprintLibrary.BuildPlan resolveGeneratedPlan(
        ServerWorld world,
        TownBlueprint blueprint,
        int originX,
        int floorY,
        int originZ,
        String foundationBlock
    ) {
        List<BlockState> states = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();

        for (int x = 0; x < blueprint.plotWidth(); x++) {
            for (int z = 0; z < blueprint.plotDepth(); z++) {
                int worldX = originX + x;
                int worldZ = originZ + z;
                int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1;
                for (int y = topY + 1; y < floorY; y++) {
                    BlockState state = BlueprintStateCodec.tryParse(foundationBlock);
                    if (state == null) {
                        return BlueprintLibrary.BuildPlan.error("invalid generated foundation block: " + foundationBlock);
                    }
                    states.add(state);
                    targets.add(new BlockPos(worldX, y, worldZ));
                }
            }
        }

        for (TownBlueprint.Placement placement : blueprint.placements()) {
            BlockState state = BlueprintStateCodec.tryParse(placement.blockId());
            if (state == null) {
                return BlueprintLibrary.BuildPlan.error("invalid generated block: " + placement.blockId());
            }
            states.add(state);
            targets.add(new BlockPos(originX + placement.x(), floorY + placement.y(), originZ + placement.z()));
        }
        return BlueprintLibrary.BuildPlan.ok(blueprint.name(), states, targets);
    }

    private static TownBlueprint generateBlueprint(
        String archetype,
        String roadSide,
        int width,
        int depth,
        int floors,
        BuildIntent intent,
        MaterialSet materials
    ) {
        List<TownBlueprint.Placement> placements = new ArrayList<>();
        int storyHeight = 4;
        int bodyTop = floors * storyHeight;
        int doorOffset = entranceOffset(roadSide, width, depth);

        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                placements.add(new TownBlueprint.Placement(x, 0, z, materials.floor()));
            }
        }

        for (int floor = 0; floor < floors; floor++) {
            int wallBase = 1 + (floor * storyHeight);
            int wallTop = wallBase + storyHeight - 1;
            for (int y = wallBase; y <= wallTop; y++) {
                for (int x = 0; x < width; x++) {
                    for (int z = 0; z < depth; z++) {
                        if (!isPerimeter(x, z, width, depth)) {
                            continue;
                        }
                        if (isDoorOpening(roadSide, x, y, z, width, depth, doorOffset, wallBase)) {
                            continue;
                        }
                        String block = selectWallBlock(x, z, y, wallTop, width, depth, materials);
                        if (isWindowCell(roadSide, x, y, z, width, depth, wallBase, wallTop, floor, floors, archetype)) {
                            block = materials.glass();
                        }
                        placements.add(new TownBlueprint.Placement(x, y, z, block));
                    }
                }
            }

            if (floor < floors - 1) {
                for (int x = 1; x < width - 1; x++) {
                    for (int z = 1; z < depth - 1; z++) {
                        placements.add(new TownBlueprint.Placement(x, wallTop, z, materials.floor()));
                    }
                }
            }
        }

        addRoof(placements, width, depth, bodyTop + 1, roofLayers(intent, width, depth), materials);
        addEntranceDetail(placements, roadSide, width, depth, doorOffset, materials, archetype);

        String name = "generated_" + archetype + "_" + intent.sizeClass();
        return new TownBlueprint(
            name,
            "generated",
            List.copyOf(placements),
            width,
            depth,
            100,
            entranceX(roadSide, width, depth, doorOffset),
            entranceZ(roadSide, width, depth, doorOffset),
            roadSide,
            0,
            themeTags(intent),
            blueprintTags(archetype, intent)
        );
    }

    private static void addRoof(
        List<TownBlueprint.Placement> placements,
        int width,
        int depth,
        int roofBaseY,
        int layers,
        MaterialSet materials
    ) {
        boolean slopeNorthSouth = width >= depth;
        int maxLayers = slopeNorthSouth ? Math.max(1, (depth + 1) / 2) : Math.max(1, (width + 1) / 2);
        int roofLayers = Math.min(layers, maxLayers);

        for (int layer = 0; layer < roofLayers; layer++) {
            if (slopeNorthSouth) {
                int northZ = layer;
                int southZ = depth - 1 - layer;
                for (int x = 0; x < width; x++) {
                    placements.add(new TownBlueprint.Placement(x, roofBaseY + layer, northZ, stair("north", materials.roofStair())));
                    if (southZ != northZ) {
                        placements.add(new TownBlueprint.Placement(x, roofBaseY + layer, southZ, stair("south", materials.roofStair())));
                    }
                    for (int z = northZ + 1; z < southZ; z++) {
                        placements.add(new TownBlueprint.Placement(x, roofBaseY + layer, z, materials.roofSolid()));
                    }
                }
            } else {
                int westX = layer;
                int eastX = width - 1 - layer;
                for (int z = 0; z < depth; z++) {
                    placements.add(new TownBlueprint.Placement(westX, roofBaseY + layer, z, stair("west", materials.roofStair())));
                    if (eastX != westX) {
                        placements.add(new TownBlueprint.Placement(eastX, roofBaseY + layer, z, stair("east", materials.roofStair())));
                    }
                    for (int x = westX + 1; x < eastX; x++) {
                        placements.add(new TownBlueprint.Placement(x, roofBaseY + layer, z, materials.roofSolid()));
                    }
                }
            }
        }
    }

    private static void addEntranceDetail(
        List<TownBlueprint.Placement> placements,
        String roadSide,
        int width,
        int depth,
        int doorOffset,
        MaterialSet materials,
        String archetype
    ) {
        switch (roadSide) {
            case "north" -> {
                placements.add(new TownBlueprint.Placement(doorOffset, 1, 0, materials.detail()));
                if ("market".equals(archetype) && width >= 7) {
                    for (int x = Math.max(1, doorOffset - 2); x <= Math.min(width - 2, doorOffset + 2); x++) {
                        placements.add(new TownBlueprint.Placement(x, 3, 0, materials.accent()));
                    }
                }
            }
            case "south" -> {
                placements.add(new TownBlueprint.Placement(doorOffset, 1, depth - 1, materials.detail()));
                if ("market".equals(archetype) && width >= 7) {
                    for (int x = Math.max(1, doorOffset - 2); x <= Math.min(width - 2, doorOffset + 2); x++) {
                        placements.add(new TownBlueprint.Placement(x, 3, depth - 1, materials.accent()));
                    }
                }
            }
            case "west" -> {
                placements.add(new TownBlueprint.Placement(0, 1, doorOffset, materials.detail()));
                if ("market".equals(archetype) && depth >= 7) {
                    for (int z = Math.max(1, doorOffset - 2); z <= Math.min(depth - 2, doorOffset + 2); z++) {
                        placements.add(new TownBlueprint.Placement(0, 3, z, materials.accent()));
                    }
                }
            }
            case "east" -> {
                placements.add(new TownBlueprint.Placement(width - 1, 1, doorOffset, materials.detail()));
                if ("market".equals(archetype) && depth >= 7) {
                    for (int z = Math.max(1, doorOffset - 2); z <= Math.min(depth - 2, doorOffset + 2); z++) {
                        placements.add(new TownBlueprint.Placement(width - 1, 3, z, materials.accent()));
                    }
                }
            }
            default -> {
            }
        }
    }

    private static boolean overlapsExistingStructure(BuildSiteScan scan, int minX, int maxX, int minZ, int maxZ) {
        if (scan == null || scan == BuildSiteScan.EMPTY) {
            return false;
        }
        int selectionArea = Math.max(1, (maxX - minX + 1) * (maxZ - minZ + 1));
        for (BuildSiteScan.NearbyStructure structure : scan.nearbyStructures()) {
            int overlapW = Math.min(maxX, structure.maxX()) - Math.max(minX, structure.minX()) + 1;
            int overlapD = Math.min(maxZ, structure.maxZ()) - Math.max(minZ, structure.minZ()) + 1;
            if (overlapW <= 0 || overlapD <= 0) {
                continue;
            }
            int overlapArea = overlapW * overlapD;
            if (overlapArea >= Math.max(6, selectionArea / 6)) {
                return true;
            }
        }
        return false;
    }

    private static BuildIntent withPlotHint(BuildIntent intent, String plotLabel) {
        if (intent == null) {
            return BuildIntent.NONE;
        }
        String hinted = effectiveArchetype(intent, plotLabel);
        if (hinted.equals(intent.primaryArchetype())) {
            return intent;
        }
        return new BuildIntent(
            hinted,
            intent.sizeClass(),
            intent.floors(),
            intent.roofFamily(),
            intent.paletteProfile(),
            intent.detailDensity(),
            intent.primaryTheme(),
            intent.secondaryTheme(),
            Math.max(0.2, intent.confidence()),
            intent.matchedExamples()
        );
    }

    private static String effectiveArchetype(BuildIntent intent, String plotLabel) {
        String label = normalize(plotLabel);
        if (label.contains("shop") || label.contains("market") || label.contains("inn")) {
            return "market";
        }
        if (label.contains("civic") || label.contains("hall") || label.contains("tower") || label.contains("church")) {
            return "civic";
        }
        if (label.contains("workshop") || label.contains("smith") || label.contains("forge")) {
            return "workshop";
        }
        if (label.contains("house") || label.contains("home") || label.contains("res")) {
            return "residential";
        }
        if (intent == null || intent.primaryArchetype().isBlank()) {
            return "residential";
        }
        return intent.primaryArchetype();
    }

    private static int chooseFootprintWidth(int plotWidth, BuildIntentContext context, BuildIntent intent, String archetype) {
        int base = switch (archetype) {
            case "civic" -> 11;
            case "market" -> 8;
            case "workshop" -> 8;
            default -> 7;
        };
        if ("medium".equals(intent.sizeClass())) {
            base += 1;
        } else if ("large".equals(intent.sizeClass())) {
            base += 2;
        }
        if (context != null && context.styleAverageWidth() > 1.0) {
            base = (int) Math.round((base + context.styleAverageWidth()) * 0.5);
        }
        int max = plotWidth >= 8 ? plotWidth - 2 : plotWidth;
        return Math.max(5, Math.min(max, base));
    }

    private static int chooseFootprintDepth(int plotDepth, BuildIntentContext context, BuildIntent intent, String archetype) {
        int base = switch (archetype) {
            case "civic" -> 9;
            case "market" -> 7;
            case "workshop" -> 6;
            default -> 6;
        };
        if ("medium".equals(intent.sizeClass())) {
            base += 1;
        } else if ("large".equals(intent.sizeClass())) {
            base += 2;
        }
        if (context != null && context.styleAverageDepth() > 1.0) {
            base = (int) Math.round((base + context.styleAverageDepth()) * 0.5);
        }
        int max = plotDepth >= 8 ? plotDepth - 2 : plotDepth;
        return Math.max(5, Math.min(max, base));
    }

    private static int placeOriginX(int minX, int maxX, int width, String roadSide) {
        int available = maxX - minX + 1;
        int centered = minX + Math.max(0, (available - width) / 2);
        return switch (roadSide) {
            case "west" -> minX + 1;
            case "east" -> maxX - width;
            default -> clamp(centered, minX, maxX - width + 1);
        };
    }

    private static int placeOriginZ(int minZ, int maxZ, int depth, String roadSide) {
        int available = maxZ - minZ + 1;
        int centered = minZ + Math.max(0, (available - depth) / 2);
        return switch (roadSide) {
            case "north" -> minZ + 1;
            case "south" -> maxZ - depth;
            default -> clamp(centered, minZ, maxZ - depth + 1);
        };
    }

    private static int highestTerrainY(ServerWorld world, int originX, int originZ, int width, int depth) {
        int highest = world.getBottomY();
        for (int x = originX; x < originX + width; x++) {
            for (int z = originZ; z < originZ + depth; z++) {
                highest = Math.max(highest, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1);
            }
        }
        return highest;
    }

    private static MaterialSet chooseMaterials(BuildIntent intent, BuildSiteScan scan, String archetype, List<String> slotOverrides) {
        String palette = intent == null ? "" : normalize(intent.paletteProfile());
        String primaryTheme = intent == null ? "" : normalize(intent.primaryTheme());
        if (primaryTheme.isBlank() && scan != null) {
            primaryTheme = normalize(scan.styleProfile().primaryTheme());
        }

        String foundation = "minecraft:stone_bricks";
        String floor = "minecraft:oak_planks";
        String wall = "minecraft:oak_planks";
        String trim = "minecraft:stripped_oak_log";
        String glass = "minecraft:glass_pane";
        String roofStair = "minecraft:spruce_stairs";
        String roofSolid = "minecraft:spruce_planks";
        String detail = "minecraft:oak_trapdoor";
        String accent = "minecraft:red_wool";

        if (palette.contains("plaster")) {
            wall = "minecraft:calcite";
            trim = "minecraft:stripped_oak_log";
            roofStair = "minecraft:dark_oak_stairs";
            roofSolid = "minecraft:dark_oak_planks";
        } else if (palette.contains("stone") || "stone".equals(primaryTheme) || "civic".equals(archetype)) {
            wall = "minecraft:stone_bricks";
            trim = "minecraft:polished_andesite";
            roofStair = "minecraft:deepslate_brick_stairs";
            roofSolid = "minecraft:deepslate_bricks";
            detail = "minecraft:stone_brick_slab[type=top]";
        }

        if ("market".equals(archetype)) {
            accent = palette.contains("market") ? "minecraft:red_wool" : "minecraft:orange_wool";
            detail = "minecraft:oak_trapdoor[half=top,facing=north,open=false,powered=false,waterlogged=false]";
        } else if ("workshop".equals(archetype)) {
            detail = "minecraft:stone_brick_slab[type=top,waterlogged=false]";
        } else if ("civic".equals(archetype)) {
            floor = "minecraft:stone_bricks";
            glass = "minecraft:glass";
        }

        if (slotOverrides != null && !slotOverrides.isEmpty()) {
            if (slotOverrides.size() > 0 && validBlock(slotOverrides.get(0))) {
                wall = slotOverrides.get(0);
            }
            if (slotOverrides.size() > 1 && validBlock(slotOverrides.get(1))) {
                trim = slotOverrides.get(1);
                foundation = slotOverrides.get(1);
            }
            if (slotOverrides.size() > 2 && validBlock(slotOverrides.get(2))) {
                roofSolid = solidRoofVariant(slotOverrides.get(2));
                roofStair = stairRoofVariant(slotOverrides.get(2));
            }
        }

        if (!validState(detail)) {
            detail = trim;
        }
        return new MaterialSet(foundation, floor, wall, trim, glass, roofStair, roofSolid, detail, accent);
    }

    private static String detectRoadSide(ServerWorld world, int minX, int maxX, int baseY, int minZ, int maxZ) {
        int north = 0;
        int south = 0;
        int west = 0;
        int east = 0;
        for (int x = minX; x <= maxX; x++) {
            north += roadLike(world, x, baseY, minZ - 1) ? 1 : 0;
            south += roadLike(world, x, baseY, maxZ + 1) ? 1 : 0;
        }
        for (int z = minZ; z <= maxZ; z++) {
            west += roadLike(world, minX - 1, baseY, z) ? 1 : 0;
            east += roadLike(world, maxX + 1, baseY, z) ? 1 : 0;
        }
        int best = Math.max(Math.max(north, south), Math.max(west, east));
        if (best <= 0) {
            return "south";
        }
        if (best == north) {
            return "north";
        }
        if (best == south) {
            return "south";
        }
        if (best == west) {
            return "west";
        }
        return "east";
    }

    private static boolean roadLike(ServerWorld world, int x, int baseY, int z) {
        BlockState state = world.getBlockState(new BlockPos(x, baseY, z));
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        if (id == null) {
            return false;
        }
        String path = id.getPath().toLowerCase(Locale.ROOT);
        for (String hint : ROAD_HINTS) {
            if (path.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static int roofLayers(BuildIntent intent, int width, int depth) {
        int max = Math.max(1, Math.min(width, depth) / 2);
        String roofFamily = intent == null ? "" : normalize(intent.roofFamily());
        int requested = switch (roofFamily) {
            case "low" -> 2;
            case "gable" -> 3;
            case "steep" -> 4;
            default -> 3;
        };
        return Math.max(1, Math.min(max, requested));
    }

    private static boolean isPerimeter(int x, int z, int width, int depth) {
        return x == 0 || z == 0 || x == width - 1 || z == depth - 1;
    }

    private static boolean isDoorOpening(String roadSide, int x, int y, int z, int width, int depth, int doorOffset, int wallBase) {
        if (y < wallBase || y > wallBase + 1) {
            return false;
        }
        return switch (roadSide) {
            case "north" -> z == 0 && x == doorOffset;
            case "south" -> z == depth - 1 && x == doorOffset;
            case "west" -> x == 0 && z == doorOffset;
            case "east" -> x == width - 1 && z == doorOffset;
            default -> false;
        };
    }

    private static boolean isWindowCell(
        String roadSide,
        int x,
        int y,
        int z,
        int width,
        int depth,
        int wallBase,
        int wallTop,
        int floor,
        int floors,
        String archetype
    ) {
        if (y != wallBase + 1) {
            return false;
        }
        boolean front = switch (roadSide) {
            case "north" -> z == 0;
            case "south" -> z == depth - 1;
            case "west" -> x == 0;
            case "east" -> x == width - 1;
            default -> false;
        };
        boolean side = (x == 0 || x == width - 1 || z == 0 || z == depth - 1) && !front;
        if (!(front || side)) {
            return false;
        }
        if ("civic".equals(archetype) && floor == floors - 1 && y == wallTop - 1) {
            return true;
        }
        return switch (roadSide) {
            case "north", "south" -> (x - 1) % 3 == 0 && x > 0 && x < width - 1;
            case "west", "east" -> (z - 1) % 3 == 0 && z > 0 && z < depth - 1;
            default -> false;
        };
    }

    private static String selectWallBlock(int x, int z, int y, int wallTop, int width, int depth, MaterialSet materials) {
        if (x == 0 || x == width - 1 || z == 0 || z == depth - 1) {
            if (x == 0 || x == width - 1) {
                if (z == 0 || z == depth - 1) {
                    return materials.trim();
                }
            }
            if (y == wallTop) {
                return materials.trim();
            }
            return materials.wall();
        }
        return materials.wall();
    }

    private static int entranceOffset(String roadSide, int width, int depth) {
        return switch (roadSide) {
            case "north", "south" -> width / 2;
            case "west", "east" -> depth / 2;
            default -> width / 2;
        };
    }

    private static int entranceX(String roadSide, int width, int depth, int doorOffset) {
        return switch (roadSide) {
            case "north", "south" -> doorOffset;
            case "west" -> 0;
            case "east" -> width - 1;
            default -> width / 2;
        };
    }

    private static int entranceZ(String roadSide, int width, int depth, int doorOffset) {
        return switch (roadSide) {
            case "north" -> 0;
            case "south" -> depth - 1;
            case "west", "east" -> doorOffset;
            default -> depth - 1;
        };
    }

    private static List<String> themeTags(BuildIntent intent) {
        List<String> tags = new ArrayList<>();
        if (intent != null) {
            if (!intent.primaryTheme().isBlank()) {
                tags.add(intent.primaryTheme());
            }
            if (!intent.secondaryTheme().isBlank() && !intent.secondaryTheme().equals(intent.primaryTheme())) {
                tags.add(intent.secondaryTheme());
            }
        }
        return List.copyOf(tags);
    }

    private static List<String> blueprintTags(String archetype, BuildIntent intent) {
        List<String> tags = new ArrayList<>();
        tags.add("generated");
        if (!archetype.isBlank()) {
            tags.add(archetype);
        }
        if (intent != null) {
            if (!intent.primaryArchetype().isBlank() && !intent.primaryArchetype().equals(archetype)) {
                tags.add(intent.primaryArchetype());
            }
            if (!intent.sizeClass().isBlank()) {
                tags.add(intent.sizeClass());
            }
            if (!intent.detailDensity().isBlank()) {
                tags.add(intent.detailDensity());
            }
        }
        return List.copyOf(tags);
    }

    private static String stair(String facing, String stairBlock) {
        String normalized = stairRoofVariant(stairBlock);
        return normalized + "[facing=" + facing + ",half=bottom,shape=straight,waterlogged=false]";
    }

    private static String stairRoofVariant(String blockId) {
        String normalized = normalize(blockId);
        if (normalized.endsWith("_stairs")) {
            return normalized;
        }
        if (normalized.endsWith("_slab")) {
            return normalized.substring(0, normalized.length() - 5) + "_stairs";
        }
        if (normalized.endsWith("_planks")) {
            return normalized.substring(0, normalized.length() - 7) + "_stairs";
        }
        if (normalized.endsWith("_bricks")) {
            return normalized.substring(0, normalized.length() - 7) + "_brick_stairs";
        }
        if (normalized.endsWith("_brick")) {
            return normalized + "_stairs";
        }
        return "minecraft:spruce_stairs";
    }

    private static String solidRoofVariant(String blockId) {
        String normalized = normalize(blockId);
        if (normalized.endsWith("_stairs")) {
            String prefix = normalized.substring(0, normalized.length() - 7);
            if (Identifier.tryParse(prefix + "_planks") != null && Registries.BLOCK.containsId(Identifier.of(prefix + "_planks"))) {
                return prefix + "_planks";
            }
            if (Identifier.tryParse(prefix + "_bricks") != null && Registries.BLOCK.containsId(Identifier.of(prefix + "_bricks"))) {
                return prefix + "_bricks";
            }
        }
        return validBlock(normalized) ? normalized : "minecraft:spruce_planks";
    }

    private static boolean validBlock(String blockId) {
        String normalized = normalize(blockId);
        Identifier id = Identifier.tryParse(normalized);
        return id != null && Registries.BLOCK.containsId(id);
    }

    private static boolean validState(String blockState) {
        return BlueprintStateCodec.tryParse(blockState) != null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private record MaterialSet(
        String foundation,
        String floor,
        String wall,
        String trim,
        String glass,
        String roofStair,
        String roofSolid,
        String detail,
        String accent
    ) {
    }

    public record GeneratedBuild(
        boolean ok,
        String message,
        BuildIntent intent,
        BuildIntentContext context,
        TownBlueprint blueprint,
        List<BlockState> blockStates,
        List<BlockPos> targets
    ) {
        private static GeneratedBuild ok(
            String message,
            BuildIntent intent,
            BuildIntentContext context,
            TownBlueprint blueprint,
            List<BlockState> blockStates,
            List<BlockPos> targets
        ) {
            return new GeneratedBuild(true, message, intent, context, blueprint, List.copyOf(blockStates), List.copyOf(targets));
        }

        private static GeneratedBuild error(String message) {
            return new GeneratedBuild(false, message, BuildIntent.NONE, null, null, List.of(), List.of());
        }
    }
}
