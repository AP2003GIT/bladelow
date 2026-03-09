package com.bladelow.auto;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Scans terrain around the player and scores candidate build sites.
 *
 * A "site" is a BlockPos representing the south-west corner at ground level
 * where a blueprint's footprint would be placed. Sites are scored 0.0–1.0
 * based on flatness, surface quality, and distance from the player.
 */
public final class TerrainScanner {

    /** How far from the player to search (in blocks, XZ plane). */
    private static final int SEARCH_RADIUS = 64;

    /** How many candidate sites to return (top-N by score). */
    private static final int MAX_CANDIDATES = 8;

    /** Max height variance allowed across the footprint before penalizing. */
    private static final int FLAT_TOLERANCE = 2;

    private TerrainScanner() {
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public record Site(BlockPos corner, double score, int groundY, String reason) {
    }

    /**
     * Find the best build sites for a footprint of (width x depth) blocks
     * near the player. Returns sites sorted best-first.
     */
    public static List<Site> findSites(ServerPlayerEntity player, int footprintWidth, int footprintDepth) {
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return List.of();
        }
        BlockPos origin = player.getBlockPos();

        List<Site> candidates = new ArrayList<>();
        int step = Math.max(2, Math.min(footprintWidth, footprintDepth) / 2);

        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx += step) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz += step) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                Site site = scoreSite(world, x, z, footprintWidth, footprintDepth, origin);
                if (site != null) {
                    candidates.add(site);
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(Site::score).reversed());
        return candidates.subList(0, Math.min(MAX_CANDIDATES, candidates.size()));
    }

    /**
     * Returns the best single site, or null if no suitable site is found.
     */
    public static Site bestSite(ServerPlayerEntity player, int footprintWidth, int footprintDepth) {
        List<Site> sites = findSites(player, footprintWidth, footprintDepth);
        return sites.isEmpty() ? null : sites.get(0);
    }

    // -------------------------------------------------------------------------
    // Scoring
    // -------------------------------------------------------------------------

    private static Site scoreSite(ServerWorld world, int x, int z,
                                   int w, int d, BlockPos playerPos) {
        // Sample ground heights across the footprint
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int solidCount = 0;
        int naturalCount = 0;
        int waterCount = 0;
        int voidCount = 0;

        for (int bx = 0; bx < w; bx++) {
            for (int bz = 0; bz < d; bz++) {
                int groundY = findGround(world, x + bx, z + bz);
                if (groundY == Integer.MIN_VALUE) { voidCount++; continue; }

                minY = Math.min(minY, groundY);
                maxY = Math.max(maxY, groundY);

                BlockState surface = world.getBlockState(new BlockPos(x + bx, groundY, z + bz));
                Block block = surface.getBlock();

                if (isWater(block)) { waterCount++; continue; }
                if (isSolidGround(block)) solidCount++;
                if (isNaturalGround(block)) naturalCount++;
            }
        }

        int footprintArea = w * d;
        if (voidCount > footprintArea / 4) return null;   // too much void (unloaded chunks etc)
        if (waterCount > footprintArea / 4) return null;  // too much water

        if (minY == Integer.MAX_VALUE) return null;

        int heightVariance = maxY - minY;
        int solidFraction  = solidCount * 100 / footprintArea;

        if (solidFraction < 50) return null; // less than half solid ground

        // --- Score components ---
        // 1. Flatness: penalise heavily for height variance
        double flatScore = heightVariance <= FLAT_TOLERANCE
            ? 1.0
            : Math.max(0.0, 1.0 - (heightVariance - FLAT_TOLERANCE) * 0.15);

        // 2. Surface quality: prefer natural ground (grass, dirt, stone) over air/water
        double surfaceScore = solidFraction / 100.0;

        // 3. Distance: prefer closer sites but not right on top of the player
        double dist = Math.sqrt(Math.pow(x - playerPos.getX(), 2) + Math.pow(z - playerPos.getZ(), 2));
        double distScore = dist < 8
            ? 0.2   // too close
            : dist > SEARCH_RADIUS * 0.8
                ? 0.4
                : 1.0 - (dist / SEARCH_RADIUS) * 0.5;

        // 4. Natural preference: slightly prefer grass/dirt over stone/sand
        double naturalScore = naturalCount * 1.0 / footprintArea;

        double total = (flatScore * 0.45) + (surfaceScore * 0.25) + (distScore * 0.20) + (naturalScore * 0.10);

        String reason = String.format("flat=%.2f surface=%.2f dist=%.2f variance=%d",
            flatScore, surfaceScore, distScore, heightVariance);

        return new Site(new BlockPos(x, minY, z), total, minY, reason);
    }

    // -------------------------------------------------------------------------
    // World helpers
    // -------------------------------------------------------------------------

    /** Returns the Y of the topmost non-air solid block, or MIN_VALUE if not found. */
    private static int findGround(ServerWorld world, int x, int z) {
        // Start from sea level + some headroom, scan down
        for (int y = 100; y >= world.getBottomY(); y--) {
            BlockState state = world.getBlockState(new BlockPos(x, y, z));
            if (!state.isAir() && !isWater(state.getBlock())) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static boolean isSolidGround(Block block) {
        return block == Blocks.GRASS_BLOCK
            || block == Blocks.DIRT
            || block == Blocks.COARSE_DIRT
            || block == Blocks.PODZOL
            || block == Blocks.STONE
            || block == Blocks.DEEPSLATE
            || block == Blocks.SANDSTONE
            || block == Blocks.SAND
            || block == Blocks.GRAVEL
            || block == Blocks.SNOW_BLOCK
            || block == Blocks.ICE
            || block == Blocks.PACKED_ICE;
    }

    private static boolean isNaturalGround(Block block) {
        return block == Blocks.GRASS_BLOCK
            || block == Blocks.DIRT
            || block == Blocks.COARSE_DIRT
            || block == Blocks.PODZOL;
    }

    private static boolean isWater(Block block) {
        return block == Blocks.WATER || block == Blocks.KELP || block == Blocks.SEAGRASS;
    }
}
