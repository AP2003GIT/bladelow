package com.bladelow.builder;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
            Identifier id = Identifier.tryParse(blockText);
            if (id == null || !Registries.BLOCK.containsId(id)) {
                return SaveResult.error("invalid block id in placements: " + blockText);
            }
            BlockPos p = placement.pos();
            out.placements.add(new PlacementJson(
                p.getX() - minX,
                p.getY() - minY,
                p.getZ() - minZ,
                id.toString()
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
        List<Block> blocks = new ArrayList<>(template.placements().size());
        List<BlockPos> targets = new ArrayList<>(template.placements().size());
        for (PlacementEntry p : template.placements()) {
            Identifier id = Identifier.tryParse(p.block());
            if (id == null || !Registries.BLOCK.containsId(id)) {
                return BuildPlan.error("invalid block in blueprint: " + p.block());
            }
            targets.add(start.add(p.x(), p.y(), p.z()));
            blocks.add(Registries.BLOCK.get(id));
        }
        return BuildPlan.ok(template.name(), blocks, targets);
    }

    private static BlueprintInfo toInfo(BlueprintTemplate template) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (PlacementEntry p : template.placements()) {
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
            maxZ = Math.max(maxZ, p.z());
        }
        return new BlueprintInfo(template.name(), template.placements().size(), minX, minY, minZ, maxX, maxY, maxZ);
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
                    entries.add(new PlacementEntry(placement.x, placement.y, placement.z, placement.block.trim()));
                }
                if (entries.isEmpty()) {
                    continue;
                }
                String key = normalize(parsed.name);
                TEMPLATES.put(key, new BlueprintTemplate(parsed.name.trim(), entries));
            } catch (JsonParseException ex) {
                // Ignore invalid file and continue loading others.
            }
        }
    }

    private static Path blueprintDir(MinecraftServer server) {
        return server.getRunDirectory().resolve("config").resolve("bladelow").resolve("blueprints");
    }

    private static void ensureExampleFiles(Path dir) throws IOException {
        ensureExampleIfMissing(dir.resolve("line20.json"), exampleLine20());
        ensureExampleIfMissing(dir.resolve("wall5x5.json"), exampleWall5x5());
    }

    private static void ensureExampleIfMissing(Path file, BlueprintJson example) throws IOException {
        if (Files.exists(file)) {
            return;
        }
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(example, writer);
        }
    }

    private static BlueprintJson exampleLine20() {
        BlueprintJson json = new BlueprintJson();
        json.name = "line20";
        json.placements = new ArrayList<>();
        for (int x = 0; x < 20; x++) {
            json.placements.add(new PlacementJson(x, 0, 0, "minecraft:stone"));
        }
        return json;
    }

    private static BlueprintJson exampleWall5x5() {
        BlueprintJson json = new BlueprintJson();
        json.name = "wall5x5";
        json.placements = new ArrayList<>();
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 5; x++) {
                json.placements.add(new PlacementJson(x, y, 0, "minecraft:stone"));
            }
        }
        return json;
    }

    private static String normalize(String input) {
        return input.trim().toLowerCase(Locale.ROOT);
    }

    public record BuildPlan(boolean ok, String message, List<Block> blocks, List<BlockPos> targets) {
        public static BuildPlan ok(String name, List<Block> blocks, List<BlockPos> targets) {
            return new BuildPlan(true, name, blocks, targets);
        }

        public static BuildPlan error(String message) {
            return new BuildPlan(false, message, List.of(), List.of());
        }
    }

    public record BlueprintInfo(String name, int count, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public String summary() {
            int width = maxX - minX + 1;
            int height = maxY - minY + 1;
            int depth = maxZ - minZ + 1;
            return "name=" + name + " blocks=" + count + " size=" + width + "x" + height + "x" + depth
                + " min=(" + minX + "," + minY + "," + minZ + ") max=(" + maxX + "," + maxY + "," + maxZ + ")";
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

    private record BlueprintTemplate(String name, List<PlacementEntry> placements) {
    }

    private record PlacementEntry(int x, int y, int z, String block) {
    }

    private static class BlueprintJson {
        String name;
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
