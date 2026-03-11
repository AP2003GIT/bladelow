package com.bladelow.command;

import com.bladelow.builder.SelectionState;
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

import java.util.ArrayList;
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

        String preset = presetRaw == null ? "" : presetRaw.trim().toLowerCase();
        if (!preset.equals("balanced") && !preset.equals("medieval") && !preset.equals("harbor")) {
            source.sendError(blue("[Bladelow] preset must be balanced|medieval|harbor"));
            return 0;
        }

        BlockPos[] bounds = selectionBoundsFromMarkers(player.getUuid(), source.getWorld().getRegistryKey());
        if (bounds == null) {
            source.sendError(blue("[Bladelow] selection is empty; use #bladeselect markerbox first"));
            return 0;
        }

        int minX = Math.min(bounds[0].getX(), bounds[1].getX());
        int maxX = Math.max(bounds[0].getX(), bounds[1].getX());
        int minZ = Math.min(bounds[0].getZ(), bounds[1].getZ());
        int maxZ = Math.max(bounds[0].getZ(), bounds[1].getZ());
        if (maxX - minX < 5 || maxZ - minZ < 5) {
            source.sendError(blue("[Bladelow] selection too small for autolayout (need at least 6x6)"));
            return 0;
        }

        if (clearExisting) {
            TownZoneStore.clear(player.getUuid(), source.getWorld().getRegistryKey());
        }

        List<AutoZone> zones = generatePresetZones(preset, minX, maxX, minZ, maxZ);
        int saved = 0;
        for (AutoZone zone : zones) {
            TownZoneStore.ZoneResult result = TownZoneStore.setBox(
                player.getUuid(),
                source.getWorld().getRegistryKey(),
                zone.type(),
                new BlockPos(zone.minX(), 0, zone.minZ()),
                new BlockPos(zone.maxX(), 0, zone.maxZ())
            );
            if (result.ok()) {
                saved++;
            }
        }

        if (saved == 0) {
            source.sendError(blue("[Bladelow] autolayout generated no valid zones"));
            return 0;
        }

        List<TownZoneStore.Zone> snapshot = TownZoneStore.snapshot(player.getUuid(), source.getWorld().getRegistryKey());
        Map<String, Integer> counts = TownZoneStore.summarizeByType(snapshot);
        List<String> summary = counts.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .toList();
        int savedCount = saved;

        source.sendFeedback(() -> blue(
            "[Bladelow] autolayout " + preset
                + " " + (clearExisting ? "applied" : "appended")
                + " zones=" + savedCount
                + " (" + String.join(", ", summary) + ")"
        ), false);
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

    private static List<AutoZone> generatePresetZones(String preset, int minX, int maxX, int minZ, int maxZ) {
        int[] xCuts = splitIntoThirds(minX, maxX);
        int[] zCuts = splitIntoThirds(minZ, maxZ);
        String[][] map = zoneMapForPreset(preset);
        List<AutoZone> zones = new ArrayList<>(9);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                String type = map[row][col];
                int zoneMinX = xCuts[col];
                int zoneMaxX = xCuts[col + 1];
                int zoneMinZ = zCuts[row];
                int zoneMaxZ = zCuts[row + 1];
                if (zoneMinX > zoneMaxX || zoneMinZ > zoneMaxZ) {
                    continue;
                }
                zones.add(new AutoZone(type, zoneMinX, zoneMaxX, zoneMinZ, zoneMaxZ));
            }
        }
        return zones;
    }

    private static int[] splitIntoThirds(int min, int max) {
        int size = (max - min) + 1;
        int first = min + (size / 3) - 1;
        int second = min + ((size * 2) / 3) - 1;
        first = clamp(first, min, max);
        second = clamp(second, first, max);
        return new int[]{min, first, second, max};
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String[][] zoneMapForPreset(String preset) {
        if ("medieval".equals(preset)) {
            return new String[][]{
                {"workshop", "civic", "workshop"},
                {"residential", "civic", "market"},
                {"residential", "mixed", "market"}
            };
        }
        if ("harbor".equals(preset)) {
            return new String[][]{
                {"workshop", "workshop", "market"},
                {"mixed", "civic", "market"},
                {"residential", "residential", "mixed"}
            };
        }
        return new String[][]{
            {"residential", "market", "workshop"},
            {"residential", "civic", "workshop"},
            {"mixed", "market", "mixed"}
        };
    }

    private record AutoZone(String type, int minX, int maxX, int minZ, int maxZ) {
    }

    static Text blue(String msg) {
        return Text.literal(msg).formatted(Formatting.AQUA);
    }
}
