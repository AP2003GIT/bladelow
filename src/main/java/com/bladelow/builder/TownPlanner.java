package com.bladelow.builder;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class TownPlanner {
    private static final int SCAN_INSET = 1;
    private static final int PLOT_SPACING = 1;
    private static final int ROAD_ADJACENT_RADIUS = 3;
    private static final int SYNTHETIC_ROAD_THRESHOLD = 10;
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

    private TownPlanner() {
    }

    public static TownPlan plan(ServerWorld world, BlockPos from, BlockPos to, List<TownBlueprint> blueprints, List<TownZoneStore.Zone> zones) {
        if (world == null || from == null || to == null) {
            return TownPlan.error("invalid townfill bounds");
        }
        List<TownBlueprint> usable = blueprints == null ? List.of() : blueprints.stream()
            .filter(blueprint -> blueprint != null && !blueprint.placements().isEmpty())
            .sorted(Comparator.comparingInt(TownBlueprint::priority).reversed().thenComparing(TownBlueprint::name))
            .toList();
        if (usable.isEmpty()) {
            return TownPlan.error("no town blueprints loaded; run /bladeblueprint reload");
        }

        TownArea area = TownArea.analyze(world, from, to, zones);
        if (area.width() < 7 || area.depth() < 7) {
            return TownPlan.error("area too small for townfill");
        }

        Set<Long> reserved = new HashSet<>(area.streetCells());
        Map<String, Integer> usageCounts = new HashMap<>();
        LinkedHashMap<String, Integer> districtUsage = initDistrictCounters();
        LinkedHashMap<String, Integer> zoneUsage = initDistrictCounters();
        List<BlockState> blockStates = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        LinkedHashSet<String> used = new LinkedHashSet<>();
        int roadBlocks = appendRoadPlacements(area, blockStates, targets);
        List<LotCandidate> lots = buildLots(area);
        int buildings = 0;
        int consumedLots = 0;

        for (LotCandidate lot : lots) {
            PlotPlacement placement = chooseLotPlacement(area, lot, reserved, usable, usageCounts, districtUsage);
            if (placement == null) {
                continue;
            }
            if (!appendPlacements(area, placement, blockStates, targets)) {
                continue;
            }
            reservePlot(reserved, placement.originX(), placement.originZ(), placement.blueprint().plotWidth(), placement.blueprint().plotDepth(), PLOT_SPACING);
            usageCounts.merge(placement.blueprint().name(), 1, Integer::sum);
            districtUsage.merge(districtTypeOf(placement.blueprint()), 1, Integer::sum);
            String zone = area.zoneTypeAt(
                placement.originX() + Math.max(0, placement.blueprint().plotWidth() / 2),
                placement.originZ() + Math.max(0, placement.blueprint().plotDepth() / 2)
            );
            if (!zone.isBlank()) {
                zoneUsage.merge(zone, 1, Integer::sum);
            }
            if (used.size() < 8) {
                used.add(
                    placement.blueprint().name()
                        + "@lot"
                        + placement.lot().order()
                        + ":"
                        + String.format(Locale.ROOT, "%.1f", placement.score())
                );
            }
            buildings++;
            consumedLots++;
        }

        if (buildings == 0 || blockStates.isEmpty() || targets.isEmpty()) {
            return TownPlan.error("no clear lots fit town blueprints in selected area");
        }

        StringBuilder message = new StringBuilder();
        message.append("townfill buildings=").append(buildings)
            .append(" lots=").append(consumedLots).append("/").append(lots.size())
            .append(" roadBlocks=").append(roadBlocks)
            .append(" plazas=").append(area.plazaCount())
            .append(" targets=").append(targets.size())
            .append(" roads=").append(area.roadCount())
            .append(" gates=").append(area.gateCount());
        if (area.syntheticRoads()) {
            message.append(" layout=synthetic-grid");
        } else {
            message.append(" layout=detected-roads");
        }
        message.append(" districts=").append(formatDistrictCounters(districtUsage));
        if (zoneUsage.values().stream().anyMatch(v -> v > 0)) {
            message.append(" zoneHits=").append(formatDistrictCounters(zoneUsage));
        }
        if (!used.isEmpty()) {
            message.append(" sample=").append(String.join(",", used));
        }
        return TownPlan.ok(message.toString(), blockStates, targets, buildings, List.copyOf(used));
    }

    private static int appendRoadPlacements(TownArea area, List<BlockState> blockStates, List<BlockPos> targets) {
        LinkedHashMap<Long, BlockState> planned = new LinkedHashMap<>();
        int added = 0;
        for (RoadNode road : area.orderedRoads()) {
            planned.put(columnKey(road.x(), road.z()), (road.primary() ? Blocks.STONE_BRICKS : Blocks.COBBLESTONE).getDefaultState());
        }
        for (long key : area.plazaCells()) {
            planned.put(key, Blocks.POLISHED_ANDESITE.getDefaultState());
        }
        for (Map.Entry<Long, BlockState> entry : planned.entrySet()) {
            int x = (int) (entry.getKey() >> 32);
            int z = (int) (long) entry.getKey();
            BlockPos pos = new BlockPos(x, area.baseY(), z);
            BlockState state = area.world().getBlockState(pos);
            if (isRoadLikeSurface(state) || !state.getFluidState().isEmpty()) {
                continue;
            }
            blockStates.add(entry.getValue());
            targets.add(pos);
            added++;
        }
        return added;
    }

    private static List<LotCandidate> buildLots(TownArea area) {
        Map<String, LotCandidate> unique = new LinkedHashMap<>();
        Map<String, LotCandidate> fallback = new LinkedHashMap<>();
        int order = 0;
        for (RoadNode road : area.orderedRoads()) {
            for (String side : List.of("north", "south", "west", "east")) {
                int entryX = road.x() + sideEntryDx(side);
                int entryZ = road.z() + sideEntryDz(side);
                if (!area.inInterior(entryX, entryZ) || area.hasStreet(entryX, entryZ)) {
                    continue;
                }
                String key = side + ":" + entryX + ":" + entryZ;
                if (unique.containsKey(key)) {
                    continue;
                }
                double baseScore = area.roadScore(entryX, entryZ) * 8.0
                    + area.centerScore(entryX, entryZ) * 6.0
                    + area.gateScore(entryX, entryZ) * 5.0
                    + area.zonePresenceScore(entryX, entryZ) * 3.0
                    + (road.primary() ? 4.0 : 0.0);
                LotCandidate candidate = new LotCandidate(road.x(), road.z(), entryX, entryZ, side, road.primary(), order++, baseScore);
                if (area.hasZones() && !area.hasZoneNearby(entryX, entryZ, 2)) {
                    fallback.put(key, candidate);
                } else {
                    unique.put(key, candidate);
                }
            }
        }
        if (unique.isEmpty() && !fallback.isEmpty()) {
            unique.putAll(fallback);
        }
        List<LotCandidate> lots = new ArrayList<>(unique.values());
        lots.sort(
            Comparator.comparingDouble(LotCandidate::baseScore).reversed()
                .thenComparingInt(lot -> lot.primaryRoad() ? 0 : 1)
                .thenComparingInt(LotCandidate::order)
        );
        return lots;
    }

    private static PlotPlacement chooseLotPlacement(
        TownArea area,
        LotCandidate lot,
        Set<Long> reserved,
        List<TownBlueprint> blueprints,
        Map<String, Integer> usageCounts,
        Map<String, Integer> districtUsage
    ) {
        PlotPlacement best = null;
        for (TownBlueprint blueprint : blueprints) {
            TownBlueprint oriented = blueprint.orientedForRoadSide(lot.side());
            int originX = originXForLot(lot, oriented);
            int originZ = originZForLot(lot, oriented);
            if (!fitsBounds(area, originX, originZ, oriented)) {
                continue;
            }
            if (isReserved(originX, originZ, oriented.plotWidth(), oriented.plotDepth(), reserved, PLOT_SPACING)) {
                continue;
            }
            if (!isPlotClear(area.world(), originX, area.baseY(), originZ, oriented)) {
                continue;
            }
            String districtType = districtTypeOf(oriented);
            double score = scoreBlueprint(
                area,
                originX,
                originZ,
                lot,
                oriented,
                usageCounts.getOrDefault(blueprint.name(), 0),
                districtUsage.getOrDefault(districtType, 0)
            );
            if (best == null || score > best.score()) {
                best = new PlotPlacement(oriented, lot, originX, originZ, score);
            }
        }
        return best;
    }

    private static boolean appendPlacements(TownArea area, PlotPlacement placement, List<BlockState> blockStates, List<BlockPos> targets) {
        for (TownBlueprint.Placement blockPlacement : placement.blueprint().placements()) {
            BlockState state = BlueprintStateCodec.tryParse(blockPlacement.blockId());
            if (state == null) {
                return false;
            }
            blockStates.add(BlueprintStateCodec.rotate(state, placement.blueprint().rotationTurns()));
            targets.add(new BlockPos(
                placement.originX() + blockPlacement.x(),
                area.baseY() + blockPlacement.y(),
                placement.originZ() + blockPlacement.z()
            ));
        }
        return true;
    }

    private static double scoreBlueprint(TownArea area, int originX, int originZ, LotCandidate lot, TownBlueprint blueprint, int usedCount, int districtUsedCount) {
        int plotCenterX = originX + Math.max(0, blueprint.plotWidth() / 2);
        int plotCenterZ = originZ + Math.max(0, blueprint.plotDepth() / 2);
        int entranceX = originX + blueprint.entranceOffsetX();
        int entranceZ = originZ + blueprint.entranceOffsetZ();

        double centerScore = area.centerScore(plotCenterX, plotCenterZ);
        double wallScore = area.wallScore(plotCenterX, plotCenterZ);
        double gateScore = area.gateScore(entranceX, entranceZ);
        double roadScore = area.roadScore(entranceX, entranceZ);
        double roadSideScore = roadSideScore(area, originX, originZ, blueprint);
        double zoneScore = area.zoneScore(blueprint, plotCenterX, plotCenterZ, entranceX, entranceZ);

        double score = lot.baseScore();
        score += blueprint.priority() * 24.0;
        score += roadScore * 12.0;
        score += roadSideScore * 10.0;
        score += zoneScore;
        if (lot.primaryRoad()) {
            score += 5.0;
        }

        if (blueprint.hasAnyTag("house", "residential")) {
            score += centerScore * 18.0;
            score += roadScore * 8.0;
            score += (1.0 - wallScore) * 8.0;
            score -= gateScore * 2.0;
        }
        if (blueprint.hasAnyTag("market", "stall", "shop")) {
            score += gateScore * 20.0;
            score += roadScore * 18.0;
            score += roadSideScore * 16.0;
        }
        if (blueprint.hasAnyTag("smithy", "workshop", "utility")) {
            score += wallScore * 16.0;
            score += roadScore * 12.0;
            score += gateScore * 8.0;
        }
        if (blueprint.hasAnyTag("civic", "hall", "keep", "plaza")) {
            score += centerScore * 20.0;
            score += roadScore * 10.0;
            score += lot.primaryRoad() ? 6.0 : 0.0;
        }
        if (blueprint.hasAnyTag("decor", "detail")) {
            score += gateScore * 8.0;
            score += roadScore * 10.0;
        }
        if (!blueprint.hasAnyTag("house", "residential", "market", "stall", "shop", "smithy", "workshop", "utility", "civic", "hall", "keep", "plaza", "decor", "detail")) {
            score += centerScore * 8.0;
            score += roadScore * 8.0;
        }

        if (!blueprint.roadSide().isBlank() && roadSideScore < 0.5) {
            score -= 8.0;
        }
        score -= usedCount * 14.0;
        score -= districtUsedCount * 4.0;
        score += Math.floorMod(originX * 17 + originZ * 13 + blueprint.name().hashCode(), 11) / 100.0;
        return score;
    }

    private static LinkedHashMap<String, Integer> initDistrictCounters() {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (TownDistrictType type : TownDistrictType.values()) {
            counts.put(type.id(), 0);
        }
        return counts;
    }

    private static String formatDistrictCounters(Map<String, Integer> counts) {
        List<String> parts = new ArrayList<>();
        for (TownDistrictType type : TownDistrictType.values()) {
            int value = counts.getOrDefault(type.id(), 0);
            if (value > 0) {
                parts.add(type.id() + "=" + value);
            }
        }
        return parts.isEmpty() ? "none" : String.join(",", parts);
    }

    private static String districtTypeOf(TownBlueprint blueprint) {
        if (blueprint == null) {
            return TownDistrictType.MIXED.id();
        }
        if (blueprint.hasAnyTag("civic", "hall", "keep", "plaza")) {
            return TownDistrictType.CIVIC.id();
        }
        if (blueprint.hasAnyTag("market", "stall", "shop")) {
            return TownDistrictType.MARKET.id();
        }
        if (blueprint.hasAnyTag("smithy", "workshop", "utility", "storage")) {
            return TownDistrictType.WORKSHOP.id();
        }
        if (blueprint.hasAnyTag("house", "residential")) {
            return TownDistrictType.RESIDENTIAL.id();
        }
        return TownDistrictType.MIXED.id();
    }

    private static double roadSideScore(TownArea area, int originX, int originZ, TownBlueprint blueprint) {
        String side = blueprint.roadSide();
        if (side.isBlank()) {
            return 0.0;
        }
        boolean roadTouch = false;
        boolean roadNear = false;
        switch (side) {
            case "north" -> {
                int z = originZ - 1;
                for (int x = originX; x < originX + blueprint.plotWidth(); x++) {
                    roadTouch |= area.hasStreet(x, z);
                    roadNear |= area.hasStreetNear(x, z, 1);
                }
            }
            case "south" -> {
                int z = originZ + blueprint.plotDepth();
                for (int x = originX; x < originX + blueprint.plotWidth(); x++) {
                    roadTouch |= area.hasStreet(x, z);
                    roadNear |= area.hasStreetNear(x, z, 1);
                }
            }
            case "west" -> {
                int x = originX - 1;
                for (int z = originZ; z < originZ + blueprint.plotDepth(); z++) {
                    roadTouch |= area.hasStreet(x, z);
                    roadNear |= area.hasStreetNear(x, z, 1);
                }
            }
            case "east" -> {
                int x = originX + blueprint.plotWidth();
                for (int z = originZ; z < originZ + blueprint.plotDepth(); z++) {
                    roadTouch |= area.hasStreet(x, z);
                    roadNear |= area.hasStreetNear(x, z, 1);
                }
            }
            default -> {
                return 0.0;
            }
        }
        if (roadTouch) {
            return 1.0;
        }
        return roadNear ? 0.5 : 0.0;
    }

    private static int originXForLot(LotCandidate lot, TownBlueprint blueprint) {
        return switch (lot.side()) {
            case "north", "south" -> lot.roadX() - blueprint.entranceOffsetX();
            case "west" -> lot.roadX() + 1 - blueprint.entranceOffsetX();
            case "east" -> lot.roadX() - 1 - blueprint.entranceOffsetX();
            default -> lot.entryX();
        };
    }

    private static int originZForLot(LotCandidate lot, TownBlueprint blueprint) {
        return switch (lot.side()) {
            case "north" -> lot.roadZ() + 1 - blueprint.entranceOffsetZ();
            case "south" -> lot.roadZ() - 1 - blueprint.entranceOffsetZ();
            case "west", "east" -> lot.roadZ() - blueprint.entranceOffsetZ();
            default -> lot.entryZ();
        };
    }

    private static boolean fitsBounds(TownArea area, int originX, int originZ, TownBlueprint blueprint) {
        int startX = originX - PLOT_SPACING;
        int endX = originX + blueprint.plotWidth() - 1 + PLOT_SPACING;
        int startZ = originZ - PLOT_SPACING;
        int endZ = originZ + blueprint.plotDepth() - 1 + PLOT_SPACING;
        return startX >= area.minX() + SCAN_INSET
            && endX <= area.maxX() - SCAN_INSET
            && startZ >= area.minZ() + SCAN_INSET
            && endZ <= area.maxZ() - SCAN_INSET;
    }

    private static boolean isPlotClear(ServerWorld world, int originX, int baseY, int originZ, TownBlueprint blueprint) {
        int topY = baseY + Math.max(2, blueprint.height() + 1);
        for (int x = originX; x < originX + blueprint.plotWidth(); x++) {
            for (int z = originZ; z < originZ + blueprint.plotDepth(); z++) {
                BlockPos groundPos = new BlockPos(x, baseY, z);
                BlockState ground = world.getBlockState(groundPos);
                if (ground.getCollisionShape(world, groundPos).isEmpty() || !ground.getFluidState().isEmpty()) {
                    return false;
                }
                for (int y = baseY + 1; y <= topY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (!state.isAir() && !state.isReplaceable()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean isReserved(int originX, int originZ, int width, int depth, Set<Long> reserved, int spacing) {
        for (int x = originX - spacing; x < originX + width + spacing; x++) {
            for (int z = originZ - spacing; z < originZ + depth + spacing; z++) {
                if (reserved.contains(columnKey(x, z))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void reservePlot(Set<Long> reserved, int originX, int originZ, int width, int depth, int spacing) {
        for (int x = originX - spacing; x < originX + width + spacing; x++) {
            for (int z = originZ - spacing; z < originZ + depth + spacing; z++) {
                reserved.add(columnKey(x, z));
            }
        }
    }

    private static int sideEntryDx(String side) {
        return switch (side) {
            case "west" -> 1;
            case "east" -> -1;
            default -> 0;
        };
    }

    private static int sideEntryDz(String side) {
        return switch (side) {
            case "north" -> 1;
            case "south" -> -1;
            default -> 0;
        };
    }

    private static long columnKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
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

    private record PlotPlacement(TownBlueprint blueprint, LotCandidate lot, int originX, int originZ, double score) {
    }

    private record LotCandidate(
        int roadX,
        int roadZ,
        int entryX,
        int entryZ,
        String side,
        boolean primaryRoad,
        int order,
        double baseScore
    ) {
    }

    private record RoadNode(int x, int z, boolean primary) {
    }

    private record TownArea(
        ServerWorld world,
        int minX,
        int maxX,
        int minZ,
        int maxZ,
        int baseY,
        double centerX,
        double centerZ,
        Set<Long> roadCells,
        Set<Long> plazaCells,
        Set<Long> streetCells,
        List<RoadNode> orderedRoads,
        List<BlockPos> gateCells,
        List<TownZoneStore.Zone> zones,
        boolean syntheticRoads
    ) {
        private static TownArea analyze(ServerWorld world, BlockPos from, BlockPos to, List<TownZoneStore.Zone> zones) {
            int minX = Math.min(from.getX(), to.getX());
            int maxX = Math.max(from.getX(), to.getX());
            int minZ = Math.min(from.getZ(), to.getZ());
            int maxZ = Math.max(from.getZ(), to.getZ());
            int baseY = Math.min(from.getY(), to.getY());

            LinkedHashSet<Long> scannedRoads = new LinkedHashSet<>();
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (isRoadLikeSurface(world.getBlockState(new BlockPos(x, baseY, z)))) {
                        scannedRoads.add(columnKey(x, z));
                    }
                }
            }

            boolean synthetic = scannedRoads.size() < Math.max(SYNTHETIC_ROAD_THRESHOLD, (maxX - minX + maxZ - minZ) / 2);
            LinkedHashSet<Long> allRoads = new LinkedHashSet<>(scannedRoads);
            List<RoadNode> roads = synthetic
                ? synthesizeRoadNodes(minX, maxX, minZ, maxZ, allRoads)
                : orderedDetectedRoads(scannedRoads, minX, maxX, minZ, maxZ);
            if (synthetic && !scannedRoads.isEmpty()) {
                roads.addAll(orderedDetectedRoads(scannedRoads, minX, maxX, minZ, maxZ));
            }
            roads = dedupeRoadNodes(roads);
            LinkedHashSet<Long> plazas = detectPlazaCells(minX, maxX, minZ, maxZ, roads);
            LinkedHashSet<Long> streetCells = new LinkedHashSet<>(allRoads);
            streetCells.addAll(plazas);
            List<BlockPos> gateCells = detectGateCells(minX, maxX, minZ, maxZ, baseY, allRoads);
            List<TownZoneStore.Zone> activeZones = filterZones(zones, minX, maxX, minZ, maxZ);
            return new TownArea(
                world,
                minX,
                maxX,
                minZ,
                maxZ,
                baseY,
                (minX + maxX) / 2.0,
                (minZ + maxZ) / 2.0,
                Set.copyOf(allRoads),
                Set.copyOf(plazas),
                Set.copyOf(streetCells),
                List.copyOf(roads),
                List.copyOf(gateCells),
                List.copyOf(activeZones),
                synthetic
            );
        }

        private static List<RoadNode> synthesizeRoadNodes(int minX, int maxX, int minZ, int maxZ, Set<Long> allRoads) {
            List<RoadNode> roads = new ArrayList<>();
            int centerX = (minX + maxX) / 2;
            int centerZ = (minZ + maxZ) / 2;
            for (int z = minZ + SCAN_INSET; z <= maxZ - SCAN_INSET; z++) {
                roads.add(addRoad(allRoads, centerX, z, true));
            }
            for (int x = minX + SCAN_INSET; x <= maxX - SCAN_INSET; x++) {
                roads.add(addRoad(allRoads, x, centerZ, true));
            }
            if ((maxX - minX) >= 24) {
                int qx1 = minX + (maxX - minX) / 3;
                int qx2 = maxX - (maxX - minX) / 3;
                for (int z = minZ + 2; z <= maxZ - 2; z++) {
                    roads.add(addRoad(allRoads, qx1, z, false));
                    roads.add(addRoad(allRoads, qx2, z, false));
                }
            }
            if ((maxZ - minZ) >= 24) {
                int qz1 = minZ + (maxZ - minZ) / 3;
                int qz2 = maxZ - (maxZ - minZ) / 3;
                for (int x = minX + 2; x <= maxX - 2; x++) {
                    roads.add(addRoad(allRoads, x, qz1, false));
                    roads.add(addRoad(allRoads, x, qz2, false));
                }
            }
            return dedupeRoadNodes(roads);
        }

        private static List<RoadNode> orderedDetectedRoads(Set<Long> scannedRoads, int minX, int maxX, int minZ, int maxZ) {
            double centerX = (minX + maxX) / 2.0;
            double centerZ = (minZ + maxZ) / 2.0;
            return scannedRoads.stream()
                .map(key -> new RoadNode((int) (key.longValue() >> 32), (int) key.longValue(), false))
                .sorted(
                    Comparator.comparingDouble((RoadNode node) -> Math.hypot(node.x() - centerX, node.z() - centerZ))
                        .thenComparingInt(RoadNode::z)
                        .thenComparingInt(RoadNode::x)
                )
                .toList();
        }

        private static RoadNode addRoad(Set<Long> allRoads, int x, int z, boolean primary) {
            allRoads.add(columnKey(x, z));
            return new RoadNode(x, z, primary);
        }

        private static List<RoadNode> dedupeRoadNodes(List<RoadNode> roads) {
            LinkedHashMap<Long, RoadNode> deduped = new LinkedHashMap<>();
            for (RoadNode road : roads) {
                long key = columnKey(road.x(), road.z());
                RoadNode existing = deduped.get(key);
                if (existing == null || (!existing.primary() && road.primary())) {
                    deduped.put(key, road);
                }
            }
            return new ArrayList<>(deduped.values());
        }

        private static List<BlockPos> detectGateCells(int minX, int maxX, int minZ, int maxZ, int baseY, Set<Long> roadCells) {
            LinkedHashSet<Long> gateKeys = new LinkedHashSet<>();
            for (long key : roadCells) {
                int x = (int) (key >> 32);
                int z = (int) key;
                if ((x - minX) <= 2 || (maxX - x) <= 2 || (z - minZ) <= 2 || (maxZ - z) <= 2) {
                    gateKeys.add(columnKey(x, z));
                }
            }
            if (gateKeys.isEmpty()) {
                gateKeys.add(columnKey((minX + maxX) / 2, minZ + SCAN_INSET));
                gateKeys.add(columnKey((minX + maxX) / 2, maxZ - SCAN_INSET));
                gateKeys.add(columnKey(minX + SCAN_INSET, (minZ + maxZ) / 2));
                gateKeys.add(columnKey(maxX - SCAN_INSET, (minZ + maxZ) / 2));
            }
            List<BlockPos> gates = new ArrayList<>(gateKeys.size());
            for (long key : gateKeys) {
                int x = (int) (key >> 32);
                int z = (int) key;
                gates.add(new BlockPos(x, baseY, z));
            }
            return gates;
        }

        private static LinkedHashSet<Long> detectPlazaCells(int minX, int maxX, int minZ, int maxZ, List<RoadNode> roads) {
            LinkedHashMap<Long, RoadNode> byKey = new LinkedHashMap<>();
            for (RoadNode road : roads) {
                byKey.put(columnKey(road.x(), road.z()), road);
            }
            LinkedHashSet<Long> plazas = new LinkedHashSet<>();
            for (RoadNode road : roads) {
                if (!road.primary()) {
                    continue;
                }
                boolean horizontal = byKey.containsKey(columnKey(road.x() - 1, road.z()))
                    || byKey.containsKey(columnKey(road.x() + 1, road.z()));
                boolean vertical = byKey.containsKey(columnKey(road.x(), road.z() - 1))
                    || byKey.containsKey(columnKey(road.x(), road.z() + 1));
                if (!horizontal || !vertical) {
                    continue;
                }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int x = road.x() + dx;
                        int z = road.z() + dz;
                        if (x < minX + SCAN_INSET || x > maxX - SCAN_INSET || z < minZ + SCAN_INSET || z > maxZ - SCAN_INSET) {
                            continue;
                        }
                        plazas.add(columnKey(x, z));
                    }
                }
            }
            return plazas;
        }

        private static List<TownZoneStore.Zone> filterZones(List<TownZoneStore.Zone> zones, int minX, int maxX, int minZ, int maxZ) {
            if (zones == null || zones.isEmpty()) {
                return List.of();
            }
            return zones.stream()
                .filter(zone -> zone != null && zone.intersects(minX, maxX, minZ, maxZ))
                .toList();
        }

        private int width() {
            return maxX - minX + 1;
        }

        private int depth() {
            return maxZ - minZ + 1;
        }

        private boolean inInterior(int x, int z) {
            return x >= minX + SCAN_INSET && x <= maxX - SCAN_INSET && z >= minZ + SCAN_INSET && z <= maxZ - SCAN_INSET;
        }

        private int roadCount() {
            return roadCells.size();
        }

        private int plazaCount() {
            return plazaCells.size();
        }

        private int gateCount() {
            return gateCells.size();
        }

        private boolean hasRoad(int x, int z) {
            return roadCells.contains(columnKey(x, z));
        }

        private boolean hasStreet(int x, int z) {
            long key = columnKey(x, z);
            return roadCells.contains(key) || plazaCells.contains(key);
        }

        private boolean hasStreetNear(int x, int z, int radius) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (hasStreet(x + dx, z + dz)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private double centerScore(int x, int z) {
            double dist = Math.hypot(x - centerX, z - centerZ);
            double maxDist = Math.max(1.0, Math.hypot(width() / 2.0, depth() / 2.0));
            return clamp01(1.0 - (dist / maxDist));
        }

        private double wallScore(int x, int z) {
            int wallDist = Math.min(Math.min(x - minX, maxX - x), Math.min(z - minZ, maxZ - z));
            double maxWallDist = Math.max(1.0, Math.min(width(), depth()) / 2.0);
            return clamp01(1.0 - (wallDist / maxWallDist));
        }

        private double gateScore(int x, int z) {
            double maxGateDist = Math.max(1.0, width() + depth());
            double best = maxGateDist;
            for (BlockPos gate : gateCells) {
                double dist = Math.abs(gate.getX() - x) + Math.abs(gate.getZ() - z);
                best = Math.min(best, dist);
            }
            return clamp01(1.0 - (best / maxGateDist));
        }

        private double roadScore(int x, int z) {
            if (streetCells.isEmpty()) {
                return gateScore(x, z) * 0.5;
            }
            double maxRoadDist = Math.max(1.0, width() + depth());
            double best = maxRoadDist;
            for (long key : streetCells) {
                int roadX = (int) (key >> 32);
                int roadZ = (int) (long) key;
                double dist = Math.abs(roadX - x) + Math.abs(roadZ - z);
                best = Math.min(best, dist);
                if (best <= ROAD_ADJACENT_RADIUS) {
                    break;
                }
            }
            return clamp01(1.0 - (best / maxRoadDist));
        }

        private double zonePresenceScore(int x, int z) {
            return zoneTypeAt(x, z).isBlank() ? 0.0 : 1.0;
        }

        private double zoneScore(TownBlueprint blueprint, int centerX, int centerZ, int entranceX, int entranceZ) {
            String centerZone = zoneTypeAt(centerX, centerZ);
            String entranceZone = zoneTypeAt(entranceX, entranceZ);
            if (centerZone.isBlank() && entranceZone.isBlank()) {
                if (!zones.isEmpty()) {
                    // Hard bias: if districts exist, out-of-zone buildings are strongly discouraged.
                    return -24.0;
                }
                return 0.0;
            }
            double centerScore = zonePreferenceScore(blueprint, centerZone);
            double entranceScore = zonePreferenceScore(blueprint, entranceZone) * 0.85;
            return Math.max(centerScore, entranceScore);
        }

        private boolean hasZones() {
            return !zones.isEmpty();
        }

        private boolean hasZoneNearby(int x, int z, int radius) {
            if (zones.isEmpty()) {
                return true;
            }
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (!zoneTypeAt(x + dx, z + dz).isBlank()) {
                        return true;
                    }
                }
            }
            return false;
        }

        private String zoneTypeAt(int x, int z) {
            TownZoneStore.Zone best = null;
            for (TownZoneStore.Zone zone : zones) {
                if (!zone.contains(x, z)) {
                    continue;
                }
                if (best == null || zone.area() < best.area()) {
                    best = zone;
                }
            }
            return best == null ? "" : best.type();
        }

        private double zonePreferenceScore(TownBlueprint blueprint, String zoneType) {
            if (zoneType == null || zoneType.isBlank()) {
                return 0.0;
            }
            return switch (zoneType) {
                case "residential" -> {
                    if (blueprint.hasAnyTag("house", "residential")) {
                        yield 42.0;
                    }
                    if (blueprint.hasAnyTag("decor", "detail")) {
                        yield 8.0;
                    }
                    if (blueprint.hasAnyTag("market", "stall", "shop")) {
                        yield -16.0;
                    }
                    if (blueprint.hasAnyTag("smithy", "workshop", "utility")) {
                        yield -12.0;
                    }
                    if (blueprint.hasAnyTag("civic", "hall", "keep", "plaza")) {
                        yield -6.0;
                    }
                    yield 0.0;
                }
                case "market" -> {
                    if (blueprint.hasAnyTag("market", "stall", "shop")) {
                        yield 42.0;
                    }
                    if (blueprint.hasAnyTag("civic", "hall", "plaza")) {
                        yield 12.0;
                    }
                    if (blueprint.hasAnyTag("decor", "detail")) {
                        yield 10.0;
                    }
                    if (blueprint.hasAnyTag("house", "residential")) {
                        yield -10.0;
                    }
                    if (blueprint.hasAnyTag("smithy", "workshop", "utility")) {
                        yield -6.0;
                    }
                    yield 0.0;
                }
                case "workshop" -> {
                    if (blueprint.hasAnyTag("smithy", "workshop", "utility")) {
                        yield 42.0;
                    }
                    if (blueprint.hasAnyTag("decor", "detail")) {
                        yield 6.0;
                    }
                    if (blueprint.hasAnyTag("house", "residential")) {
                        yield -12.0;
                    }
                    if (blueprint.hasAnyTag("market", "stall", "shop")) {
                        yield -8.0;
                    }
                    if (blueprint.hasAnyTag("civic", "hall", "keep", "plaza")) {
                        yield -4.0;
                    }
                    yield 0.0;
                }
                case "civic" -> {
                    if (blueprint.hasAnyTag("civic", "hall", "keep", "plaza")) {
                        yield 44.0;
                    }
                    if (blueprint.hasAnyTag("market", "stall", "shop")) {
                        yield 10.0;
                    }
                    if (blueprint.hasAnyTag("decor", "detail")) {
                        yield 8.0;
                    }
                    if (blueprint.hasAnyTag("house", "residential")) {
                        yield -12.0;
                    }
                    if (blueprint.hasAnyTag("smithy", "workshop", "utility")) {
                        yield -8.0;
                    }
                    yield 0.0;
                }
                case "mixed" -> {
                    if (blueprint.hasAnyTag("house", "residential")) {
                        yield 16.0;
                    }
                    if (blueprint.hasAnyTag("market", "stall", "shop")) {
                        yield 16.0;
                    }
                    if (blueprint.hasAnyTag("smithy", "workshop", "utility")) {
                        yield 16.0;
                    }
                    if (blueprint.hasAnyTag("civic", "hall", "keep", "plaza")) {
                        yield 12.0;
                    }
                    if (blueprint.hasAnyTag("decor", "detail")) {
                        yield 10.0;
                    }
                    yield 0.0;
                }
                default -> 0.0;
            };
        }

        private double clamp01(double value) {
            if (value < 0.0) {
                return 0.0;
            }
            if (value > 1.0) {
                return 1.0;
            }
            return value;
        }
    }
}
