package com.bladelow.ml;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class PlacementFeatureExtractor {
    private PlacementFeatureExtractor() {
    }

    public static PlacementFeatures extract(ServerWorld world, ServerPlayerEntity player, BlockPos target) {
        var currentState = world.getBlockState(target);
        boolean replaceable = currentState.isAir() || currentState.isReplaceable();

        var belowState = world.getBlockState(target.down());
        boolean support = !belowState.isAir();

        double distance = Math.sqrt(player.squaredDistanceTo(
            target.getX() + 0.5,
            target.getY() + 0.5,
            target.getZ() + 0.5
        ));
        double normalizedDistance = Math.min(distance / 8.0, 1.0);

        return new PlacementFeatures(
            1.0,
            replaceable ? 1.0 : 0.0,
            support ? 1.0 : 0.0,
            normalizedDistance
        );
    }
}
