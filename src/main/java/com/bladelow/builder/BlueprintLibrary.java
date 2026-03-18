package com.bladelow.builder;

import com.bladelow.ml.BlueprintAutoClassifier;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class BlueprintLibrary {
    private static final Gson GSON = new Gson();
    private static final Map<String, BlueprintTemplate> TEMPLATES = new ConcurrentHashMap<>();
    private static final Map<UUID, String> SELECTED_BY_PLAYER = new ConcurrentHashMap<>();

    private BlueprintLibrary() {
    }

    public static synchronized String reload(MinecraftServer server) {
        TEMPLATES.clear();
        Path dir = blueprintDir(server);
        try {
            Files.createDirectories(dir);
            ensureExampleFiles(dir);
            loadDirectory(dir);
            return "loaded blueprints=" + TEMPLATES.size();
        } catch (IOException ex) {
            return "load failed: " + ex.getMessage();
        }
    }

    public static synchronized List<String> listNames() {
        return TEMPLATES.keySet().stream().sorted().toList();
    }

    public static synchronized List<BlueprintInfo> listTownInfos() {
        return townBlueprints().stream()
            .map(BlueprintLibrary::toInfo)
            .sorted((a, b) -> {
                int byPriority = Integer.compare(b.priority(), a.priority());
                if (byPriority != 0) {
                    return byPriority;
                }
                int byArea = Integer.compare((b.plotWidth() * b.plotDepth()), (a.plotWidth() * a.plotDepth()));
                if (byArea != 0) {
                    return byArea;
                }
                return a.name().compareToIgnoreCase(b.name());
            })
            .toList();
    }

    public static synchronized boolean select(UUID playerId, String name) {
        String key = normalize(name);
        if (!TEMPLATES.containsKey(key)) {
            return false;
        }
        SELECTED_BY_PLAYER.put(playerId, key);
        return true;
    }

    public static synchronized String selected(UUID playerId) {
        return SELECTED_BY_PLAYER.get(playerId);
    }

    public static synchronized BuildPlan resolveSelected(UUID playerId, BlockPos start) {
        String key = SELECTED_BY_PLAYER.get(playerId);
        if (key == null) {
            return BuildPlan.error("no selected blueprint; use /bladeblueprint load <name>");
        }
        BlueprintTemplate template = TEMPLATES.get(key);
        if (template == null) {
            return BuildPlan.error("selected blueprint not loaded; run /bladeblueprint reload");
        }
        return resolveTemplate(template, start);
    }

    public static synchronized BuildPlan resolveByName(String name, BlockPos start) {
        BlueprintTemplate template = TEMPLATES.get(normalize(name));
        if (template == null) {
            return BuildPlan.error("unknown blueprint: " + name);
        }
        return resolveTemplate(template, start);
    }

    public static synchronized BuildPlan resolveTownFill(ServerWorld world, UUID playerId, BlockPos from, BlockPos to) {
        return resolveTownFill(world, playerId, from, to, "", true, true);
    }

    public static synchronized BuildPlan resolveTownFill(
        ServerWorld world,
        UUID playerId,
        BlockPos from,
        BlockPos to,
        String requiredZoneType,
        boolean lockLots
    ) {
        return resolveTownFill(world, playerId, from, to, requiredZoneType, lockLots, true);
    }

    public static synchronized BuildPlan resolveTownFill(
        ServerWorld world,
        UUID playerId,
        BlockPos from,
        BlockPos to,
        String requiredZoneType,
        boolean lockLots,
        boolean includeRoads
    ) {
        List<TownZoneStore.Zone> zones = playerId == null ? List.of() : TownZoneStore.snapshot(playerId, world.getRegistryKey());
        TownPlan plan = TownPlanner.plan(world, playerId, from, to, townBlueprints(), zones, requiredZoneType, lockLots, includeRoads);
        if (!plan.ok()) {
            return BuildPlan.error(plan.message());
        }
        return BuildPlan.ok(plan.message(), plan.blockStates(), plan.targets());
    }

    public static synchronized int clearTownLotLocks(ServerWorld world, UUID playerId) {
        if (world == null || playerId == null) {
            return 0;
        }
        return TownPlanner.clearLockedLots(playerId, world.getRegistryKey().getValue().toString());
    }

    public static synchronized BlueprintInfo info(String name) {
        BlueprintTemplate template = TEMPLATES.get(normalize(name));
        if (template == null) {
            return null;
        }
        return toInfo(template);
    }

    public static synchronized BlueprintInfo infoSelected(UUID playerId) {
        String key = SELECTED_BY_PLAYER.get(playerId);
        if (key == null) {
            return null;
        }
        BlueprintTemplate template = TEMPLATES.get(key);
        if (template == null) {
            return null;
        }
        return toInfo(template);
    }

    public static synchronized SaveResult saveSelectionAsBlueprint(
        MinecraftServer server,
        String name,
        List<BlockPos> points,
        String blockId
    ) {
        if (points == null || points.isEmpty()) {
            return SaveResult.error("selection is empty");
        }
        Identifier id = Identifier.tryParse(blockId);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            return SaveResult.error("invalid block id: " + blockId);
        }
        List<BlueprintPlacement> placements = new ArrayList<>(points.size());
        for (BlockPos p : points) {
            placements.add(new BlueprintPlacement(p, id.toString()));
        }
        return savePlacementsAsBlueprint(server, name, placements);
    }

    public static synchronized SaveResult savePlacementsAsBlueprint(
        MinecraftServer server,
        String name,
        List<BlueprintPlacement> placements
    ) {
        if (placements == null || placements.isEmpty()) {
            return SaveResult.error("no placements to save");
        }
        String normalizedName = normalize(name);
        if (normalizedName.isBlank()) {
            return SaveResult.error("invalid blueprint name");
        }

        List<BlueprintPlacement> sorted = placements.stream()
            .filter(entry -> entry != null && entry.pos() != null && entry.blockId() != null && !entry.blockId().isBlank())
            .sorted(
                Comparator.comparingInt((BlueprintPlacement p) -> p.pos().getY())
                    .thenComparingInt(p -> p.pos().getX())
                    .thenComparingInt(p -> p.pos().getZ())
            )
            .toList();
        if (sorted.isEmpty()) {
            return SaveResult.error("no valid placements to save");
        }

        int minX = sorted.stream().mapToInt(p -> p.pos().getX()).min().orElse(0);
        int minY = sorted.stream().mapToInt(p -> p.pos().getY()).min().orElse(0);
        int minZ = sorted.stream().mapToInt(p -> p.pos().getZ()).min().orElse(0);

        BlueprintJson out = new BlueprintJson();
        out.name = normalizedName;
        out.placements = new ArrayList<>(sorted.size());
        for (BlueprintPlacement placement : sorted) {
            String blockText = placement.blockId().trim();
            BlockState parsedState = BlueprintStateCodec.tryParse(blockText);
            if (parsedState == null) {
                return SaveResult.error("invalid block spec in placements: " + blockText);
            }
            BlockPos p = placement.pos();
            out.placements.add(new PlacementJson(
                p.getX() - minX,
                p.getY() - minY,
                p.getZ() - minZ,
                BlueprintStateCodec.stringify(parsedState)
            ));
        }

        Path dir = blueprintDir(server);
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(normalizedName + ".json");
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(out, writer);
            }
            String reloadStatus = reload(server);
            return SaveResult.ok("saved blueprint '" + normalizedName + "' blocks=" + sorted.size() + "; " + reloadStatus);
        } catch (IOException ex) {
            return SaveResult.error("save failed: " + ex.getMessage());
        }
    }

    private static BuildPlan resolveTemplate(BlueprintTemplate template, BlockPos start) {
        List<BlockState> states = new ArrayList<>(template.placements().size());
        List<BlockPos> targets = new ArrayList<>(template.placements().size());
        for (PlacementEntry placement : template.placements()) {
            BlockState state = BlueprintStateCodec.tryParse(placement.block());
            if (state == null) {
                return BuildPlan.error("invalid block in blueprint: " + placement.block());
            }
            targets.add(start.add(placement.x(), placement.y(), placement.z()));
            states.add(state);
        }
        return BuildPlan.ok(template.name(), states, targets);
    }

    private static List<TownBlueprint> townBlueprints() {
        return TEMPLATES.values().stream()
            .filter(BlueprintLibrary::isTownTemplate)
            .map(BlueprintLibrary::toTownBlueprint)
            .toList();
    }

    private static TownBlueprint toTownBlueprint(BlueprintTemplate template) {
        List<TownBlueprint.Placement> placements = template.placements().stream()
            .map(placement -> new TownBlueprint.Placement(placement.x(), placement.y(), placement.z(), placement.block()))
            .toList();
        return new TownBlueprint(
            template.name(),
            template.category(),
            placements,
            template.plotWidth(),
            template.plotDepth(),
            template.priority(),
            template.entranceX(),
            template.entranceZ(),
            template.roadSide(),
            0,
            template.themeTags(),
            template.tags()
        );
    }

    private static BlueprintInfo toInfo(BlueprintTemplate template) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (PlacementEntry placement : template.placements()) {
            minX = Math.min(minX, placement.x());
            minY = Math.min(minY, placement.y());
            minZ = Math.min(minZ, placement.z());
            maxX = Math.max(maxX, placement.x());
            maxY = Math.max(maxY, placement.y());
            maxZ = Math.max(maxZ, placement.z());
        }
        return new BlueprintInfo(
            template.name(),
            template.placements().size(),
            minX,
            minY,
            minZ,
            maxX,
            maxY,
            maxZ,
            template.category(),
            template.plotWidth(),
            template.plotDepth(),
            template.priority(),
            template.entranceX(),
            template.entranceZ(),
            template.roadSide(),
            template.themeTags(),
            List.copyOf(template.tags())
        );
    }

    private static BlueprintInfo toInfo(TownBlueprint blueprint) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (TownBlueprint.Placement placement : blueprint.placements()) {
            minX = Math.min(minX, placement.x());
            minY = Math.min(minY, placement.y());
            minZ = Math.min(minZ, placement.z());
            maxX = Math.max(maxX, placement.x());
            maxY = Math.max(maxY, placement.y());
            maxZ = Math.max(maxZ, placement.z());
        }
        return new BlueprintInfo(
            blueprint.name(),
            blueprint.placements().size(),
            minX,
            minY,
            minZ,
            maxX,
            maxY,
            maxZ,
            blueprint.category(),
            blueprint.plotWidth(),
            blueprint.plotDepth(),
            blueprint.priority(),
            blueprint.entranceOffsetX(),
            blueprint.entranceOffsetZ(),
            blueprint.roadSide(),
            blueprint.themeTags(),
            blueprint.tags()
        );
    }

    private static void loadDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        }

        for (Path file : files) {
            try (Reader reader = Files.newBufferedReader(file)) {
                BlueprintJson parsed = GSON.fromJson(reader, BlueprintJson.class);
                if (parsed == null || parsed.name == null || parsed.name.isBlank() || parsed.placements == null || parsed.placements.isEmpty()) {
                    continue;
                }
                List<PlacementEntry> entries = new ArrayList<>();
                for (PlacementJson placement : parsed.placements) {
                    if (placement == null || placement.block == null || placement.block.isBlank()) {
                        continue;
                    }
                    BlockState state = BlueprintStateCodec.tryParse(placement.block.trim());
                    if (state == null) {
                        continue;
                    }
                    entries.add(new PlacementEntry(
                        placement.x,
                        placement.y,
                        placement.z,
                        BlueprintStateCodec.stringify(state)
                    ));
                }
                if (entries.isEmpty()) {
                    continue;
                }
                String key = normalize(parsed.name);
                int plotWidth = parsed.plotWidth > 0 ? parsed.plotWidth : inferredWidth(entries);
                int plotDepth = parsed.plotDepth > 0 ? parsed.plotDepth : inferredDepth(entries);
                int buildHeight = inferredHeight(entries);
                int priority = parsed.priority;
                String category = normalizeCategory(parsed.category);
                List<String> tags = normalizeTags(parsed.tags);
                List<String> themeTags = normalizeTags(parsed.themeTags);
                BlueprintAutoClassifier.Classification inferred = BlueprintAutoClassifier.classify(
                    entries.stream().map(PlacementEntry::block).toList(),
                    plotWidth,
                    plotDepth,
                    buildHeight
                );
                themeTags = mergeTags(themeTags, inferred.themeTags());
                tags = mergeTags(tags, inferred.tags());
                String roadSide = normalizeSide(parsed.roadSide);
                if (roadSide.isBlank() && "town".equals(category)) {
                    roadSide = "north";
                }
                int entranceX = parsed.entranceX;
                int entranceZ = parsed.entranceZ;
                if ("town".equals(category) && parsed.entranceX == 0 && parsed.entranceZ == 0) {
                    entranceX = inferredEntranceX(plotWidth, roadSide);
                    entranceZ = inferredEntranceZ(plotDepth, roadSide);
                }
                TEMPLATES.put(
                    key,
                    new BlueprintTemplate(
                        parsed.name.trim(),
                        entries,
                        category,
                        plotWidth,
                        plotDepth,
                        priority,
                        entranceX,
                        entranceZ,
                        roadSide,
                        themeTags,
                        tags
                    )
                );
            } catch (JsonParseException ex) {
                // Ignore invalid file and continue loading others.
            }
        }
    }

    private static int inferredWidth(List<PlacementEntry> entries) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        for (PlacementEntry entry : entries) {
            minX = Math.min(minX, entry.x());
            maxX = Math.max(maxX, entry.x());
        }
        return Math.max(1, maxX - minX + 1);
    }

    private static int inferredDepth(List<PlacementEntry> entries) {
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (PlacementEntry entry : entries) {
            minZ = Math.min(minZ, entry.z());
            maxZ = Math.max(maxZ, entry.z());
        }
        return Math.max(1, maxZ - minZ + 1);
    }

    private static int inferredHeight(List<PlacementEntry> entries) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (PlacementEntry entry : entries) {
            minY = Math.min(minY, entry.y());
            maxY = Math.max(maxY, entry.y());
        }
        return Math.max(1, maxY - minY + 1);
    }

    private static int inferredEntranceX(int plotWidth, String roadSide) {
        return switch (roadSide) {
            case "west" -> 0;
            case "east" -> Math.max(0, plotWidth - 1);
            default -> Math.max(0, plotWidth / 2);
        };
    }

    private static int inferredEntranceZ(int plotDepth, String roadSide) {
        return switch (roadSide) {
            case "north" -> 0;
            case "south" -> Math.max(0, plotDepth - 1);
            default -> Math.max(0, plotDepth / 2);
        };
    }

    private static String normalizeCategory(String input) {
        if (input == null) {
            return "";
        }
        return input.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeSide(String input) {
        String normalized = normalizeCategory(input);
        return switch (normalized) {
            case "north", "south", "east", "west" -> normalized;
            default -> "";
        };
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag == null) {
                continue;
            }
            String normalized = tag.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isBlank()) {
                out.add(normalized);
            }
        }
        return List.copyOf(out);
    }

    private static List<String> mergeTags(List<String> explicitTags, List<String> inferredTags) {
        if ((explicitTags == null || explicitTags.isEmpty()) && (inferredTags == null || inferredTags.isEmpty())) {
            return List.of();
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (explicitTags != null) {
            merged.addAll(explicitTags);
        }
        if (inferredTags != null) {
            merged.addAll(inferredTags);
        }
        return List.copyOf(merged);
    }

    private static boolean isTownTemplate(BlueprintTemplate template) {
        return template != null && "town".equals(template.category());
    }

    private static Path blueprintDir(MinecraftServer server) {
        return server.getRunDirectory().resolve("config").resolve("bladelow").resolve("blueprints");
    }

    private static void ensureExampleFiles(Path dir) throws IOException {
        ensureExampleIfMissing(dir.resolve("town_house_small.json"), exampleTownHouseSmall());
        ensureExampleIfMissing(dir.resolve("town_house_tall.json"), exampleTownHouseTall());
        ensureExampleIfMissing(dir.resolve("town_house_corner.json"), exampleTownHouseCorner());
        ensureExampleIfMissing(dir.resolve("town_smithy.json"), exampleTownSmithy());
        ensureExampleIfMissing(dir.resolve("town_warehouse.json"), exampleTownWarehouse());
        ensureExampleIfMissing(dir.resolve("town_market_stall.json"), exampleTownMarketStall());
        ensureExampleIfMissing(dir.resolve("town_civic_hall.json"), exampleTownCivicHall());
        ensureExampleIfMissing(dir.resolve("town_plaza_fountain.json"), exampleTownPlazaFountain());
    }

    private static void ensureExampleIfMissing(Path file, BlueprintJson example) throws IOException {
        if (Files.exists(file)) {
            return;
        }
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(example, writer);
        }
    }

    private static BlueprintJson exampleTownHouseSmall() {
        BlueprintJson json = townJson(
            "town_house_small",
            7,
            9,
            8,
            "north",
            3,
            0,
            List.of("medieval", "oak", "village"),
            "house",
            "residential",
            "small"
        );
        for (int y = 1; y <= 3; y++) {
            for (int x = 1; x <= 5; x++) {
                addPlacement(json, x, y, 1, x == 3 && y <= 2 ? null : "minecraft:oak_planks");
                addPlacement(json, x, y, 7, "minecraft:oak_planks");
            }
            for (int z = 2; z <= 6; z++) {
                addPlacement(json, 1, y, z, "minecraft:oak_planks");
                addPlacement(json, 5, y, z, "minecraft:oak_planks");
            }
        }
        for (int y = 1; y <= 3; y++) {
            addPlacement(json, 1, y, 1, "minecraft:spruce_log");
            addPlacement(json, 5, y, 1, "minecraft:spruce_log");
            addPlacement(json, 1, y, 7, "minecraft:spruce_log");
            addPlacement(json, 5, y, 7, "minecraft:spruce_log");
        }
        addPlacement(json, 1, 2, 4, "minecraft:glass");
        addPlacement(json, 5, 2, 4, "minecraft:glass");
        addPlacement(json, 3, 2, 7, "minecraft:glass");
        fillRect(json, 1, 4, 1, 5, 4, 7, "minecraft:oak_planks");
        fillRect(json, 2, 5, 2, 4, 5, 6, "minecraft:oak_slab");
        return json;
    }

    private static BlueprintJson exampleTownHouseTall() {
        BlueprintJson json = townJson(
            "town_house_tall",
            9,
            11,
            9,
            "north",
            4,
            0,
            List.of("medieval", "stone", "residential"),
            "house",
            "residential",
            "tall"
        );
        for (int y = 1; y <= 5; y++) {
            for (int x = 1; x <= 7; x++) {
                addPlacement(json, x, y, 1, x == 4 && y <= 2 ? null : "minecraft:stone_bricks");
                addPlacement(json, x, y, 9, "minecraft:stone_bricks");
            }
            for (int z = 2; z <= 8; z++) {
                addPlacement(json, 1, y, z, "minecraft:stone_bricks");
                addPlacement(json, 7, y, z, "minecraft:stone_bricks");
            }
        }
        for (int y = 1; y <= 5; y++) {
            addPlacement(json, 1, y, 1, "minecraft:spruce_log");
            addPlacement(json, 7, y, 1, "minecraft:spruce_log");
            addPlacement(json, 1, y, 9, "minecraft:spruce_log");
            addPlacement(json, 7, y, 9, "minecraft:spruce_log");
        }
        addPlacement(json, 1, 2, 4, "minecraft:glass");
        addPlacement(json, 7, 2, 4, "minecraft:glass");
        addPlacement(json, 1, 4, 6, "minecraft:glass");
        addPlacement(json, 7, 4, 6, "minecraft:glass");
        addPlacement(json, 4, 3, 9, "minecraft:glass");
        fillRect(json, 1, 6, 1, 7, 6, 9, "minecraft:oak_planks");
        fillRect(json, 2, 7, 2, 6, 7, 8, "minecraft:oak_planks");
        return json;
    }

    private static BlueprintJson exampleTownSmithy() {
        BlueprintJson json = townJson(
            "town_smithy",
            11,
            13,
            10,
            "north",
            5,
            0,
            List.of("medieval", "stone", "utility"),
            "smithy",
            "workshop",
            "utility"
        );
        for (int y = 1; y <= 4; y++) {
            for (int x = 1; x <= 9; x++) {
                addPlacement(json, x, y, 1, x == 5 && y <= 2 ? null : "minecraft:cobblestone");
                addPlacement(json, x, y, 11, "minecraft:cobblestone");
            }
            for (int z = 2; z <= 10; z++) {
                addPlacement(json, 1, y, z, "minecraft:cobblestone");
                addPlacement(json, 9, y, z, "minecraft:cobblestone");
            }
        }
        for (int y = 1; y <= 4; y++) {
            addPlacement(json, 1, y, 1, "minecraft:stone_bricks");
            addPlacement(json, 9, y, 1, "minecraft:stone_bricks");
            addPlacement(json, 1, y, 11, "minecraft:stone_bricks");
            addPlacement(json, 9, y, 11, "minecraft:stone_bricks");
        }
        addPlacement(json, 2, 2, 5, "minecraft:glass");
        addPlacement(json, 8, 2, 5, "minecraft:glass");
        addPlacement(json, 2, 2, 8, "minecraft:glass");
        addPlacement(json, 8, 2, 8, "minecraft:glass");
        fillRect(json, 1, 5, 1, 9, 5, 11, "minecraft:deepslate_tiles");
        fillRect(json, 2, 6, 2, 8, 6, 10, "minecraft:deepslate_tiles");
        addPlacement(json, 3, 1, 3, "minecraft:furnace");
        addPlacement(json, 4, 1, 3, "minecraft:blast_furnace");
        addPlacement(json, 6, 1, 3, "minecraft:anvil");
        addPlacement(json, 7, 1, 3, "minecraft:stonecutter");
        return json;
    }

    private static BlueprintJson exampleTownMarketStall() {
        BlueprintJson json = townJson(
            "town_market_stall",
            5,
            7,
            7,
            "north",
            2,
            0,
            List.of("medieval", "market", "cloth"),
            "market",
            "stall",
            "decor"
        );
        for (int y = 1; y <= 2; y++) {
            addPlacement(json, 1, y, 1, "minecraft:oak_fence");
            addPlacement(json, 3, y, 1, "minecraft:oak_fence");
            addPlacement(json, 1, y, 5, "minecraft:oak_fence");
            addPlacement(json, 3, y, 5, "minecraft:oak_fence");
        }
        fillRect(json, 0, 3, 1, 4, 3, 5, "minecraft:red_wool");
        addPlacement(json, 1, 1, 3, "minecraft:oak_planks");
        addPlacement(json, 2, 1, 3, "minecraft:chest");
        addPlacement(json, 3, 1, 3, "minecraft:oak_planks");
        addPlacement(json, 2, 1, 2, "minecraft:barrel");
        return json;
    }

    private static BlueprintJson exampleTownHouseCorner() {
        BlueprintJson json = townJson(
            "town_house_corner",
            8,
            8,
            8,
            "north",
            3,
            0,
            List.of("medieval", "oak", "residential"),
            "house",
            "residential",
            "detail"
        );
        for (int y = 1; y <= 3; y++) {
            for (int x = 1; x <= 6; x++) {
                addPlacement(json, x, y, 1, x == 3 && y <= 2 ? null : "minecraft:spruce_planks");
                addPlacement(json, x, y, 6, "minecraft:spruce_planks");
            }
            for (int z = 2; z <= 5; z++) {
                addPlacement(json, 1, y, z, "minecraft:spruce_planks");
                addPlacement(json, 6, y, z, "minecraft:spruce_planks");
            }
        }
        for (int y = 1; y <= 3; y++) {
            addPlacement(json, 1, y, 1, "minecraft:dark_oak_log");
            addPlacement(json, 6, y, 1, "minecraft:dark_oak_log");
            addPlacement(json, 1, y, 6, "minecraft:dark_oak_log");
            addPlacement(json, 6, y, 6, "minecraft:dark_oak_log");
        }
        addPlacement(json, 2, 2, 6, "minecraft:glass_pane");
        addPlacement(json, 5, 2, 6, "minecraft:glass_pane");
        addPlacement(json, 1, 2, 3, "minecraft:glass_pane");
        addPlacement(json, 6, 2, 4, "minecraft:glass_pane");
        fillRect(json, 1, 4, 1, 6, 4, 6, "minecraft:oak_planks");
        fillRect(json, 2, 5, 2, 5, 5, 5, "minecraft:oak_slab");
        addPlacement(json, 4, 1, 4, "minecraft:crafting_table");
        addPlacement(json, 5, 1, 4, "minecraft:barrel");
        return json;
    }

    private static BlueprintJson exampleTownWarehouse() {
        BlueprintJson json = townJson(
            "town_warehouse",
            12,
            9,
            9,
            "north",
            5,
            0,
            List.of("medieval", "storage", "utility"),
            "workshop",
            "utility",
            "storage"
        );
        for (int y = 1; y <= 4; y++) {
            for (int x = 1; x <= 10; x++) {
                addPlacement(json, x, y, 1, x == 5 && y <= 2 ? null : "minecraft:stone_bricks");
                addPlacement(json, x, y, 7, "minecraft:stone_bricks");
            }
            for (int z = 2; z <= 6; z++) {
                addPlacement(json, 1, y, z, "minecraft:stone_bricks");
                addPlacement(json, 10, y, z, "minecraft:stone_bricks");
            }
        }
        for (int y = 1; y <= 4; y++) {
            addPlacement(json, 1, y, 1, "minecraft:deepslate_bricks");
            addPlacement(json, 10, y, 1, "minecraft:deepslate_bricks");
            addPlacement(json, 1, y, 7, "minecraft:deepslate_bricks");
            addPlacement(json, 10, y, 7, "minecraft:deepslate_bricks");
        }
        addPlacement(json, 3, 2, 1, "minecraft:glass");
        addPlacement(json, 8, 2, 1, "minecraft:glass");
        addPlacement(json, 3, 2, 7, "minecraft:glass");
        addPlacement(json, 8, 2, 7, "minecraft:glass");
        fillRect(json, 1, 5, 1, 10, 5, 7, "minecraft:spruce_planks");
        fillRect(json, 2, 6, 2, 9, 6, 6, "minecraft:spruce_slab");
        addPlacement(json, 3, 1, 3, "minecraft:barrel");
        addPlacement(json, 4, 1, 3, "minecraft:chest");
        addPlacement(json, 7, 1, 3, "minecraft:barrel");
        addPlacement(json, 8, 1, 3, "minecraft:chest");
        addPlacement(json, 5, 1, 5, "minecraft:anvil");
        return json;
    }

    private static BlueprintJson exampleTownCivicHall() {
        BlueprintJson json = townJson(
            "town_civic_hall",
            13,
            11,
            11,
            "north",
            6,
            0,
            List.of("medieval", "civic", "stone"),
            "civic",
            "hall",
            "plaza"
        );
        for (int y = 1; y <= 5; y++) {
            for (int x = 1; x <= 11; x++) {
                addPlacement(json, x, y, 1, x == 6 && y <= 2 ? null : "minecraft:stone_bricks");
                addPlacement(json, x, y, 9, "minecraft:stone_bricks");
            }
            for (int z = 2; z <= 8; z++) {
                addPlacement(json, 1, y, z, "minecraft:stone_bricks");
                addPlacement(json, 11, y, z, "minecraft:stone_bricks");
            }
        }
        for (int y = 1; y <= 5; y++) {
            addPlacement(json, 1, y, 1, "minecraft:polished_andesite");
            addPlacement(json, 11, y, 1, "minecraft:polished_andesite");
            addPlacement(json, 1, y, 9, "minecraft:polished_andesite");
            addPlacement(json, 11, y, 9, "minecraft:polished_andesite");
        }
        addPlacement(json, 3, 2, 1, "minecraft:glass_pane");
        addPlacement(json, 9, 2, 1, "minecraft:glass_pane");
        addPlacement(json, 3, 2, 9, "minecraft:glass_pane");
        addPlacement(json, 9, 2, 9, "minecraft:glass_pane");
        addPlacement(json, 2, 3, 5, "minecraft:glass_pane");
        addPlacement(json, 10, 3, 5, "minecraft:glass_pane");
        fillRect(json, 1, 6, 1, 11, 6, 9, "minecraft:deepslate_tiles");
        fillRect(json, 2, 7, 2, 10, 7, 8, "minecraft:deepslate_tile_slab");
        addPlacement(json, 6, 1, 5, "minecraft:lectern");
        addPlacement(json, 5, 1, 5, "minecraft:oak_stairs");
        addPlacement(json, 7, 1, 5, "minecraft:oak_stairs");
        return json;
    }

    private static BlueprintJson exampleTownPlazaFountain() {
        BlueprintJson json = townJson(
            "town_plaza_fountain",
            7,
            7,
            6,
            "north",
            3,
            0,
            List.of("medieval", "plaza", "decor"),
            "civic",
            "plaza",
            "decor"
        );
        fillRect(json, 0, 1, 0, 6, 1, 6, "minecraft:stone_bricks");
        fillRect(json, 1, 1, 1, 5, 1, 5, "minecraft:polished_andesite");
        addPlacement(json, 3, 1, 3, "minecraft:water");
        addPlacement(json, 3, 2, 3, "minecraft:water");
        addPlacement(json, 3, 3, 3, "minecraft:stone_brick_wall");
        addPlacement(json, 2, 2, 3, "minecraft:stone_brick_stairs");
        addPlacement(json, 4, 2, 3, "minecraft:stone_brick_stairs");
        addPlacement(json, 3, 2, 2, "minecraft:stone_brick_stairs");
        addPlacement(json, 3, 2, 4, "minecraft:stone_brick_stairs");
        return json;
    }

    private static BlueprintJson townJson(
        String name,
        int plotWidth,
        int plotDepth,
        int priority,
        String roadSide,
        int entranceX,
        int entranceZ,
        List<String> themeTags,
        String... tags
    ) {
        BlueprintJson json = new BlueprintJson();
        json.name = name;
        json.category = "town";
        json.plotWidth = plotWidth;
        json.plotDepth = plotDepth;
        json.priority = priority;
        json.roadSide = roadSide;
        json.entranceX = entranceX;
        json.entranceZ = entranceZ;
        json.themeTags = themeTags == null ? List.of() : List.copyOf(themeTags);
        json.tags = List.of(tags);
        json.placements = new ArrayList<>();
        return json;
    }

    private static void fillRect(BlueprintJson json, int minX, int y, int minZ, int maxX, int maxY, int maxZ, String block) {
        for (int yy = y; yy <= maxY; yy++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    addPlacement(json, x, yy, z, block);
                }
            }
        }
    }

    private static void addPlacement(BlueprintJson json, int x, int y, int z, String block) {
        if (json == null || block == null || block.isBlank()) {
            return;
        }
        json.placements.add(new PlacementJson(x, y, z, block));
    }

    private static String normalize(String input) {
        if (input == null) {
            return "";
        }
        return input.trim().toLowerCase(Locale.ROOT);
    }

    public record BuildPlan(boolean ok, String message, List<BlockState> blockStates, List<BlockPos> targets) {
        public static BuildPlan ok(String message, List<BlockState> blockStates, List<BlockPos> targets) {
            return new BuildPlan(true, message, List.copyOf(blockStates), List.copyOf(targets));
        }

        public static BuildPlan error(String message) {
            return new BuildPlan(false, message, List.of(), List.of());
        }

        public List<Block> blocks() {
            return blockStates.stream()
                .map(BlockState::getBlock)
                .toList();
        }
    }

    public record BlueprintInfo(
        String name,
        int count,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ,
        String category,
        int plotWidth,
        int plotDepth,
        int priority,
        int entranceX,
        int entranceZ,
        String roadSide,
        List<String> themeTags,
        List<String> tags
    ) {
        public int width() {
            return maxX - minX + 1;
        }

        public int height() {
            return maxY - minY + 1;
        }

        public int depth() {
            return maxZ - minZ + 1;
        }

        public String summary() {
            StringBuilder out = new StringBuilder();
            out.append("name=").append(name)
                .append(" blocks=").append(count)
                .append(" size=").append(width()).append("x").append(height()).append("x").append(depth())
                .append(" plot=").append(plotWidth).append("x").append(plotDepth);
            if (category != null && !category.isBlank()) {
                out.append(" category=").append(category);
            }
            if (priority != 0) {
                out.append(" priority=").append(priority);
            }
            if (roadSide != null && !roadSide.isBlank()) {
                out.append(" road=").append(roadSide)
                    .append(" entrance=(").append(entranceX).append(",").append(entranceZ).append(")");
            }
            if (!themeTags.isEmpty()) {
                out.append(" theme=").append(String.join("/", themeTags));
            }
            out.append(" min=(").append(minX).append(",").append(minY).append(",").append(minZ)
                .append(") max=(").append(maxX).append(",").append(maxY).append(",").append(maxZ).append(")");
            return out.toString();
        }
    }

    public record SaveResult(boolean ok, String message) {
        public static SaveResult ok(String message) {
            return new SaveResult(true, message);
        }

        public static SaveResult error(String message) {
            return new SaveResult(false, message);
        }
    }

    public record BlueprintPlacement(BlockPos pos, String blockId) {
    }

    private record BlueprintTemplate(
        String name,
        List<PlacementEntry> placements,
        String category,
        int plotWidth,
        int plotDepth,
        int priority,
        int entranceX,
        int entranceZ,
        String roadSide,
        List<String> themeTags,
        List<String> tags
    ) {
    }

    private record PlacementEntry(int x, int y, int z, String block) {
    }

    private static class BlueprintJson {
        String name;
        String category;
        int plotWidth;
        int plotDepth;
        int priority;
        int entranceX;
        int entranceZ;
        String roadSide;
        List<String> themeTags;
        List<String> tags;
        List<PlacementJson> placements;
    }

    private static class PlacementJson {
        int x;
        int y;
        int z;
        String block;

        PlacementJson(int x, int y, int z, String block) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
        }
    }
}
