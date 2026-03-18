package com.bladelow.builder;

import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a selected build area into planner-friendly features.
 *
 * The analyzer deliberately ignores raw block-by-block detail that would be too
 * noisy for town planning. Instead it extracts terrain spread, nearby structure
 * footprints, and rough material themes so later systems can score "fit" fast.
 */
public final class BuildSiteAnalyzer {
    private static final int SCAN_MARGIN = 24;
    private static final int STYLE_SCAN_HEIGHT = 20;
    private static final int MIN_STRUCTURE_COLUMNS = 8;
    private static final Set<String> TERRAIN_EXACT = Set.of(
        "grass_block",
        "dirt",
        "coarse_dirt",
        "rooted_dirt",
        "podzol",
        "mycelium",
        "mud",
        "muddy_mangrove_roots",
        "sand",
        "red_sand",
        "gravel",
        "clay",
        "moss_block",
        "snow_block",
        "ice",
        "packed_ice",
        "blue_ice",
        "netherrack",
        "end_stone"
    );

    private BuildSiteAnalyzer() {
    }

    public static BuildSiteScan scan(ServerWorld world, BlockPos from, BlockPos to, int baseY, Set<Long> ignoredColumns) {
        if (world == null || from == null || to == null) {
            return BuildSiteScan.EMPTY;
        }

        // Scan slightly outside the requested box so the planner can learn from
        // nearby context instead of only what is inside the selected plot.
        int selectionMinX = Math.min(from.getX(), to.getX());
        int selectionMaxX = Math.max(from.getX(), to.getX());
        int selectionMinZ = Math.min(from.getZ(), to.getZ());
        int selectionMaxZ = Math.max(from.getZ(), to.getZ());
        int minX = selectionMinX - SCAN_MARGIN;
        int maxX = selectionMaxX + SCAN_MARGIN;
        int minZ = selectionMinZ - SCAN_MARGIN;
        int maxZ = selectionMaxZ + SCAN_MARGIN;

        int terrainMin = Integer.MAX_VALUE;
        int terrainMax = Integer.MIN_VALUE;
        long terrainSum = 0L;
        int terrainSamples = 0;

        Map<Long, ColumnSample> candidateColumns = new LinkedHashMap<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (topY < world.getBottomY()) {
                    continue;
                }
                terrainMin = Math.min(terrainMin, topY);
                terrainMax = Math.max(terrainMax, topY);
                terrainSum += topY;
                terrainSamples++;

                long key = columnKey(x, z);
                if (ignoredColumns != null && ignoredColumns.contains(key)) {
                    // Roads/plazas are already planner primitives, so they are
                    // excluded from structure/style learning to avoid biasing
                    // the style profile toward circulation blocks.
                    continue;
                }
                ColumnSample sample = sampleColumn(world, x, z, baseY);
                if (sample.isCandidate()) {
                    candidateColumns.put(key, sample);
                }
            }
        }

        if (terrainSamples <= 0) {
            return BuildSiteScan.EMPTY;
        }

        List<BuildSiteScan.NearbyStructure> nearbyStructures = new ArrayList<>();
        List<StructureAggregate> acceptedStructures = new ArrayList<>();
        List<StructureAggregate> styleStructures = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        for (Map.Entry<Long, ColumnSample> entry : candidateColumns.entrySet()) {
            long key = entry.getKey();
            if (!visited.add(key)) {
                continue;
            }
            StructureAggregate aggregate = collectStructure(key, candidateColumns, visited);
            if (!aggregate.accepted()) {
                continue;
            }
            BuildSiteScan.NearbyStructure structure = aggregate.toStructure();
            nearbyStructures.add(structure);
            acceptedStructures.add(aggregate);
            if (qualifiesForStyle(structure)) {
                styleStructures.add(aggregate);
            }
        }

        // Prefer "real" candidate structures for style learning, but fall back
        // to everything accepted if the filtered set is too small.
        List<StructureAggregate> styleSource = styleStructures.isEmpty() ? acceptedStructures : styleStructures;

        SiteStyleProfile profile = buildStyleProfile(styleSource);
        int terrainAverage = (int) Math.round(terrainSum / (double) terrainSamples);
        return new BuildSiteScan(
            profile,
            List.copyOf(nearbyStructures),
            terrainMin,
            terrainMax,
            terrainAverage
        );
    }

    private static ColumnSample sampleColumn(ServerWorld world, int x, int z, int baseY) {
        int worldTopY = world.getBottomY() + world.getHeight() - 1;
        int minY = Math.max(world.getBottomY(), baseY + 1);
        int maxY = Math.min(worldTopY, baseY + STYLE_SCAN_HEIGHT);
        if (maxY < minY) {
            return ColumnSample.EMPTY;
        }

        // A column is treated as a possible man-made structure if it has enough
        // non-terrain material above the base plane.
        int strongCount = 0;
        int weakCount = 0;
        int detailCount = 0;
        int minSeenY = Integer.MAX_VALUE;
        int maxSeenY = Integer.MIN_VALUE;
        Map<String, Integer> themeCounts = new HashMap<>();
        Map<String, Integer> familyCounts = new HashMap<>();

        for (int y = minY; y <= maxY; y++) {
            BlockState state = world.getBlockState(new BlockPos(x, y, z));
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                continue;
            }
            Identifier id = Registries.BLOCK.getId(state.getBlock());
            if (id == null) {
                continue;
            }
            String path = id.getPath().toLowerCase(Locale.ROOT);
            if (isTerrainNoise(path)) {
                continue;
            }

            String theme = themeForPath(path);
            String family = familyForPath(path);
            boolean detail = isDetailPath(path);
            if (!theme.isBlank()) {
                themeCounts.merge(theme, 1, Integer::sum);
            }
            if (!family.isBlank()) {
                familyCounts.merge(family, 1, Integer::sum);
            }
            if (isWeakStructural(path)) {
                weakCount++;
            } else {
                strongCount++;
            }
            if (detail) {
                detailCount++;
            }
            minSeenY = Math.min(minSeenY, y);
            maxSeenY = Math.max(maxSeenY, y);
        }

        return new ColumnSample(strongCount, weakCount, detailCount, minSeenY, maxSeenY, themeCounts, familyCounts);
    }

    private static StructureAggregate collectStructure(long seed, Map<Long, ColumnSample> columns, Set<Long> visited) {
        ArrayDeque<Long> queue = new ArrayDeque<>();
        queue.add(seed);

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int columnCount = 0;
        int detailCount = 0;
        Map<String, Integer> themeCounts = new HashMap<>();
        Map<String, Integer> familyCounts = new HashMap<>();

        // Flood-fill contiguous candidate columns so fences, walls, roofs, and
        // supports from one building are scored together.
        while (!queue.isEmpty()) {
            long key = queue.removeFirst();
            ColumnSample sample = columns.get(key);
            if (sample == null) {
                continue;
            }
            int x = (int) (key >> 32);
            int z = (int) key;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
            if (sample.minSeenY() != Integer.MAX_VALUE) {
                minY = Math.min(minY, sample.minSeenY());
                maxY = Math.max(maxY, sample.maxSeenY());
            }
            columnCount++;
            detailCount += sample.detailCount();
            mergeCounts(themeCounts, sample.themeCounts());
            mergeCounts(familyCounts, sample.familyCounts());

            for (long next : neighbors(x, z)) {
                if (!columns.containsKey(next) || !visited.add(next)) {
                    continue;
                }
                queue.add(next);
            }
        }

        return new StructureAggregate(minX, maxX, minZ, maxZ, minY, maxY, columnCount, detailCount, themeCounts, familyCounts);
    }

    private static SiteStyleProfile buildStyleProfile(List<StructureAggregate> structures) {
        if (structures == null || structures.isEmpty()) {
            return SiteStyleProfile.NONE;
        }

        // Collapse all accepted structures into the single coarse style summary
        // the planner uses today: top themes plus average footprint/height.
        Map<String, Integer> themeCounts = new HashMap<>();
        int samples = 0;
        double widthSum = 0.0;
        double depthSum = 0.0;
        double heightSum = 0.0;
        int counted = 0;

        for (StructureAggregate structure : structures) {
            if (structure == null) {
                continue;
            }
            mergeCounts(themeCounts, structure.themeCounts());
            samples += structure.themeCounts().values().stream().mapToInt(Integer::intValue).sum();
            widthSum += structure.width();
            depthSum += structure.depth();
            heightSum += Math.max(1, structure.height());
            counted++;
        }
        if (samples <= 0 || themeCounts.isEmpty() || counted <= 0) {
            return SiteStyleProfile.NONE;
        }

        List<Map.Entry<String, Integer>> sortedThemes = themeCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .toList();
        String primary = sortedThemes.get(0).getKey();
        String secondary = sortedThemes.size() > 1 ? sortedThemes.get(1).getKey() : "";
        return new SiteStyleProfile(
            primary,
            secondary,
            samples,
            counted,
            widthSum / counted,
            depthSum / counted,
            heightSum / counted
        );
    }

    private static boolean qualifiesForStyle(BuildSiteScan.NearbyStructure structure) {
        if (structure == null) {
            return false;
        }
        return structure.width() >= 4
            && structure.depth() >= 4
            && structure.width() <= 24
            && structure.depth() <= 24
            && structure.aspectRatio() <= 3.5;
    }

    private static void mergeCounts(Map<String, Integer> into, Map<String, Integer> source) {
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            into.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    private static Iterable<Long> neighbors(int x, int z) {
        return List.of(
            columnKey(x + 1, z),
            columnKey(x - 1, z),
            columnKey(x, z + 1),
            columnKey(x, z - 1)
        );
    }

    private static boolean isTerrainNoise(String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        if (TERRAIN_EXACT.contains(path)) {
            return true;
        }
        if (path.endsWith("_ore")
            || path.endsWith("_leaves")
            || path.startsWith("infested_")) {
            return true;
        }
        return path.contains("grass")
            || path.contains("flower")
            || path.contains("fern")
            || path.contains("vine")
            || path.contains("mushroom")
            || path.contains("dripleaf")
            || path.contains("coral")
            || path.contains("kelp")
            || path.contains("seagrass")
            || path.contains("lichen")
            || path.contains("amethyst_cluster")
            || path.contains("small_dripleaf")
            || path.contains("pointed_dripstone");
    }

    private static String familyForPath(String path) {
        if (path.contains("glass")) {
            return "glass";
        }
        if (path.contains("quartz")
            || path.contains("calcite")
            || path.contains("white_concrete")
            || path.contains("bone_block")) {
            return "plaster";
        }
        if (isStonePath(path)) {
            return "stone";
        }
        if (isWoodPath(path)) {
            return "oak";
        }
        if (path.contains("wool")
            || path.contains("banner")
            || path.contains("terracotta")
            || path.contains("concrete")
            || path.contains("copper")) {
            return "market";
        }
        return "";
    }

    private static String themeForPath(String path) {
        if (path.contains("wool")
            || path.contains("banner")
            || path.contains("terracotta")
            || path.contains("concrete")
            || path.contains("copper")) {
            return "market";
        }
        if (isStonePath(path)) {
            return "stone";
        }
        if (isWoodPath(path)) {
            return "oak";
        }
        return "";
    }

    private static boolean isStonePath(String path) {
        return path.contains("stone")
            || path.contains("cobbl")
            || path.contains("brick")
            || path.contains("andesite")
            || path.contains("diorite")
            || path.contains("granite")
            || path.contains("deepslate")
            || path.contains("tuff")
            || path.contains("blackstone")
            || path.contains("basalt")
            || path.contains("smooth_stone");
    }

    private static boolean isWoodPath(String path) {
        return path.contains("oak")
            || path.contains("spruce")
            || path.contains("birch")
            || path.contains("dark_oak")
            || path.contains("acacia")
            || path.contains("mangrove")
            || path.contains("cherry")
            || path.contains("jungle")
            || path.contains("bamboo")
            || path.contains("crimson")
            || path.contains("warped")
            || path.contains("planks")
            || path.contains("log")
            || path.contains("wood")
            || path.contains("stripped_");
    }

    private static boolean isWeakStructural(String path) {
        return path.contains("log")
            || path.contains("wood")
            || path.contains("stripped_");
    }

    private static boolean isDetailPath(String path) {
        return path.contains("stairs")
            || path.contains("slab")
            || path.contains("wall")
            || path.contains("fence")
            || path.contains("door")
            || path.contains("trapdoor")
            || path.contains("glass")
            || path.contains("lantern")
            || path.contains("chain");
    }

    private static long columnKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private record ColumnSample(
        int strongCount,
        int weakCount,
        int detailCount,
        int minSeenY,
        int maxSeenY,
        Map<String, Integer> themeCounts,
        Map<String, Integer> familyCounts
    ) {
        private static final ColumnSample EMPTY = new ColumnSample(0, 0, 0, Integer.MAX_VALUE, Integer.MIN_VALUE, Map.of(), Map.of());

        private boolean isCandidate() {
            if (strongCount >= 3) {
                return true;
            }
            if (strongCount >= 2 && detailCount >= 1) {
                return true;
            }
            return strongCount >= 1 && weakCount >= 2 && detailCount >= 1;
        }
    }

    private record StructureAggregate(
        int minX,
        int maxX,
        int minZ,
        int maxZ,
        int minY,
        int maxY,
        int columns,
        int detailCount,
        Map<String, Integer> themeCounts,
        Map<String, Integer> familyCounts
    ) {
        private boolean accepted() {
            if (columns < MIN_STRUCTURE_COLUMNS) {
                return false;
            }
            if (minX == Integer.MAX_VALUE || minY == Integer.MAX_VALUE) {
                return false;
            }
            boolean varied = familyCounts.size() >= 2;
            boolean detailed = detailCount >= 4;
            boolean woodLike = familyCounts.containsKey("oak");
            boolean marketLike = familyCounts.containsKey("market");
            boolean glazed = familyCounts.containsKey("glass");
            return varied || detailed || woodLike || marketLike || glazed;
        }

        private int width() {
            return maxX - minX + 1;
        }

        private int depth() {
            return maxZ - minZ + 1;
        }

        private int height() {
            return maxY - minY + 1;
        }

        private BuildSiteScan.NearbyStructure toStructure() {
            return new BuildSiteScan.NearbyStructure(minX, maxX, minZ, maxZ, minY, maxY, columns);
        }
    }
}
