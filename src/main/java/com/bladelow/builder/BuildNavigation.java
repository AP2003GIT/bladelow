package com.bladelow.builder;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public final class BuildNavigation {
    private static final int MAX_PATH_EXPANDED = 3200;

    private BuildNavigation() {
    }

    public static int ensureInRangeForPlacement(ServerWorld world, ServerPlayerEntity player, BlockPos target) {
        double targetX = target.getX() + 0.5;
        double targetY = target.getY() + 0.5;
        double targetZ = target.getZ() + 0.5;

        double reach = BuildRuntimeSettings.reachDistance();
        double dist = Math.sqrt(player.squaredDistanceTo(targetX, targetY, targetZ));
        if (dist <= reach) {
            return 0;
        }

        if (!BuildRuntimeSettings.smartMoveEnabled()) {
            return -1;
        }

        double[] approach = selectApproachPoint(world, player, targetX, targetY, targetZ, reach);
        if (approach == null) {
            return -1;
        }

        return switch (BuildRuntimeSettings.moveMode()) {
            case TELEPORT -> moveTeleport(player, approach[0], approach[1], approach[2], targetX, targetY, targetZ, reach);
            case WALK -> moveWalk(world, player, approach[0], approach[1], approach[2], targetX, targetY, targetZ, reach);
            case AUTO -> {
                int walk = moveWalk(world, player, approach[0], approach[1], approach[2], targetX, targetY, targetZ, reach);
                if (walk >= 0) {
                    yield walk;
                }
                yield moveTeleport(player, approach[0], approach[1], approach[2], targetX, targetY, targetZ, reach);
            }
        };
    }

    private static int moveTeleport(
        ServerPlayerEntity player,
        double approachX,
        double approachY,
        double approachZ,
        double targetX,
        double targetY,
        double targetZ,
        double reach
    ) {
        player.requestTeleport(approachX, approachY, approachZ);
        double afterDist = Math.sqrt(player.squaredDistanceTo(targetX, targetY, targetZ));
        return afterDist <= reach + 0.5 ? 1 : -1;
    }

    private static int moveWalk(
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
            return 0;
        }

        BlockPos start = BlockPos.ofFloored(player.getX(), player.getY(), player.getZ());
        BlockPos goal = BlockPos.ofFloored(approachX, approachY, approachZ);

        double horizontalToGoal = Math.sqrt(square(goal.getX() - start.getX()) + square(goal.getZ() - start.getZ()));
        int adaptiveRadius = Math.max(18, Math.min(64, (int) Math.ceil(horizontalToGoal * 1.8) + 10));
        int verticalWindow = Math.max(4, Math.min(14, Math.abs(goal.getY() - start.getY()) + 5));
        List<BlockPos> path = findPath(world, start, goal, adaptiveRadius, verticalWindow);
        if (path.isEmpty()) {
            return -1;
        }

        int movedSteps = 0;
        int maxTeleportSteps = Math.min(8, path.size());
        for (int i = 0; i < maxTeleportSteps; i++) {
            BlockPos step = path.get(i);
            double stepX = step.getX() + 0.5;
            double stepY = step.getY();
            double stepZ = step.getZ() + 0.5;
            if (!canStandAtBlock(world, step.getX(), step.getY(), step.getZ())) {
                return movedSteps > 0 ? 1 : -1;
            }
            player.requestTeleport(stepX, stepY, stepZ);
            movedSteps++;
            double dist = Math.sqrt(player.squaredDistanceTo(targetX, targetY, targetZ));
            if (dist <= reach + 0.1) {
                return 1;
            }
        }

        return movedSteps > 0 ? 1 : -1;
    }

    private static List<BlockPos> findPath(ServerWorld world, BlockPos start, BlockPos goal, int maxRadius, int maxVerticalOffset) {
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

        int expanded = 0;
        while (!open.isEmpty() && expanded < MAX_PATH_EXPANDED) {
            PathNode current = open.poll();
            expanded++;

            PathKey currentKey = current.key();
            if (current.g > gScore.getOrDefault(currentKey, Double.POSITIVE_INFINITY)) {
                continue;
            }

            if (currentKey.equals(goalKey) || heuristic(currentKey, goalKey) <= 1.25) {
                return reconstructPath(cameFrom, currentKey);
            }

            List<PathKey> neighbors = neighbors(world, currentKey, startKey, maxRadius, maxVerticalOffset);
            for (PathKey neighborKey : neighbors) {
                double tentativeG = current.g + stepCost(currentKey, neighborKey);
                if (tentativeG >= gScore.getOrDefault(neighborKey, Double.POSITIVE_INFINITY)) {
                    continue;
                }

                cameFrom.put(neighborKey, currentKey);
                gScore.put(neighborKey, tentativeG);
                double h = heuristic(neighborKey, goalKey);
                open.add(new PathNode(neighborKey, tentativeG + h, tentativeG));
            }
        }
        return List.of();
    }

    private static List<PathKey> neighbors(
        ServerWorld world,
        PathKey current,
        PathKey start,
        int maxRadius,
        int maxVerticalOffset
    ) {
        List<PathKey> out = new ArrayList<>(18);
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

                for (int ny = current.y - 1; ny <= current.y + 1; ny++) {
                    if (Math.abs(ny - start.y) > maxVerticalOffset) {
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
                    out.add(new PathKey(nx, ny, nz));
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

    private static double stepCost(PathKey from, PathKey to) {
        boolean diagonal = from.x != to.x && from.z != to.z;
        double horizontal = diagonal ? 1.41 : 1.0;
        double vertical = Math.abs(to.y - from.y) * 0.35;
        return horizontal + vertical;
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

    private static double[] selectApproachPoint(
        ServerWorld world,
        ServerPlayerEntity player,
        double targetX,
        double targetY,
        double targetZ,
        double reach
    ) {
        int playerFeetY = (int) Math.floor(player.getY());
        int targetFeetY = (int) Math.floor(targetY);
        double bestScore = Double.MAX_VALUE;
        double[] best = null;

        for (double radius = 1.4; radius <= Math.min(reach + 0.8, 6.4); radius += 0.55) {
            int samples = Math.max(8, (int) Math.ceil(radius * 7.0));
            for (int i = 0; i < samples; i++) {
                double angle = (Math.PI * 2.0 * i) / samples;
                int blockX = (int) Math.floor(targetX + Math.cos(angle) * radius);
                int blockZ = (int) Math.floor(targetZ + Math.sin(angle) * radius);

                for (int feetY = targetFeetY - 2; feetY <= targetFeetY + 2; feetY++) {
                    ApproachCandidate candidate = scoreApproachCandidate(
                        world, player, blockX, feetY, blockZ, targetX, targetY, targetZ, reach, radius
                    );
                    if (candidate != null && candidate.score < bestScore) {
                        bestScore = candidate.score;
                        best = new double[] {candidate.x, candidate.y, candidate.z};
                    }
                }

                if (playerFeetY < targetFeetY - 2 || playerFeetY > targetFeetY + 2) {
                    ApproachCandidate candidate = scoreApproachCandidate(
                        world, player, blockX, playerFeetY, blockZ, targetX, targetY, targetZ, reach, radius
                    );
                    if (candidate != null && candidate.score < bestScore) {
                        bestScore = candidate.score;
                        best = new double[] {candidate.x, candidate.y, candidate.z};
                    }
                }
            }
        }

        if (best != null) {
            return best;
        }

        if (Math.sqrt(player.squaredDistanceTo(targetX, targetY, targetZ)) <= reach + 0.25) {
            return new double[] {player.getX(), player.getY(), player.getZ()};
        }
        return null;
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
}
