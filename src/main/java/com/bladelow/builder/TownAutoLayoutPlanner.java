package com.bladelow.builder;

import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Shared auto-layout generator used by commands, HUD actions, and director mode.
 * Generates a 3x3 district zoning grid for a marked build area.
 */
public final class TownAutoLayoutPlanner {
    private static final List<String> ROAD_HINTS = List.of(
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

    private TownAutoLayoutPlanner() {
    }

    public static ApplyResult apply(
        UUID playerId,
        RegistryKey<World> worldKey,
        BlockPos from,
        BlockPos to,
        String rawPreset,
        boolean clearExisting
    ) {
        return apply(playerId, worldKey, from, to, rawPreset, clearExisting, null);
    }

    public static ApplyResult apply(
        UUID playerId,
        RegistryKey<World> worldKey,
        BlockPos from,
        BlockPos to,
        String rawPreset,
        boolean clearExisting,
        ServerWorld world
    ) {
        if (playerId == null || worldKey == null || from == null || to == null) {
            return ApplyResult.error("invalid area");
        }

        String preset = normalizePreset(rawPreset);
        if (preset.isBlank()) {
            return ApplyResult.error("preset must be balanced|medieval|harbor|adaptive");
        }

        int minX = Math.min(from.getX(), to.getX());
        int maxX = Math.max(from.getX(), to.getX());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxZ = Math.max(from.getZ(), to.getZ());
        if (maxX - minX < 5 || maxZ - minZ < 5) {
            return ApplyResult.error("selection too small for autolayout (need at least 6x6)");
        }

        if (clearExisting) {
            TownZoneStore.clear(playerId, worldKey);
        }

        int baseY = Math.min(from.getY(), to.getY());
        List<ZoneSpec> generated = generatePresetZones(preset, minX, maxX, minZ, maxZ, baseY, world);
        int saved = 0;
        for (ZoneSpec zone : generated) {
            TownZoneStore.ZoneResult result = TownZoneStore.setBox(
                playerId,
                worldKey,
                zone.type(),
                new BlockPos(zone.minX(), 0, zone.minZ()),
                new BlockPos(zone.maxX(), 0, zone.maxZ())
            );
            if (result.ok()) {
                saved++;
            }
        }

        if (saved <= 0) {
            return ApplyResult.error("autolayout generated no valid zones");
        }

        List<TownZoneStore.Zone> snapshot = TownZoneStore.snapshot(playerId, worldKey);
        Map<String, Integer> counts = TownZoneStore.summarizeByType(snapshot);
        String summary = counts.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .reduce((a, b) -> a + ", " + b)
            .orElse("none");
        return ApplyResult.ok(
            "autolayout " + preset
                + " " + (clearExisting ? "applied" : "appended")
                + " zones=" + saved
                + " (" + summary + ")",
            preset,
            saved,
            counts
        );
    }

    public static String normalizePreset(String rawPreset) {
        String preset = rawPreset == null ? "" : rawPreset.trim().toLowerCase(Locale.ROOT);
        if (preset.equals("balanced") || preset.equals("medieval") || preset.equals("harbor") || preset.equals("adaptive")) {
            return preset;
        }
        return "";
    }

    private static List<ZoneSpec> generatePresetZones(
        String preset,
        int minX,
        int maxX,
        int minZ,
        int maxZ,
        int baseY,
        ServerWorld world
    ) {
        int[] xCuts = splitIntoThirds(minX, maxX);
        int[] zCuts = splitIntoThirds(minZ, maxZ);
        String[][] map = zoneMapForPreset(preset, minX, maxX, minZ, maxZ, baseY, world);
        List<ZoneSpec> zones = new ArrayList<>(9);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                String type = map[row][col];
                int zoneMinX = xCuts[col];
                int zoneMaxX = xCuts[col + 1];
                int zoneMinZ = zCuts[row];
                int zoneMaxZ = zCuts[row + 1];
                if (zoneMinX > zoneMaxX || zoneMinZ > zoneMaxZ) {
                    continue;
                }
                zones.add(new ZoneSpec(type, zoneMinX, zoneMaxX, zoneMinZ, zoneMaxZ));
            }
        }
        return zones;
    }

    private static int[] splitIntoThirds(int min, int max) {
        int size = (max - min) + 1;
        int first = min + (size / 3) - 1;
        int second = min + ((size * 2) / 3) - 1;
        first = clamp(first, min, max);
        second = clamp(second, first, max);
        return new int[]{min, first, second, max};
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String[][] zoneMapForPreset(
        String preset,
        int minX,
        int maxX,
        int minZ,
        int maxZ,
        int baseY,
        ServerWorld world
    ) {
        if ("adaptive".equals(preset)) {
            return adaptiveZoneMap(minX, maxX, minZ, maxZ, baseY, world);
        }
        if ("medieval".equals(preset)) {
            return new String[][]{
                {"workshop", "civic", "workshop"},
                {"residential", "civic", "market"},
                {"residential", "mixed", "market"}
            };
        }
        if ("harbor".equals(preset)) {
            return new String[][]{
                {"workshop", "workshop", "market"},
                {"mixed", "civic", "market"},
                {"residential", "residential", "mixed"}
            };
        }
        return new String[][]{
            {"residential", "market", "workshop"},
            {"residential", "civic", "workshop"},
            {"mixed", "market", "mixed"}
        };
    }

    private static String[][] adaptiveZoneMap(
        int minX,
        int maxX,
        int minZ,
        int maxZ,
        int baseY,
        ServerWorld world
    ) {
        if (world == null) {
            return zoneMapForPreset("balanced", minX, maxX, minZ, maxZ, baseY, null);
        }

        EdgeStats north = scanEdge(world, minX, maxX, minZ, baseY, true);
        EdgeStats south = scanEdge(world, minX, maxX, maxZ, baseY, true);
        EdgeStats west = scanEdge(world, minZ, maxZ, minX, baseY, false);
        EdgeStats east = scanEdge(world, minZ, maxZ, maxX, baseY, false);

        Side front = Side.NORTH;
        EdgeStats best = north;
        if (south.score() > best.score()) {
            front = Side.SOUTH;
            best = south;
        }
        if (west.score() > best.score()) {
            front = Side.WEST;
            best = west;
        }
        if (east.score() > best.score()) {
            front = Side.EAST;
            best = east;
        }

        boolean harborBias = best.waterFraction() >= 0.20;
        String[][] northFacing = harborBias
            ? new String[][]{
                {"workshop", "market", "workshop"},
                {"mixed", "civic", "market"},
                {"residential", "residential", "mixed"}
            }
            : new String[][]{
                {"mixed", "market", "mixed"},
                {"residential", "civic", "workshop"},
                {"residential", "mixed", "workshop"}
            };

        int turns = switch (front) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
        };
        return rotateMapClockwise(northFacing, turns);
    }

    private static EdgeStats scanEdge(
        ServerWorld world,
        int start,
        int end,
        int fixed,
        int baseY,
        boolean xSweep
    ) {
        int samples = Math.max(1, Math.abs(end - start) + 1);
        int roadHits = 0;
        int waterHits = 0;
        for (int i = 0; i < samples; i++) {
            int a = start + i;
            int x = xSweep ? a : fixed;
            int z = xSweep ? fixed : a;
            BlockState state = world.getBlockState(new BlockPos(x, baseY, z));
            if (isRoadLikeSurface(state)) {
                roadHits++;
            }
            if (!state.getFluidState().isEmpty()) {
                waterHits++;
            }
        }
        return new EdgeStats(samples, roadHits, waterHits);
    }

    private static boolean isRoadLikeSurface(BlockState state) {
        if (state == null || state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
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

    private static String[][] rotateMapClockwise(String[][] src, int turns) {
        String[][] out = src;
        int n = src.length;
        int normalized = Math.floorMod(turns, 4);
        for (int t = 0; t < normalized; t++) {
            String[][] next = new String[n][n];
            for (int r = 0; r < n; r++) {
                for (int c = 0; c < n; c++) {
                    next[c][n - 1 - r] = out[r][c];
                }
            }
            out = next;
        }
        return out;
    }

    public record ZoneSpec(String type, int minX, int maxX, int minZ, int maxZ) {
    }

    private enum Side {
        NORTH,
        SOUTH,
        EAST,
        WEST
    }

    private record EdgeStats(int samples, int roadHits, int waterHits) {
        private double roadFraction() {
            return samples <= 0 ? 0.0 : (double) roadHits / samples;
        }

        private double waterFraction() {
            return samples <= 0 ? 0.0 : (double) waterHits / samples;
        }

        private double score() {
            return roadFraction() + (waterFraction() * 0.85);
        }
    }

    public record ApplyResult(
        boolean ok,
        String message,
        String preset,
        int saved,
        Map<String, Integer> zoneCounts
    ) {
        static ApplyResult ok(String message, String preset, int saved, Map<String, Integer> counts) {
            return new ApplyResult(true, message, preset, saved, Map.copyOf(new LinkedHashMap<>(counts)));
        }

        static ApplyResult error(String message) {
            return new ApplyResult(false, message, "", 0, Map.of());
        }
    }
}
