package com.bladelow.builder;

import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;

import java.util.Set;

public final class BuildSafetyPolicy {
    private static final Set<String> PROTECTED_BLOCKS = Set.of(
        "minecraft:chest",
        "minecraft:trapped_chest",
        "minecraft:barrel",
        "minecraft:hopper",
        "minecraft:dropper",
        "minecraft:dispenser",
        "minecraft:ender_chest",
        "minecraft:beacon",
        "minecraft:spawner",
        "minecraft:command_block"
    );

    private BuildSafetyPolicy() {
    }

    public static boolean isProtected(BlockState state) {
        String id = Registries.BLOCK.getId(state.getBlock()).toString();
        if (id.isBlank()) {
            return false;
        }
        if (PROTECTED_BLOCKS.contains(id)) {
            return true;
        }
        return id.endsWith("_shulker_box") || "minecraft:shulker_box".equals(id);
    }
}
