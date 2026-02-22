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

        int y = (int) Math.floor(player.getY());
        BlockPos start = BlockPos.ofFloored(player.getX(), y, player.getZ());
        BlockPos goal = BlockPos.ofFloored(approachX, y, approachZ);

        List<BlockPos> path = findPath(world, start, goal, y, 28);
        if (path.isEmpty()) {
            return -1;
        }

        int movedSteps = 0;
        int maxTeleportSteps = Math.min(8, path.size());
        for (int i = 0; i < maxTeleportSteps; i++) {
            BlockPos step = path.get(i);
            double stepX = step.getX() + 0.5;
            double stepZ = step.getZ() + 0.5;
            if (!canStandAt(world, stepX, player.getY(), stepZ)) {
                return movedSteps > 0 ? 1 : -1;
            }
            player.requestTeleport(stepX, player.getY(), stepZ);
            movedSteps++;
            double dist = Math.sqrt(player.squaredDistanceTo(targetX, targetY, targetZ));
            if (dist <= reach + 0.1) {
                return 1;
            }
        }

        return movedSteps > 0 ? 1 : -1;
    }

    private static List<BlockPos> findPath(ServerWorld world, BlockPos start, BlockPos goal, int y, int maxRadius) {
        if (start.equals(goal)) {
            return List.of();
        }

        PriorityQueue<PathNode> open = new PriorityQueue<>();
        Map<Long, PathNode> nodes = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        Map<Long, Double> gScore = new HashMap<>();

        long startKey = key(start.getX(), start.getZ());
        long goalKey = key(goal.getX(), goal.getZ());
        double startHeuristic = heuristic(start.getX(), start.getZ(), goal.getX(), goal.getZ());
        PathNode startNode = new PathNode(start.getX(), start.getZ(), startHeuristic, 0.0);
        open.add(startNode);
        nodes.put(startKey, startNode);
        gScore.put(startKey, 0.0);

        int expanded = 0;
        int maxExpanded = 1800;

        while (!open.isEmpty() && expanded < maxExpanded) {
            PathNode current = open.poll();
            expanded++;

            long currentKey = key(current.x, current.z);
            if (currentKey == goalKey || heuristic(current.x, current.z, goal.getX(), goal.getZ()) <= 1.0) {
                return reconstructPath(cameFrom, current, y);
            }

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }

                    int nx = current.x + dx;
                    int nz = current.z + dz;
                    if (Math.abs(nx - start.getX()) > maxRadius || Math.abs(nz - start.getZ()) > maxRadius) {
                        continue;
                    }
                    if (!canStandAt(world, nx + 0.5, y, nz + 0.5)) {
                        continue;
                    }

                    boolean diagonal = dx != 0 && dz != 0;
                    double stepCost = diagonal ? 1.41 : 1.0;
                    double tentativeG = current.g + stepCost;
                    long neighborKey = key(nx, nz);
                    if (tentativeG >= gScore.getOrDefault(neighborKey, Double.POSITIVE_INFINITY)) {
                        continue;
                    }

                    cameFrom.put(neighborKey, currentKey);
                    gScore.put(neighborKey, tentativeG);
                    double h = heuristic(nx, nz, goal.getX(), goal.getZ());
                    PathNode neighbor = new PathNode(nx, nz, tentativeG + h, tentativeG);
                    nodes.put(neighborKey, neighbor);
                    open.add(neighbor);
                }
            }
        }
        return List.of();
    }

    private static List<BlockPos> reconstructPath(Map<Long, Long> cameFrom, PathNode end, int y) {
        List<BlockPos> reversed = new ArrayList<>();
        long current = key(end.x, end.z);
        reversed.add(new BlockPos(end.x, y, end.z));
        while (cameFrom.containsKey(current)) {
            current = cameFrom.get(current);
            int x = (int) (current >> 32);
            int z = (int) current;
            reversed.add(new BlockPos(x, y, z));
        }
        List<BlockPos> out = new ArrayList<>(Math.max(0, reversed.size() - 1));
        for (int i = reversed.size() - 2; i >= 0; i--) {
            out.add(reversed.get(i));
        }
        return out;
    }

    private static double heuristic(int x, int z, int gx, int gz) {
        double dx = gx - x;
        double dz = gz - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private record PathNode(int x, int z, double f, double g) implements Comparable<PathNode> {
        @Override
        public int compareTo(PathNode o) {
            return Double.compare(this.f, o.f);
        }
    }

    private static double[] selectApproachPoint(
        ServerWorld world,
        ServerPlayerEntity player,
        double targetX,
        double targetY,
        double targetZ,
        double reach
    ) {
        double py = player.getY();
        double bestScore = Double.MAX_VALUE;
        double[] best = null;

        for (double radius = 1.4; radius <= Math.min(reach + 0.8, 6.4); radius += 0.55) {
            int samples = Math.max(8, (int) Math.ceil(radius * 7.0));
            for (int i = 0; i < samples; i++) {
                double angle = (Math.PI * 2.0 * i) / samples;
                double x = targetX + Math.cos(angle) * radius;
                double z = targetZ + Math.sin(angle) * radius;
                if (!canStandAt(world, x, py, z)) {
                    continue;
                }

                double distTarget = Math.sqrt(square(x - targetX) + square(py + 1.0 - targetY) + square(z - targetZ));
                if (distTarget > reach + 0.35) {
                    continue;
                }

                double distPlayer = Math.sqrt(player.squaredDistanceTo(x, py, z));
                double score = distPlayer + distTarget * 0.45 + Math.abs(radius - (reach - 0.35)) * 0.05;
                if (score < bestScore) {
                    bestScore = score;
                    best = new double[] {x, py, z};
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

    private static double square(double v) {
        return v * v;
    }

    private static boolean canStandAt(ServerWorld world, double x, double y, double z) {
        BlockPos feet = BlockPos.ofFloored(x, y, z);
        BlockPos head = feet.up();
        BlockPos below = feet.down();
        boolean feetFree = world.getBlockState(feet).isAir() || world.getBlockState(feet).isReplaceable();
        boolean headFree = world.getBlockState(head).isAir() || world.getBlockState(head).isReplaceable();
        boolean supported = !world.getBlockState(below).isAir();
        return feetFree && headFree && supported;
    }
}
