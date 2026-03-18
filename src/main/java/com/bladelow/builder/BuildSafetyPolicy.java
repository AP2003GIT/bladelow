package com.bladelow.builder;

import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.Set;

/**
 * Conservative block protection policy for automation.
 *
 * The builder treats a broad set of interactive or player-owned blocks as
 * protected so automated jobs do not wreck storage, redstone, crops, or utility
 * structures while filling a city.
 */
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
        if (state == null || state.isAir()) {
            return false;
        }

        // Any block entity is treated as player-owned structure data by default.
        if (state.hasBlockEntity()) {
            return true;
        }

        Identifier id = Registries.BLOCK.getId(state.getBlock());
        if (id == null) {
            return false;
        }

        String fullId = id.toString();
        String path = id.getPath();
        if (fullId.isBlank()) {
            return false;
        }
        if (PROTECTED_BLOCKS.contains(fullId)) {
            return true;
        }

        if (path.endsWith("_shulker_box") || "shulker_box".equals(path)) {
            return true;
        }

        if (path.endsWith("_bed")
            || path.endsWith("_door")
            || path.endsWith("_trapdoor")
            || path.endsWith("_fence_gate")
            || path.endsWith("_button")
            || path.endsWith("_pressure_plate")
            || path.endsWith("_sign")
            || path.endsWith("_wall_sign")
            || path.endsWith("_hanging_sign")
            || path.endsWith("_wall_hanging_sign")
            || path.endsWith("_banner")
            || path.endsWith("_wall_banner")) {
            return true;
        }

        if (path.equals("lever")
            || path.equals("repeater")
            || path.equals("comparator")
            || path.equals("redstone_wire")
            || path.equals("redstone_torch")
            || path.equals("redstone_wall_torch")
            || path.equals("observer")
            || path.equals("tripwire")
            || path.equals("tripwire_hook")
            || path.equals("daylight_detector")
            || path.equals("target")
            || path.equals("sculk_sensor")
            || path.equals("calibrated_sculk_sensor")
            || path.equals("lightning_rod")
            || path.equals("decorated_pot")
            || path.endsWith("_rail")
            || "rail".equals(path)) {
            return true;
        }

        if (path.equals("farmland")
            || path.endsWith("_sapling")
            || path.equals("bamboo_sapling")
            || path.equals("mangrove_propagule")
            || path.equals("sweet_berry_bush")
            || path.equals("cave_vines")
            || path.equals("cave_vines_plant")
            || path.equals("twisting_vines")
            || path.equals("twisting_vines_plant")
            || path.equals("weeping_vines")
            || path.equals("weeping_vines_plant")
            || path.equals("kelp")
            || path.equals("kelp_plant")
            || path.equals("seagrass")
            || path.equals("tall_seagrass")
            || path.equals("sugar_cane")
            || path.equals("attached_melon_stem")
            || path.equals("attached_pumpkin_stem")
            || path.equals("melon_stem")
            || path.equals("pumpkin_stem")
            || path.equals("wheat")
            || path.equals("carrots")
            || path.equals("potatoes")
            || path.equals("beetroots")
            || path.equals("torchflower_crop")
            || path.equals("pitcher_crop")
            || path.equals("cocoa")) {
            return true;
        }

        return path.endsWith("_portal")
            || path.equals("end_portal")
            || path.equals("end_gateway")
            || path.equals("end_portal_frame")
            || path.equals("respawn_anchor")
            || path.equals("beehive")
            || path.equals("bee_nest")
            || path.equals("bell")
            || path.equals("lodestone")
            || path.equals("campfire")
            || path.equals("soul_campfire");
    }
}
