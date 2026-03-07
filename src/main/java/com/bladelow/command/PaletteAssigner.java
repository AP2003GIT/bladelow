package com.bladelow.command;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Assigns palette blocks to placement targets using role-based spatial classification.
 *
 * When 2–3 blocks are given, targets are classified as WALL, FLOOR, or DETAIL
 * based on their position within the bounding box, giving structural variety.
 * When only 1 block is given, it is applied uniformly.
 *
 * Extracted from BladePlaceCommand for independent testability.
 */
public final class PaletteAssigner {

    private PaletteAssigner() {
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Assign palette blocks to targets for a given command tag.
     */
    public static List<Block> assign(List<Block> palette, List<BlockPos> targets, String tag) {
        if (palette.isEmpty() || targets.isEmpty()) return List.of();
        if (palette.size() == 1) return repeat(palette.get(0), targets.size());
        if ("bladeplace".equals(tag)) return lineCycle(palette, targets.size());
        if (tag.startsWith("selection") || tag.startsWith("blueprint:")) return byRoles(palette, targets, tag);
        return lineCycle(palette, targets.size());
    }

    /**
     * Override the palette on an existing targets list (used for blueprint palette overrides).
     */
    public static List<Block> applyOverride(List<Block> existing, List<BlockPos> positions, List<Block> palette) {
        if (palette.isEmpty()) return existing;
        if (existing.size() != positions.size()) return existing;
        if (palette.size() == 1) return lineCycle(palette, existing.size());
        return byRoles(palette, positions, "blueprint");
    }

    /**
     * Convert a list of Blocks to their default BlockStates.
     */
    public static List<BlockState> defaultStates(List<Block> blocks) {
        List<BlockState> out = new ArrayList<>(blocks.size());
        for (Block block : blocks) out.add(block.getDefaultState());
        return out;
    }

    // -------------------------------------------------------------------------
    // Private implementation
    // -------------------------------------------------------------------------

    private static List<Block> repeat(Block block, int count) {
        List<Block> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(block);
        return out;
    }

    private static List<Block> lineCycle(List<Block> palette, int count) {
        List<Block> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(palette.get(i % palette.size()));
        return out;
    }

    private static List<Block> byRoles(List<Block> palette, List<BlockPos> targets, String tag) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : targets) {
            minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
        }
        boolean hasInterior2d = (maxX - minX) >= 2 && (maxZ - minZ) >= 2;
        boolean hasHeight     = (maxY - minY) >= 1;

        List<Block> out = new ArrayList<>(targets.size());
        for (BlockPos pos : targets) {
            boolean edge2d = pos.getX() == minX || pos.getX() == maxX
                          || pos.getZ() == minZ || pos.getZ() == maxZ;
            Role role = classifyRole(pos, minY, maxY, edge2d, hasInterior2d, hasHeight, tag);
            out.add(palette.get(slotFor(role, palette.size())));
        }
        return out;
    }

    private static Role classifyRole(BlockPos pos, int minY, int maxY,
                                     boolean edge2d, boolean hasInterior2d,
                                     boolean hasHeight, String tag) {
        if (!hasHeight) return (!edge2d && hasInterior2d) ? Role.FLOOR : Role.WALL;
        boolean floor = pos.getY() == minY;
        boolean top   = pos.getY() == maxY;
        if (floor && !edge2d && hasInterior2d) return Role.FLOOR;
        if (top && (!edge2d || !tag.startsWith("selection"))) return Role.DETAIL;
        if (edge2d)  return Role.WALL;
        if (floor)   return Role.FLOOR;
        if (top)     return Role.DETAIL;
        return Role.WALL;
    }

    private static int slotFor(Role role, int paletteSize) {
        int[] order = switch (role) {
            case WALL   -> new int[]{0, 1, 2};
            case FLOOR  -> new int[]{1, 0, 2};
            case DETAIL -> new int[]{2, 1, 0};
        };
        for (int idx : order) if (idx < paletteSize) return idx;
        return 0;
    }

    private enum Role { WALL, FLOOR, DETAIL }
}
