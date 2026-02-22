package com.bladelow.builder;

import net.minecraft.util.math.BlockPos;

public enum PlacementAxis {
    X,
    Y,
    Z;

    public BlockPos offset(int n) {
        return switch (this) {
            case X -> new BlockPos(n, 0, 0);
            case Y -> new BlockPos(0, n, 0);
            case Z -> new BlockPos(0, 0, n);
        };
    }

    public static PlacementAxis fromInput(String value) {
        return switch (value.toLowerCase()) {
            case "x" -> X;
            case "y" -> Y;
            case "z" -> Z;
            default -> throw new IllegalArgumentException("axis must be x, y, or z");
        };
    }
}
