package com.bladelow.builder;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Final output of the town planner before execution.
 *
 * A TownPlan is still flattened into a normal placement job, but it preserves
 * enough metadata for callers to report how many buildings were chosen and
 * which blueprint names were used.
 */
public record TownPlan(
    boolean ok,
    String message,
    List<BlockState> blockStates,
    List<BlockPos> targets,
    int buildings,
    List<String> usedBlueprints
) {
    public static TownPlan ok(String message, List<BlockState> blockStates, List<BlockPos> targets, int buildings, List<String> usedBlueprints) {
        return new TownPlan(true, message, List.copyOf(blockStates), List.copyOf(targets), buildings, List.copyOf(usedBlueprints));
    }

    public static TownPlan error(String message) {
        return new TownPlan(false, message, List.of(), List.of(), 0, List.of());
    }

    public List<Block> blocks() {
        return blockStates.stream()
            .map(BlockState::getBlock)
            .toList();
    }
}
