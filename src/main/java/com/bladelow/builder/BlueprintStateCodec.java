package com.bladelow.builder;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.Registries;
import net.minecraft.util.BlockRotation;

public final class BlueprintStateCodec {
    private BlueprintStateCodec() {
    }

    public static BlockState parse(String blockSpec) throws CommandSyntaxException {
        return BlockArgumentParser.block(Registries.BLOCK, blockSpec, false).blockState();
    }

    public static BlockState tryParse(String blockSpec) {
        if (blockSpec == null || blockSpec.isBlank()) {
            return null;
        }
        try {
            return parse(blockSpec.trim());
        } catch (CommandSyntaxException ex) {
            return null;
        }
    }

    public static String stringify(BlockState state) {
        return BlockArgumentParser.stringifyBlockState(state);
    }

    public static BlockState rotate(BlockState state, int turnsClockwise) {
        if (state == null) {
            return null;
        }
        return switch (Math.floorMod(turnsClockwise, 4)) {
            case 1 -> state.rotate(BlockRotation.CLOCKWISE_90);
            case 2 -> state.rotate(BlockRotation.CLOCKWISE_180);
            case 3 -> state.rotate(BlockRotation.COUNTERCLOCKWISE_90);
            default -> state;
        };
    }
}
