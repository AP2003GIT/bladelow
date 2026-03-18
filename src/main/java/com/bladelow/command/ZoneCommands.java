package com.bladelow.command;

import com.bladelow.builder.SelectionState;
import com.bladelow.builder.TownAutoLayoutPlanner;
import com.bladelow.builder.TownDistrictType;
import com.bladelow.builder.TownZoneStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Handles all #bladezone commands: set, box, list, clear.
 * Extracted from BladePlaceCommand.
 */
public final class ZoneCommands {

    private ZoneCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladezone")
            .then(literal("set")
                .then(argument("type", StringArgumentType.word())
                    .executes(ctx -> runSetSelection(ctx.getSource(), StringArgumentType.getString(ctx, "type")))
                )
            )
            .then(literal("box")
                .then(argument("type", StringArgumentType.word())
                    .then(argument("from", BlockPosArgumentType.blockPos())
                        .then(argument("to", BlockPosArgumentType.blockPos())
                            .executes(ctx -> runBox(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "type"),
                                BlockPosArgumentType.getLoadedBlockPos(ctx, "from"),
                                BlockPosArgumentType.getLoadedBlockPos(ctx, "to")
                            ))
                        )
                    )
                )
            )
            .then(literal("list").executes(ctx -> runList(ctx.getSource())))
            .then(literal("clear")
                .executes(ctx -> runClear(ctx.getSource(), null))
                .then(argument("type", StringArgumentType.word())
                    .executes(ctx -> runClear(ctx.getSource(), StringArgumentType.getString(ctx, "type")))
                )
            )
            .then(literal("autolayout")
                .then(argument("preset", StringArgumentType.word())
                    .executes(ctx -> runAutoLayout(ctx.getSource(), StringArgumentType.getString(ctx, "preset"), true))
                    .then(literal("append")
                        .executes(ctx -> runAutoLayout(ctx.getSource(), StringArgumentType.getString(ctx, "preset"), false))
                    )
                )
            )
        );
    }

    // -------------------------------------------------------------------------
    // Runners
    // -------------------------------------------------------------------------

    private static int runSetSelection(ServerCommandSource source, String type) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(blue("Player context required.")); return 0; }
        var worldKey = source.getWorld().getRegistryKey();
        List<BlockPos> points = SelectionState.snapshot(player.getUuid(), worldKey);
        TownZoneStore.ZoneResult result = TownZoneStore.setSelection(player.getUuid(), worldKey, type, points);
        if (!result.ok()) { source.sendError(blue("[Bladelow] " + result.message())); return 0; }
        source.sendFeedback(() -> blue("[Bladelow] " + result.message()), false);
        return 1;
    }

    private static int runBox(ServerCommandSource source, String type, BlockPos from, BlockPos to) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(blue("Player context required.")); return 0; }
        TownZoneStore.ZoneResult result =
            TownZoneStore.setBox(player.getUuid(), source.getWorld().getRegistryKey(), type, from, to);
        if (!result.ok()) { source.sendError(blue("[Bladelow] " + result.message())); return 0; }
        source.sendFeedback(() -> blue("[Bladelow] " + result.message()), false);
        return 1;
    }

    private static int runList(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(blue("Player context required.")); return 0; }
        List<TownZoneStore.Zone> zones = TownZoneStore.snapshot(player.getUuid(), source.getWorld().getRegistryKey());
        if (zones.isEmpty()) {
            source.sendFeedback(() -> blue("[Bladelow] no saved zones"), false);
            return 1;
        }
        Map<String, Integer> counts = TownZoneStore.summarizeByType(zones);
        List<String> summary = counts.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue()).toList();
        source.sendFeedback(() -> blue("[Bladelow] zones " + String.join(", ", summary)), false);
        int shown = Math.min(6, zones.size());
        for (int i = 0; i < shown; i++) {
            TownZoneStore.Zone zone = zones.get(i);
            int idx = i + 1;
            source.sendFeedback(() -> blue("[Bladelow] zone#" + idx + " " + zone.summary()), false);
        }
        if (zones.size() > shown) {
            source.sendFeedback(() -> blue("[Bladelow] +" + (zones.size() - shown) + " more zones"), false);
        }
        return 1;
    }

    private static int runClear(ServerCommandSource source, String type) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(blue("Player context required.")); return 0; }
        String normalizedType = type == null ? null : TownZoneStore.normalizeType(type);
        if (type != null && normalizedType.isBlank()) {
            source.sendError(blue("[Bladelow] district type must be " + TownDistrictType.idsCsv()));
            return 0;
        }
        int removed = type == null
            ? TownZoneStore.clear(player.getUuid(), source.getWorld().getRegistryKey())
            : TownZoneStore.clear(player.getUuid(), source.getWorld().getRegistryKey(), normalizedType);
        source.sendFeedback(() -> blue(type == null
            ? "[Bladelow] cleared zones=" + removed
            : "[Bladelow] cleared " + normalizedType + " zones=" + removed), false);
        return 1;
    }

    private static int runAutoLayout(ServerCommandSource source, String presetRaw, boolean clearExisting) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(blue("Player context required."));
            return 0;
        }

        BlockPos[] bounds = selectionBoundsFromMarkers(player.getUuid(), source.getWorld().getRegistryKey());
        if (bounds == null) {
            source.sendError(blue("[Bladelow] selection is empty; use #bladeselect markerbox first"));
            return 0;
        }
        TownAutoLayoutPlanner.ApplyResult result = TownAutoLayoutPlanner.apply(
            player.getUuid(),
            source.getWorld().getRegistryKey(),
            bounds[0],
            bounds[1],
            presetRaw,
            clearExisting,
            source.getWorld()
        );
        if (!result.ok()) {
            source.sendError(blue("[Bladelow] " + result.message()));
            return 0;
        }
        source.sendFeedback(() -> blue("[Bladelow] " + result.message()), false);
        return 1;
    }

    private static BlockPos[] selectionBoundsFromMarkers(UUID playerId, net.minecraft.registry.RegistryKey<net.minecraft.world.World> worldKey) {
        List<BlockPos> points = SelectionState.snapshot(playerId, worldKey);
        if (points.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos point : points) {
            minX = Math.min(minX, point.getX());
            minZ = Math.min(minZ, point.getZ());
            maxX = Math.max(maxX, point.getX());
            maxZ = Math.max(maxZ, point.getZ());
        }
        return new BlockPos[]{new BlockPos(minX, 0, minZ), new BlockPos(maxX, 0, maxZ)};
    }

    static Text blue(String msg) {
        return Text.literal(msg).formatted(Formatting.AQUA);
    }
}
