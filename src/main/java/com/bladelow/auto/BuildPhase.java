package com.bladelow.auto;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Build phases for multi-step construction.
 *
 * Blocks in a blueprint are classified by their Y offset relative to the
 * blueprint's minimum Y:
 *
 *   FOUNDATION  y == 0            (ground-level floor, base slabs)
 *   WALLS       1 <= y <= height-2 (vertical structure, windows, doors)
 *   ROOF        y == height-1      (top layer — caps, battlements, roof blocks)
 *   DETAILS     any y, tagged      (stairs, slabs, fences, torches, signs etc.)
 *
 * DETAILS are identified by block type rather than Y position — decorative
 * blocks that don't form the primary structure.
 */
public enum BuildPhase {
    FOUNDATION(0, "Foundation — ground floor and base"),
    WALLS(1, "Walls — vertical structure"),
    ROOF(2, "Roof — top layer and caps"),
    DETAILS(3, "Details — decoration and trim");

    public final int order;
    public final String label;

    BuildPhase(int order, String label) {
        this.order = order;
        this.label = label;
    }

    // -------------------------------------------------------------------------
    // Phase slice
    // -------------------------------------------------------------------------

    public record PhaseSlice(BuildPhase phase, List<BlockState> blockStates, List<BlockPos> targets) {
        public boolean isEmpty() { return targets.isEmpty(); }
        public int size() { return targets.size(); }
    }

    /**
     * Split a flat list of (state, pos) pairs into ordered phase slices.
     * Only non-empty phases are returned.
     */
    public static List<PhaseSlice> split(List<BlockState> states, List<BlockPos> targets) {
        if (states == null || targets == null || states.size() != targets.size()) {
            return List.of();
        }

        // Find Y bounds
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BlockPos pos : targets) {
            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());
        }
        if (minY == Integer.MAX_VALUE) return List.of();

        int height = maxY - minY + 1;

        // Buckets
        List<BlockState> foundationStates  = new ArrayList<>();
        List<BlockPos>   foundationTargets = new ArrayList<>();
        List<BlockState> wallStates        = new ArrayList<>();
        List<BlockPos>   wallTargets       = new ArrayList<>();
        List<BlockState> roofStates        = new ArrayList<>();
        List<BlockPos>   roofTargets       = new ArrayList<>();
        List<BlockState> detailStates      = new ArrayList<>();
        List<BlockPos>   detailTargets     = new ArrayList<>();

        for (int i = 0; i < targets.size(); i++) {
            BlockPos pos   = targets.get(i);
            BlockState state = states.get(i);
            int relY = pos.getY() - minY;

            if (isDetailBlock(state)) {
                detailStates.add(state);
                detailTargets.add(pos);
            } else if (relY == 0) {
                foundationStates.add(state);
                foundationTargets.add(pos);
            } else if (height > 2 && relY == height - 1) {
                roofStates.add(state);
                roofTargets.add(pos);
            } else {
                wallStates.add(state);
                wallTargets.add(pos);
            }
        }

        List<PhaseSlice> result = new ArrayList<>();
        addIfNonEmpty(result, FOUNDATION, foundationStates, foundationTargets);
        addIfNonEmpty(result, WALLS,      wallStates,       wallTargets);
        addIfNonEmpty(result, ROOF,       roofStates,       roofTargets);
        addIfNonEmpty(result, DETAILS,    detailStates,     detailTargets);
        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void addIfNonEmpty(List<PhaseSlice> out, BuildPhase phase,
                                       List<BlockState> states, List<BlockPos> targets) {
        if (!targets.isEmpty()) {
            out.add(new PhaseSlice(phase, List.copyOf(states), List.copyOf(targets)));
        }
    }

    /**
     * Detail blocks: decorative items that should be placed last so they have
     * solid support from the structural phases.
     */
    private static boolean isDetailBlock(BlockState state) {
        if (state == null) return false;
        var block = state.getBlock();
        return block == Blocks.TORCH
            || block == Blocks.WALL_TORCH
            || block == Blocks.LANTERN
            || block == Blocks.FLOWER_POT
            || block == Blocks.OAK_FENCE
            || block == Blocks.SPRUCE_FENCE
            || block == Blocks.BIRCH_FENCE
            || block == Blocks.JUNGLE_FENCE
            || block == Blocks.ACACIA_FENCE
            || block == Blocks.DARK_OAK_FENCE
            || block == Blocks.NETHER_BRICK_FENCE
            || block == Blocks.IRON_BARS
            || block == Blocks.OAK_DOOR
            || block == Blocks.SPRUCE_DOOR
            || block == Blocks.BIRCH_DOOR
            || block == Blocks.IRON_DOOR
            || block == Blocks.OAK_TRAPDOOR
            || block == Blocks.SPRUCE_TRAPDOOR
            || block == Blocks.IRON_TRAPDOOR
            || block == Blocks.LADDER
            || block == Blocks.VINE
            || block == Blocks.GLASS_PANE
            || block == Blocks.WHITE_STAINED_GLASS_PANE
            || block == Blocks.OAK_SIGN
            || block == Blocks.SPRUCE_SIGN
            || block == Blocks.CHEST
            || block == Blocks.CRAFTING_TABLE
            || block == Blocks.FURNACE
            || block == Blocks.BOOKSHELF;
    }
}
