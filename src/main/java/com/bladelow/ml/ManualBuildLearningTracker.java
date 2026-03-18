package com.bladelow.ml;

import com.bladelow.builder.PlacementJobRunner;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight observer that logs manual player block placements into the local
 * learning dataset.
 *
 * Instead of depending on extra interaction hooks, it snapshots a small
 * neighborhood around each player and records nearby new block placements when
 * they match the block currently held by the player.
 */
public final class ManualBuildLearningTracker {
    private static final int HORIZONTAL_RADIUS = 4;
    private static final int VERTICAL_BELOW = 2;
    private static final int VERTICAL_ABOVE = 4;
    private static final int MAX_LOGGED_EVENTS_PER_TICK = 6;
    private static final int MAX_CENTER_SHIFT = 1;
    private static final double MAX_DETECTION_DISTANCE_SQ = 49.0;
    private static final Map<UUID, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    private ManualBuildLearningTracker() {
    }

    public static void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        Set<UUID> seenPlayers = new HashSet<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!(player.getEntityWorld() instanceof ServerWorld world)) {
                continue;
            }
            UUID playerId = player.getUuid();
            seenPlayers.add(playerId);

            Snapshot current = capture(world, player);
            Snapshot previous = SNAPSHOTS.put(playerId, current);
            if (previous == null) {
                continue;
            }
            if (movedTooFar(previous.center(), current.center())) {
                continue;
            }
            if (PlacementJobRunner.hasActive(playerId)) {
                continue;
            }

            detectManualPlacements(world, player, previous, current);
        }

        SNAPSHOTS.keySet().retainAll(seenPlayers);
    }

    private static Snapshot capture(ServerWorld world, ServerPlayerEntity player) {
        Map<Long, BlockState> states = new HashMap<>();
        BlockPos center = player.getBlockPos();
        for (int dx = -HORIZONTAL_RADIUS; dx <= HORIZONTAL_RADIUS; dx++) {
            for (int dz = -HORIZONTAL_RADIUS; dz <= HORIZONTAL_RADIUS; dz++) {
                for (int dy = -VERTICAL_BELOW; dy <= VERTICAL_ABOVE; dy++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    states.put(pos.asLong(), world.getBlockState(pos));
                }
            }
        }
        return new Snapshot(center, states);
    }

    private static void detectManualPlacements(
        ServerWorld world,
        ServerPlayerEntity player,
        Snapshot previous,
        Snapshot current
    ) {
        String mainHand = heldBlockId(player.getMainHandStack());
        String offHand = heldBlockId(player.getOffHandStack());
        if (mainHand.isBlank() && offHand.isBlank()) {
            return;
        }

        List<BlockPos> candidates = new ArrayList<>();
        for (Map.Entry<Long, BlockState> entry : current.states().entrySet()) {
            BlockState currentState = entry.getValue();
            BlockState previousState = previous.states().get(entry.getKey());
            if (!isLikelyManualPlacement(currentState, previousState)) {
                continue;
            }
            String currentBlockId = blockId(currentState);
            if (!currentBlockId.equals(mainHand) && !currentBlockId.equals(offHand)) {
                continue;
            }
            BlockPos pos = BlockPos.fromLong(entry.getKey());
            if (player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > MAX_DETECTION_DISTANCE_SQ) {
                continue;
            }
            candidates.add(pos);
        }

        candidates.sort(Comparator.comparingDouble(pos ->
            player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
        ));

        int logged = 0;
        for (BlockPos pos : candidates) {
            if (logged >= MAX_LOGGED_EVENTS_PER_TICK) {
                break;
            }
            BlockState placedState = world.getBlockState(pos);
            PlacementFeatures features = PlacementFeatureExtractor.extract(world, player, pos);
            BladelowLearning.datasetLogger().recordManualPlacement(
                world,
                player,
                pos,
                placedState,
                features,
                mainHand.isBlank() ? offHand : mainHand
            );
            logged++;
        }
    }

    private static boolean isLikelyManualPlacement(BlockState currentState, BlockState previousState) {
        if (currentState == null || currentState.isAir()) {
            return false;
        }
        if (previousState != null && currentState.equals(previousState)) {
            return false;
        }
        return previousState == null || previousState.isAir() || previousState.isReplaceable();
    }

    private static boolean movedTooFar(BlockPos previousCenter, BlockPos currentCenter) {
        if (previousCenter == null || currentCenter == null) {
            return true;
        }
        return Math.abs(previousCenter.getX() - currentCenter.getX()) > MAX_CENTER_SHIFT
            || Math.abs(previousCenter.getY() - currentCenter.getY()) > MAX_CENTER_SHIFT
            || Math.abs(previousCenter.getZ() - currentCenter.getZ()) > MAX_CENTER_SHIFT;
    }

    private static String heldBlockId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        Item item = stack.getItem();
        if (!(item instanceof BlockItem blockItem)) {
            return "";
        }
        return Registries.BLOCK.getId(blockItem.getBlock()).toString();
    }

    private static String blockId(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).toString();
    }

    private record Snapshot(BlockPos center, Map<Long, BlockState> states) {
    }
}
