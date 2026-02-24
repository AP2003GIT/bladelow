package com.bladelow.builder;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BuildNavigation {
    private static final int BASE_MAX_PATH_EXPANDED = 3200;
    private static final int HARD_MAX_PATH_EXPANDED = 12000;
    private static final long PATH_CACHE_TTL_MS = 12_000L;
    private static final long TEMP_BLOCK_MS_SOFT = 8_000L;
    private static final long TEMP_BLOCK_MS_HARD = 18_000L;
    private static final int MAX_TEMP_BLOCKED_PER_PLAYER = 640;
    private static final Map<UUID, Map<PathKey, Long>> TEMP_BLOCKED = new ConcurrentHashMap<>();
    private static final Map<UUID, CachedPathPlan> PATH_PLANS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> BLACKLIST_HITS = new ConcurrentHashMap<>();

    private BuildNavigation() {
    }

    public static void invalidateCachedPath(UUID playerId) {
        if (playerId == null) {
            return;
        }
        PATH_PLANS.remove(playerId);
    }

    public static void noteExternalFailure(UUID playerId, BlockPos pos, String reason, boolean hard) {
        if (playerId == null || pos == null) {
            return;
        }
        long duration = hard ? Math.max(TEMP_BLOCK_MS_HARD, 24_000L) : TEMP_BLOCK_MS_SOFT;
        rememberTemporarilyBlocked(playerId, pos.getX(), pos.getY(), pos.getZ(), "external_" + (reason == null ? "failure" : reason), duration);
        if (hard) {
            invalidateCachedPath(playerId);
        }
    }

    public static boolean backtrackToLastSafe(ServerWorld world, ServerPlayerEntity player, int maxLookback) {
        if (world == null || player == null) {
            return false;
        }
        UUID playerId = player.getUuid();
        CachedPathPlan plan = PATH_PLANS.get(playerId);
        if (plan == null || plan.path().isEmpty()) {
            return false;
        }
        int from = Math.max(0, Math.min(plan.cursor() - 1, plan.path().size() - 1));
        int to = Math.max(0, from - Math.max(1, maxLookback));
        for (int idx = from; idx >= to; idx--) {
            BlockPos step = plan.path().get(idx);
            if (!canStandAtBlock(world, step.getX(), step.getY(), step.getZ())) {
                continue;
            }
            player.requestTeleport(step.getX() + 0.5, step.getY(), step.getZ() + 0.5);
            plan.cursor(idx);
            plan.lastUsedAt(System.currentTimeMillis());
            return true;
        }
        return false;
    }

    public static int consumeBlacklistHits(UUID playerId) {
        if (playerId == null) {
            return 0;
        }
        Integer hits = BLACKLIST_HITS.remove(playerId);
        return hits == null ? 0 : Math.max(0, hits);
    }

    public static MoveResult ensureInRangeForPlacement(
        ServerWorld world,
        ServerPlayerEntity player,
        BlockPos target,
        BuildRuntimeSettings.Snapshot settings
    ) {
        UUID playerId = player.getUuid();
        pruneCachedPlan(playerId);

        double targetX = target.getX() + 0.5;
        double targetY = target.getY() + 0.5;
        double targetZ = target.getZ() + 0.5;

        double reach = settings.reachDistance();
        double dist = Math.sqrt(player.squaredDistanceTo(targetX, targetY, targetZ));
        if (dist <= reach) {
            PATH_PLANS.remove(playerId);
            return MoveResult.alreadyInRange(dist);
        }

        if (!settings.smartMoveEnabled()) {
            return MoveResult.failed("smart_move_disabled", dist);
        }

        List<ApproachCandidate> approaches = selectApproachCandidates(world, player, targetX, targetY, targetZ, reach);
        if (approaches.isEmpty()) {
            return MoveResult.failed("no_approach_candidate", dist);
        }

        int walkAttempts = Math.min(8, approaches.size());
        int teleportAttempts = Math.min(4, approaches.size());
        MoveResult bestFailure = null;

        return switch (settings.moveMode()) {
            case TELEPORT -> {
                for (int i = 0; i < teleportAttempts; i++) {
                    ApproachCandidate approach = approaches.get(i);
                    MoveResult tp = moveTeleport(
                        player,
                        approach.x(),
                        approach.y(),
                        approach.z(),
                        targetX,
                        targetY,
                        targetZ,
                        reach,
                        "teleport#" + (i + 1)
                    );
                    if (tp.status() >= 0) {
                        yield tp;
                    }
                    rememberTemporarilyBlocked(playerId, approach, tp.reason());
                    bestFailure = pickBetterFailure(bestFailure, tp);
                }
                yield bestFailure == null ? MoveResult.failed("teleport_failed", dist) : bestFailure;
            }
            case WALK -> {
                for (int i = 0; i < walkAttempts; i++) {
                    ApproachCandidate approach = approaches.get(i);
                    MoveResult walk = moveWalk(world, player, approach.x(), approach.y(), approach.z(), targetX, targetY, targetZ, reach);
                    if (walk.status() >= 0) {
                        yield walk;
                    }
                    rememberTemporarilyBlocked(playerId, approach, walk.reason());
                    bestFailure = pickBetterFailure(bestFailure, walk);
                }
                ApproachCandidate fallbackApproach = approaches.get(0);
                MoveResult fallback = moveTeleport(
                    player,
                    fallbackApproach.x(),
                    fallbackApproach.y(),
                    fallbackApproach.z(),
                    targetX,
                    targetY,
                    targetZ,
                    reach,
                    "walk_final_fallback_teleport"
                );
                if (fallback.status() >= 0) {
                    yield fallback;
                }
                rememberTemporarilyBlocked(playerId, fallbackApproach, fallback.reason());
                yield pickBetterFailure(bestFailure, fallback);
            }
            case AUTO -> {
                for (int i = 0; i < walkAttempts; i++) {
                    ApproachCandidate approach = approaches.get(i);
                    MoveResult walk = moveWalk(world, player, approach.x(), approach.y(), approach.z(), targetX, targetY, targetZ, reach);
                    if (walk.status() >= 0) {
                        yield walk;
                    }
                    rememberTemporarilyBlocked(playerId, approach, walk.reason());
                    bestFailure = pickBetterFailure(bestFailure, walk);
                }
                for (int i = 0; i < teleportAttempts; i++) {
                    ApproachCandidate approach = approaches.get(i);
                    MoveResult tp = moveTeleport(
                        player,
                        approach.x(),
                        approach.y(),
                        approach.z(),
                        targetX,
                        targetY,
                        targetZ,
                        reach,
                        "auto_fallback_teleport#" + (i + 1)
                    );
                    if (tp.status() >= 0) {
                        yield tp;
                    }
                    rememberTemporarilyBlocked(playerId, approach, tp.reason());
                    bestFailure = pickBetterFailure(bestFailure, tp);
                }
                yield bestFailure == null ? MoveResult.failed("auto_failed", dist) : bestFailure;
            }
        };
    }

    private static MoveResult moveTeleport(
        ServerPlayerEntity player,
        double approachX,
        double approachY,
        double approachZ,
        double targetX,
        double targetY,
        double targetZ,
        double reach,
        String modeTag
    ) {
        player.requestTeleport(approachX, approachY, approachZ);
        double afterDist = Math.sqrt(player.squaredDistanceTo(targetX, targetY, targetZ));
        if (afterDist <= reach + 0.5) {
            return MoveResult.moved(modeTag, 1, afterDist);
        }
        return MoveResult.failed(modeTag + "_out_of_range", afterDist);
    }

    private static MoveResult moveWalk(
        ServerWorld world,
        ServerPlayerEntity player,
        double approachX,
        double approachY,
        double approachZ,
        double targetX,
        double targetY,
        double targetZ,
        double reach
    ) {
        double distNow = Math.sqrt(player.squaredDistanceTo(targetX, targetY, targetZ));
        if (distNow <= reach + 0.1) {
            return MoveResult.alreadyInRange(distNow);
        }

        UUID playerId = player.getUuid();
        BlockPos start = BlockPos.ofFloored(player.getX(), player.getY(), player.getZ());
        BlockPos goal = BlockPos.ofFloored(approachX, approachY, approachZ);

        // Incremental replanning: reuse cached plan for same goal before doing full A* again.
        CachedPathPlan cached = getUsableCachedPlan(playerId, start, goal);
        if (cached != null) {
            MoveResult fromCache = followPathPlan(world, player, playerId, cached, targetX, targetY, targetZ, reach, "walk_cache");
            if (fromCache.status() >= 0) {
                return fromCache;
            }
            PATH_PLANS.remove(playerId);
        }

        List<BlockPos> path = findPathWithFallback(world, playerId, start, goal);
        if (path.isEmpty()) {
            double horizontalToGoal = Math.sqrt(square(goal.getX() - start.getX()) + square(goal.getZ() - start.getZ()));
            int adaptiveRadius = Math.max(18, Math.min(64, (int) Math.ceil(horizontalToGoal * 1.8) + 10));
            int verticalWindow = Math.max(4, Math.min(14, Math.abs(goal.getY() - start.getY()) + 5));
            int widerRadius = Math.min(96, adaptiveRadius + 14);
            int widerVertical = Math.min(20, verticalWindow + 4);
            BlockPos fallback = greedyFallbackStep(world, start, goal, widerRadius, widerVertical);
            if (fallback == null) {
                rememberTemporarilyBlocked(playerId, goal.getX(), goal.getY(), goal.getZ(), "walk_no_path");
                return MoveResult.failed("walk_no_path", distNow);
            }
            double beforeDist = Math.sqrt(player.squaredDistanceTo(targetX, targetY, targetZ));
            player.requestTeleport(fallback.getX() + 0.5, fallback.getY(), fallback.getZ() + 0.5);
            double afterDist = Math.sqrt(player.squaredDistanceTo(targetX, targetY, targetZ));
            if (afterDist > beforeDist + 0.35) {
                rememberTemporarilyBlocked(playerId, fallback.getX(), fallback.getY(), fallback.getZ(), "walk_greedy_rejected");
                return MoveResult.failed("walk_greedy_rejected", afterDist);
            }
            return MoveResult.moved("walk_greedy_step", 1, afterDist);
        }

        CachedPathPlan freshPlan = new CachedPathPlan(goal, path, System.currentTimeMillis());
        PATH_PLANS.put(playerId, freshPlan);
        MoveResult fromFresh = followPathPlan(world, player, playerId, freshPlan, targetX, targetY, targetZ, reach, "walk_path");
        if (fromFresh.status() < 0) {
            PATH_PLANS.remove(playerId);
        }
        return fromFresh;
    }

    private static int computeExpandedBudget(BlockPos start, BlockPos goal, int maxRadius, int maxVerticalOffset) {
        int dx = Math.abs(goal.getX() - start.getX());
        int dz = Math.abs(goal.getZ() - start.getZ());
        int dy = Math.abs(goal.getY() - start.getY());
        int taxi = dx + dz;
        int budget = BASE_MAX_PATH_EXPANDED
            + taxi * 36
            + dy * 120
            + maxRadius * 14
            + maxVerticalOffset * 85;
        return Math.min(HARD_MAX_PATH_EXPANDED, Math.max(BASE_MAX_PATH_EXPANDED, budget));
    }

    private static List<BlockPos> findPathWithFallback(
        ServerWorld world,
        UUID playerId,
        BlockPos start,
        BlockPos goal
    ) {
        double horizontalToGoal = Math.sqrt(square(goal.getX() - start.getX()) + square(goal.getZ() - start.getZ()));
        int adaptiveRadius = Math.max(18, Math.min(64, (int) Math.ceil(horizontalToGoal * 1.8) + 10));
        int verticalWindow = Math.max(4, Math.min(14, Math.abs(goal.getY() - start.getY()) + 5));
        int maxExpanded = computeExpandedBudget(start, goal, adaptiveRadius, verticalWindow);

        List<BlockPos> path = findPath(world, playerId, start, goal, adaptiveRadius, verticalWindow, maxExpanded);
        if (!path.isEmpty()) {
            return path;
        }

        int widerRadius = Math.min(96, adaptiveRadius + 14);
        int widerVertical = Math.min(20, verticalWindow + 4);
        int widerBudget = Math.min(HARD_MAX_PATH_EXPANDED, maxExpanded + 2800);
        return findPath(world, playerId, start, goal, widerRadius, widerVertical, widerBudget);
    }

    private static CachedPathPlan getUsableCachedPlan(UUID playerId, BlockPos start, BlockPos goal) {
        if (playerId == null) {
            return null;
        }
        if (!pruneCachedPlan(playerId)) {
            return null;
        }
        CachedPathPlan plan = PATH_PLANS.get(playerId);
        if (plan == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (!plan.goal().equals(goal) || plan.path().isEmpty()) {
            return null;
        }

        int bestIndex = nearestIndex(plan, start, 10);
        if (bestIndex < 0) {
            return null;
        }
        plan.cursor(bestIndex);
        plan.lastUsedAt(now);
        return plan;
    }

    private static boolean pruneCachedPlan(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        CachedPathPlan plan = PATH_PLANS.get(playerId);
        if (plan == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - plan.createdAt() > PATH_CACHE_TTL_MS && now - plan.lastUsedAt() > PATH_CACHE_TTL_MS) {
            PATH_PLANS.remove(playerId);
            return false;
        }
        if (plan.path().isEmpty() || plan.cursor() >= plan.path().size()) {
            PATH_PLANS.remove(playerId);
            return false;
        }
        return true;
    }

    private static int nearestIndex(CachedPathPlan plan, BlockPos start, int scanWindow) {
        if (plan == null || plan.path().isEmpty()) {
            return -1;
        }
        int from = Math.max(0, plan.cursor() - 2);
        int to = Math.min(plan.path().size() - 1, plan.cursor() + scanWindow);
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = from; i <= to; i++) {
            BlockPos node = plan.path().get(i);
            double dist = start.getSquaredDistance(node);
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return bestDist <= 25.0 ? best : -1;
    }

    private static MoveResult followPathPlan(
        ServerWorld world,
        ServerPlayerEntity player,
        UUID playerId,
        CachedPathPlan plan,
        double targetX,
        double targetY,
        double targetZ,
        double reach,
        String tag
    ) {
        if (plan == null || plan.path().isEmpty()) {
            return MoveResult.failed(tag + "_empty", Math.sqrt(player.squaredDistanceTo(targetX, targetY, targetZ)));
        }

        int movedSteps = 0;
        int remaining = Math.max(0, plan.path().size() - plan.cursor());
        int maxTeleportSteps = Math.min(remaining, Math.max(8, Math.min(14, (int) Math.ceil(plan.path().size() * 0.45))));

        for (int i = 0; i < maxTeleportSteps; i++) {
            int idx = plan.cursor() + i;
            if (idx >= plan.path().size()) {
                break;
            }
            BlockPos step = plan.path().get(idx);
            if (!canStandAtBlock(world, step.getX(), step.getY(), step.getZ())) {
                double distAfterAbort = Math.sqrt(player.squaredDistanceTo(targetX, targetY, targetZ));
                rememberTemporarilyBlocked(playerId, step.getX(), step.getY(), step.getZ(), tag + "_step_not_standable");
                if (movedSteps > 0) {
                    plan.cursor(idx);
                    return MoveResult.moved(tag + "_partial_blocked", movedSteps, distAfterAbort);
                }
                return MoveResult.failed(tag + "_step_not_standable", distAfterAbort);
            }

            player.requestTeleport(step.getX() + 0.5, step.getY(), step.getZ() + 0.5);
            movedSteps++;
            plan.cursor(idx + 1);

            double dist = Math.sqrt(player.squaredDistanceTo(targetX, targetY, targetZ));
            if (dist <= reach + 0.1) {
                PATH_PLANS.remove(playerId);
                return MoveResult.moved(tag, movedSteps, dist);
            }
        }

        double distAfter = Math.sqrt(player.squaredDistanceTo(targetX, targetY, targetZ));
        if (movedSteps > 0) {
            return MoveResult.moved(tag + "_partial", movedSteps, distAfter);
        }
        rememberTemporarilyBlocked(playerId, plan.goal().getX(), plan.goal().getY(), plan.goal().getZ(), tag + "_no_progress");
        return MoveResult.failed(tag + "_no_progress", distAfter);
    }

    private static BlockPos greedyFallbackStep(
        ServerWorld world,
        BlockPos start,
        BlockPos goal,
        int maxRadius,
        int maxVerticalOffset
    ) {
        PathKey startKey = new PathKey(start.getX(), start.getY(), start.getZ());
        PathKey goalKey = new PathKey(goal.getX(), goal.getY(), goal.getZ());
        List<PathEdge> options = neighbors(world, null, startKey, startKey, maxRadius, maxVerticalOffset);
        if (options.isEmpty()) {
            return null;
        }

        double baseline = heuristic(startKey, goalKey);
        PathKey best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (PathEdge optionEdge : options) {
            PathKey option = optionEdge.key();
            double h = heuristic(option, goalKey);
            if (h >= baseline + 0.8) {
                continue;
            }
            double score = h + Math.abs(option.y - startKey.y) * 0.45 + optionEdge.actionCost() * 0.35;
            if (score < bestScore) {
                bestScore = score;
                best = option;
            }
        }
        if (best == null) {
            return null;
        }
        return new BlockPos(best.x, best.y, best.z);
    }

    private static List<BlockPos> findPath(
        ServerWorld world,
        UUID playerId,
        BlockPos start,
        BlockPos goal,
        int maxRadius,
        int maxVerticalOffset,
        int maxExpanded
    ) {
        if (start.equals(goal)) {
            return List.of();
        }

        PriorityQueue<PathNode> open = new PriorityQueue<>();
        Map<PathKey, PathKey> cameFrom = new HashMap<>();
        Map<PathKey, Double> gScore = new HashMap<>();

        PathKey startKey = new PathKey(start.getX(), start.getY(), start.getZ());
        PathKey goalKey = new PathKey(goal.getX(), goal.getY(), goal.getZ());
        double startHeuristic = heuristic(startKey, goalKey);
        PathNode startNode = new PathNode(startKey, startHeuristic, 0.0);
        open.add(startNode);
        gScore.put(startKey, 0.0);
        PathKey bestSoFar = startKey;
        double bestHeuristic = startHeuristic;

        int expanded = 0;
        while (!open.isEmpty() && expanded < maxExpanded) {
            PathNode current = open.poll();
            expanded++;

            PathKey currentKey = current.key();
            if (current.g > gScore.getOrDefault(currentKey, Double.POSITIVE_INFINITY)) {
                continue;
            }

            double currentHeuristic = heuristic(currentKey, goalKey);
            if (currentHeuristic < bestHeuristic) {
                bestHeuristic = currentHeuristic;
                bestSoFar = currentKey;
            }

            if (currentKey.equals(goalKey) || currentHeuristic <= 1.25) {
                return reconstructPath(cameFrom, currentKey);
            }

            List<PathEdge> neighbors = neighbors(world, playerId, currentKey, startKey, maxRadius, maxVerticalOffset);
            for (PathEdge neighbor : neighbors) {
                PathKey neighborKey = neighbor.key();
                double tentativeG = current.g + stepCost(currentKey, neighbor);
                if (tentativeG >= gScore.getOrDefault(neighborKey, Double.POSITIVE_INFINITY)) {
                    continue;
                }

                cameFrom.put(neighborKey, currentKey);
                gScore.put(neighborKey, tentativeG);
                double h = heuristic(neighborKey, goalKey);
                open.add(new PathNode(neighborKey, tentativeG + h, tentativeG));
            }
        }
        if (!bestSoFar.equals(startKey)) {
            return reconstructPath(cameFrom, bestSoFar);
        }
        return List.of();
    }

    private static List<PathEdge> neighbors(
        ServerWorld world,
        UUID playerId,
        PathKey current,
        PathKey start,
        int maxRadius,
        int maxVerticalOffset
    ) {
        List<PathEdge> out = new ArrayList<>(28);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }

                int nx = current.x + dx;
                int nz = current.z + dz;
                if (Math.abs(nx - start.x) > maxRadius || Math.abs(nz - start.z) > maxRadius) {
                    continue;
                }

                for (int ny = current.y - 2; ny <= current.y + 2; ny++) {
                    if (Math.abs(ny - start.y) > maxVerticalOffset) {
                        continue;
                    }
                    if (isTemporarilyBlocked(playerId, nx, ny, nz)) {
                        continue;
                    }
                    if (!canStandAtBlock(world, nx, ny, nz)) {
                        continue;
                    }

                    if (dx != 0 && dz != 0) {
                        boolean sideA = canStandAtBlock(world, current.x + dx, ny, current.z);
                        boolean sideB = canStandAtBlock(world, current.x, ny, current.z + dz);
                        if (!sideA && !sideB) {
                            continue;
                        }
                    }
                    MoveAction action = classifyAction(dx, dz, ny - current.y);
                    double edgeCost = action.tickCost() + hazardPenalty(world, nx, ny, nz, action);
                    out.add(new PathEdge(new PathKey(nx, ny, nz), edgeCost, action));
                }
            }
        }
        return out;
    }

    private static List<BlockPos> reconstructPath(Map<PathKey, PathKey> cameFrom, PathKey end) {
        List<BlockPos> reversed = new ArrayList<>();
        PathKey current = end;
        reversed.add(new BlockPos(current.x, current.y, current.z));
        while (cameFrom.containsKey(current)) {
            current = cameFrom.get(current);
            reversed.add(new BlockPos(current.x, current.y, current.z));
        }
        List<BlockPos> out = new ArrayList<>(Math.max(0, reversed.size() - 1));
        for (int i = reversed.size() - 2; i >= 0; i--) {
            out.add(reversed.get(i));
        }
        return out;
    }

    private static double stepCost(PathKey from, PathEdge to) {
        PathKey next = to.key();
        boolean diagonal = from.x != next.x && from.z != next.z;
        double horizontal = diagonal ? 1.41 : 1.0;
        double dy = Math.abs(next.y - from.y);
        double vertical = dy * 0.35 + (dy > 1.0 ? dy * 0.8 : 0.0);
        return horizontal + vertical + to.actionCost();
    }

    private static double heuristic(PathKey from, PathKey goal) {
        double dx = goal.x - from.x;
        double dy = (goal.y - from.y) * 0.8;
        double dz = goal.z - from.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private record PathNode(PathKey key, double f, double g) implements Comparable<PathNode> {
        @Override
        public int compareTo(PathNode o) {
            return Double.compare(this.f, o.f);
        }
    }

    private record PathKey(int x, int y, int z) {
    }

    private static List<ApproachCandidate> selectApproachCandidates(
        ServerWorld world,
        ServerPlayerEntity player,
        double targetX,
        double targetY,
        double targetZ,
        double reach
    ) {
        int playerFeetY = (int) Math.floor(player.getY());
        int targetFeetY = (int) Math.floor(targetY);
        Map<ApproachKey, ApproachCandidate> bestByPos = new HashMap<>();

        for (double radius = 1.2; radius <= Math.min(reach + 1.8, 8.2); radius += 0.5) {
            int samples = Math.max(8, (int) Math.ceil(radius * 7.0));
            for (int i = 0; i < samples; i++) {
                double angle = (Math.PI * 2.0 * i) / samples;
                int blockX = (int) Math.floor(targetX + Math.cos(angle) * radius);
                int blockZ = (int) Math.floor(targetZ + Math.sin(angle) * radius);

                for (int feetY = targetFeetY - 3; feetY <= targetFeetY + 3; feetY++) {
                    upsertCandidate(bestByPos, scoreApproachCandidate(
                        world, player, blockX, feetY, blockZ, targetX, targetY, targetZ, reach, radius
                    ));
                }

                if (Math.abs(playerFeetY - targetFeetY) > 2) {
                    for (int feetY = playerFeetY - 2; feetY <= playerFeetY + 2; feetY++) {
                        upsertCandidate(bestByPos, scoreApproachCandidate(
                            world, player, blockX, feetY, blockZ, targetX, targetY, targetZ, reach, radius
                        ));
                    }
                }
            }
        }

        if (bestByPos.isEmpty()) {
            // Coarse-grid fallback handles odd terrain where ring samples miss valid stand spots.
            coarseApproachSearch(world, player, targetX, targetY, targetZ, reach, playerFeetY, targetFeetY, bestByPos);
        }

        if (Math.sqrt(player.squaredDistanceTo(targetX, targetY, targetZ)) <= reach + 0.25) {
            upsertCandidate(bestByPos, new ApproachCandidate(player.getX(), player.getY(), player.getZ(), 0.0));
        }

        if (bestByPos.isEmpty()) {
            return List.of();
        }

        List<ApproachCandidate> out = new ArrayList<>(bestByPos.values());
        out.sort((a, b) -> Double.compare(a.score(), b.score()));
        UUID playerId = player.getUuid();
        List<ApproachCandidate> filtered = new ArrayList<>(out.size());
        for (ApproachCandidate candidate : out) {
            if (isTemporarilyBlocked(playerId, candidate)) {
                continue;
            }
            filtered.add(candidate);
        }
        if (filtered.isEmpty()) {
            filtered = out;
        }

        if (filtered.size() <= 12) {
            return filtered;
        }
        return new ArrayList<>(filtered.subList(0, 12));
    }

    private static void coarseApproachSearch(
        ServerWorld world,
        ServerPlayerEntity player,
        double targetX,
        double targetY,
        double targetZ,
        double reach,
        int playerFeetY,
        int targetFeetY,
        Map<ApproachKey, ApproachCandidate> bestByPos
    ) {
        int maxRange = Math.max(3, Math.min(10, (int) Math.ceil(reach + 2.5)));

        for (int dx = -maxRange; dx <= maxRange; dx++) {
            for (int dz = -maxRange; dz <= maxRange; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int blockX = (int) Math.floor(targetX) + dx;
                int blockZ = (int) Math.floor(targetZ) + dz;
                double horizontal = Math.sqrt(square(dx) + square(dz));
                if (horizontal > reach + 2.0) {
                    continue;
                }

                int minY = Math.min(playerFeetY, targetFeetY) - 3;
                int maxY = Math.max(playerFeetY, targetFeetY) + 3;
                for (int feetY = minY; feetY <= maxY; feetY++) {
                    upsertCandidate(bestByPos, scoreApproachCandidate(
                        world, player, blockX, feetY, blockZ, targetX, targetY, targetZ, reach, horizontal
                    ));
                }
            }
        }
    }

    private static ApproachCandidate scoreApproachCandidate(
        ServerWorld world,
        ServerPlayerEntity player,
        int blockX,
        int feetY,
        int blockZ,
        double targetX,
        double targetY,
        double targetZ,
        double reach,
        double radius
    ) {
        if (!canStandAtBlock(world, blockX, feetY, blockZ)) {
            return null;
        }
        double standX = blockX + 0.5;
        double standY = feetY;
        double standZ = blockZ + 0.5;

        double distTarget = Math.sqrt(square(standX - targetX) + square(standY + 1.0 - targetY) + square(standZ - targetZ));
        if (distTarget > reach + 0.35) {
            return null;
        }

        double distPlayer = Math.sqrt(player.squaredDistanceTo(standX, standY, standZ));
        double score = distPlayer
            + distTarget * 0.45
            + Math.abs(radius - (reach - 0.35)) * 0.05
            + Math.abs(standY - player.getY()) * 0.18;
        return new ApproachCandidate(standX, standY, standZ, score);
    }

    private record ApproachCandidate(double x, double y, double z, double score) {
    }

    private record ApproachKey(int x, int y, int z) {
    }

    private record PathEdge(PathKey key, double actionCost, MoveAction action) {
    }

    private enum MoveAction {
        WALK(1.0),
        DIAGONAL(1.25),
        STEP_UP(1.6),
        STEP_DOWN(1.05),
        JUMP_CLIMB(2.6),
        DROP(2.2);

        private final double tickCost;

        MoveAction(double tickCost) {
            this.tickCost = tickCost;
        }

        public double tickCost() {
            return tickCost;
        }
    }

    private static final class CachedPathPlan {
        private final BlockPos goal;
        private final List<BlockPos> path;
        private final long createdAt;
        private int cursor;
        private long lastUsedAt;

        private CachedPathPlan(BlockPos goal, List<BlockPos> path, long createdAt) {
            this.goal = goal;
            this.path = path;
            this.createdAt = createdAt;
            this.lastUsedAt = createdAt;
            this.cursor = 0;
        }

        public BlockPos goal() {
            return goal;
        }

        public List<BlockPos> path() {
            return path;
        }

        public long createdAt() {
            return createdAt;
        }

        public int cursor() {
            return cursor;
        }

        public void cursor(int cursor) {
            this.cursor = Math.max(0, Math.min(path.size(), cursor));
        }

        public long lastUsedAt() {
            return lastUsedAt;
        }

        public void lastUsedAt(long lastUsedAt) {
            this.lastUsedAt = lastUsedAt;
        }
    }

    private static void upsertCandidate(Map<ApproachKey, ApproachCandidate> bestByPos, ApproachCandidate candidate) {
        if (bestByPos == null || candidate == null) {
            return;
        }
        ApproachKey key = new ApproachKey(
            (int) Math.floor(candidate.x()),
            (int) Math.floor(candidate.y()),
            (int) Math.floor(candidate.z())
        );
        ApproachCandidate existing = bestByPos.get(key);
        if (existing == null || candidate.score() < existing.score()) {
            bestByPos.put(key, candidate);
        }
    }

    private static MoveAction classifyAction(int dx, int dz, int dy) {
        if (dy >= 2) {
            return MoveAction.JUMP_CLIMB;
        }
        if (dy == 1) {
            return MoveAction.STEP_UP;
        }
        if (dy <= -2) {
            return MoveAction.DROP;
        }
        if (dy == -1) {
            return MoveAction.STEP_DOWN;
        }
        if (dx != 0 && dz != 0) {
            return MoveAction.DIAGONAL;
        }
        return MoveAction.WALK;
    }

    private static double hazardPenalty(ServerWorld world, int x, int y, int z, MoveAction action) {
        BlockPos feet = new BlockPos(x, y, z);
        BlockPos head = feet.up();
        BlockPos below = feet.down();

        double penalty = 0.0;
        penalty += blockDangerPenalty(world.getBlockState(feet)) * 1.35;
        penalty += blockDangerPenalty(world.getBlockState(head)) * 0.35;
        penalty += blockDangerPenalty(world.getBlockState(below)) * 1.0;
        if (action == MoveAction.DROP) {
            penalty += 0.45;
        }
        if (action == MoveAction.JUMP_CLIMB) {
            penalty += 0.35;
        }
        return penalty;
    }

    private static double blockDangerPenalty(BlockState state) {
        if (state.isAir()) {
            return 0.0;
        }
        if (state.isOf(Blocks.LAVA) || state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)) {
            return 9.0;
        }
        if (state.isOf(Blocks.CACTUS) || state.isOf(Blocks.MAGMA_BLOCK) || state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE)) {
            return 5.0;
        }
        if (state.isOf(Blocks.SWEET_BERRY_BUSH) || state.isOf(Blocks.POWDER_SNOW)) {
            return 3.5;
        }
        if (state.isOf(Blocks.WATER)) {
            return 1.4;
        }
        return 0.0;
    }

    private static boolean isTemporarilyBlocked(UUID playerId, ApproachCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        int x = (int) Math.floor(candidate.x());
        int y = (int) Math.floor(candidate.y());
        int z = (int) Math.floor(candidate.z());
        return isTemporarilyBlocked(playerId, x, y, z);
    }

    private static boolean isTemporarilyBlocked(UUID playerId, int x, int y, int z) {
        if (playerId == null) {
            return false;
        }
        Map<PathKey, Long> blocked = TEMP_BLOCKED.get(playerId);
        if (blocked == null || blocked.isEmpty()) {
            return false;
        }
        pruneExpired(blocked);
        Long expiresAt = blocked.get(new PathKey(x, y, z));
        if (expiresAt == null) {
            return false;
        }
        boolean blockedNow = expiresAt > System.currentTimeMillis();
        if (blockedNow) {
            BLACKLIST_HITS.merge(playerId, 1, Integer::sum);
        }
        return blockedNow;
    }

    private static void rememberTemporarilyBlocked(UUID playerId, ApproachCandidate candidate, String reason) {
        if (playerId == null || candidate == null) {
            return;
        }
        rememberTemporarilyBlocked(
            playerId,
            (int) Math.floor(candidate.x()),
            (int) Math.floor(candidate.y()),
            (int) Math.floor(candidate.z()),
            reason
        );
    }

    private static void rememberTemporarilyBlocked(UUID playerId, int x, int y, int z, String reason) {
        rememberTemporarilyBlocked(playerId, x, y, z, reason, null);
    }

    private static void rememberTemporarilyBlocked(UUID playerId, int x, int y, int z, String reason, Long fixedDurationMs) {
        if (playerId == null) {
            return;
        }
        String normalized = reason == null ? "" : reason;
        if (normalized.contains("already_in_range")) {
            return;
        }
        long duration;
        if (fixedDurationMs != null && fixedDurationMs > 0L) {
            duration = fixedDurationMs;
        } else {
            duration = normalized.contains("no_path")
                || normalized.contains("no_progress")
                || normalized.contains("no_approach")
                ? TEMP_BLOCK_MS_HARD
                : TEMP_BLOCK_MS_SOFT;
        }

        Map<PathKey, Long> blocked = TEMP_BLOCKED.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
        pruneExpired(blocked);
        if (blocked.size() > MAX_TEMP_BLOCKED_PER_PLAYER) {
            trimOldest(blocked, blocked.size() - MAX_TEMP_BLOCKED_PER_PLAYER);
        }
        blocked.put(new PathKey(x, y, z), System.currentTimeMillis() + duration);
    }

    private static void pruneExpired(Map<PathKey, Long> blocked) {
        if (blocked == null || blocked.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<PathKey, Long>> it = blocked.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<PathKey, Long> next = it.next();
            Long expiresAt = next.getValue();
            if (expiresAt == null || expiresAt <= now) {
                it.remove();
            }
        }
    }

    private static void trimOldest(Map<PathKey, Long> blocked, int removeCount) {
        if (removeCount <= 0 || blocked == null || blocked.isEmpty()) {
            return;
        }
        for (int i = 0; i < removeCount; i++) {
            PathKey oldestKey = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<PathKey, Long> entry : blocked.entrySet()) {
                long value = entry.getValue() == null ? Long.MIN_VALUE : entry.getValue();
                if (value < oldest) {
                    oldest = value;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey == null) {
                return;
            }
            blocked.remove(oldestKey);
        }
    }

    private static double square(double v) {
        return v * v;
    }

    private static boolean canStandAtBlock(ServerWorld world, int x, int y, int z) {
        if (y <= world.getBottomY() || y + 1 > world.getTopYInclusive()) {
            return false;
        }
        BlockPos feet = new BlockPos(x, y, z);
        BlockPos head = feet.up();
        BlockPos below = feet.down();
        boolean feetFree = world.getBlockState(feet).getCollisionShape(world, feet).isEmpty();
        boolean headFree = world.getBlockState(head).getCollisionShape(world, head).isEmpty();
        boolean supported = !world.getBlockState(below).getCollisionShape(world, below).isEmpty();
        return feetFree && headFree && supported;
    }

    public record MoveResult(int status, String reason, int movedSteps, double finalDistance) {
        public static MoveResult alreadyInRange(double finalDistance) {
            return new MoveResult(0, "already_in_range", 0, finalDistance);
        }

        public static MoveResult moved(String reason, int movedSteps, double finalDistance) {
            return new MoveResult(1, reason == null ? "moved" : reason, Math.max(0, movedSteps), finalDistance);
        }

        public static MoveResult failed(String reason, double finalDistance) {
            return new MoveResult(-1, reason == null ? "failed" : reason, 0, finalDistance);
        }
    }

    private static MoveResult pickBetterFailure(MoveResult current, MoveResult candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        if (candidate.finalDistance() + 0.05 < current.finalDistance()) {
            return candidate;
        }
        if (Math.abs(candidate.finalDistance() - current.finalDistance()) <= 0.05
            && candidate.reason().length() < current.reason().length()) {
            return candidate;
        }
        return current;
    }
}
