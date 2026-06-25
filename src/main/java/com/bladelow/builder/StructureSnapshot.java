package com.bladelow.builder;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Exact captured structure memory, independent of the older blueprint format.
 */
public record StructureSnapshot(
    String name,
    String createdAt,
    String worldId,
    int originX,
    int originY,
    int originZ,
    int width,
    int height,
    int depth,
    int blockCount,
    boolean includesAir,
    Map<String, Integer> palette,
    List<BlockEntry> blocks
) {
    public static StructureSnapshot capture(
        ServerWorld world,
        String name,
        List<BlockPos> basePoints,
        int captureHeight,
        boolean includeAir
    ) {
        if (world == null) {
            throw new IllegalArgumentException("world is unavailable");
        }
        if (basePoints == null || basePoints.isEmpty()) {
            throw new IllegalArgumentException("selection is empty");
        }
        if (captureHeight < 1 || captureHeight > 256) {
            throw new IllegalArgumentException("height must be 1..256");
        }

        int minX = basePoints.stream().mapToInt(BlockPos::getX).min().orElse(0);
        int maxX = basePoints.stream().mapToInt(BlockPos::getX).max().orElse(0);
        int minY = basePoints.stream().mapToInt(BlockPos::getY).min().orElse(0);
        int minZ = basePoints.stream().mapToInt(BlockPos::getZ).min().orElse(0);
        int maxZ = basePoints.stream().mapToInt(BlockPos::getZ).max().orElse(0);
        int maxY = Math.min(world.getTopYInclusive(), minY + captureHeight);

        List<BlockEntry> captured = new ArrayList<>();
        Map<String, Integer> palette = new TreeMap<>();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (!includeAir && state.isAir()) {
                        continue;
                    }
                    String blockState = BlueprintStateCodec.stringify(state);
                    palette.merge(blockState, 1, Integer::sum);
                    captured.add(new BlockEntry(x - minX, y - minY, z - minZ, blockState));
                }
            }
        }

        captured = captured.stream()
            .sorted(Comparator.comparingInt(BlockEntry::y).thenComparingInt(BlockEntry::x).thenComparingInt(BlockEntry::z))
            .toList();

        return new StructureSnapshot(
            normalizeName(name),
            Instant.now().toString(),
            world.getRegistryKey().getValue().toString(),
            minX,
            minY,
            minZ,
            maxX - minX + 1,
            maxY - minY + 1,
            maxZ - minZ + 1,
            captured.size(),
            includeAir,
            Map.copyOf(palette),
            List.copyOf(captured)
        );
    }

    public List<BlueprintLibrary.BlueprintPlacement> toBlueprintPlacements() {
        List<BlueprintLibrary.BlueprintPlacement> out = new ArrayList<>(blocks.size());
        for (BlockEntry block : blocks) {
            out.add(new BlueprintLibrary.BlueprintPlacement(
                new BlockPos(originX + block.x(), originY + block.y(), originZ + block.z()),
                block.blockState()
            ));
        }
        return List.copyOf(out);
    }

    public String summary() {
        return "snapshot=" + name
            + " size=" + width + "x" + height + "x" + depth
            + " blocks=" + blockCount
            + " palette=" + palette.size();
    }

    private static String normalizeName(String input) {
        if (input == null) {
            return "";
        }
        return input.trim().toLowerCase();
    }

    public record BlockEntry(int x, int y, int z, String blockState) {
    }
}
