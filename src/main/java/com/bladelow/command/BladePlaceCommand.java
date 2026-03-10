package com.bladelow.command;

import com.bladelow.builder.BuildRuntimeSettings;
import com.bladelow.builder.BlueprintLibrary;
import com.bladelow.builder.BlueprintStateCodec;
import com.bladelow.builder.BuildItWebService;
import com.bladelow.builder.BuildProfileStore;
import com.bladelow.builder.PlacementAxis;
import com.bladelow.builder.PlacementJob;
import com.bladelow.builder.PlacementJobRunner;
import com.bladelow.builder.SelectionState;
import com.bladelow.builder.TownDistrictType;
import com.bladelow.builder.TownZoneStore;
import com.bladelow.ml.BladelowLearning;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class BladePlaceCommand {
    private static final int MAX_SELECTION_BOX_BLOCKS = 131072;

    private BladePlaceCommand() {
    }

    /**
     * Main entry point — registers all Bladelow commands by delegating to
     * focused command modules. Only #bladeselect and #bladeblueprint remain
     * here pending their own extraction.
     */
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        // ---- Extracted modules ----
        RuntimeCommands.register(dispatcher);   // cancel, pause, continue, confirm, preview,
                                                // status, diag, move, safety, profile, model
        ZoneCommands.register(dispatcher);      // bladezone
        WebCommands.register(dispatcher);       // bladeweb

        // ---- Still inline (next split candidates) ----
        registerBladeHelp(dispatcher);
        registerBladeSelect(dispatcher);
        registerBladeBlueprint(dispatcher);
    }

    private static void registerBladeHelp(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladehelp")
            .executes(ctx -> {
                ctx.getSource().sendFeedback(() -> blueText("[Bladelow] Quick commands:"), false);
                ctx.getSource().sendFeedback(() -> blueText("[Bladelow] #bladeselect markerbox <from> <to> <height> | addhere | add <x> <y> <z> | buildh <height> <blocks_csv>"), false);
                ctx.getSource().sendFeedback(() -> blueText("[Bladelow] #bladeselect export <name> <block_id>"), false);
                ctx.getSource().sendFeedback(() -> blueText("[Bladelow] #bladezone set " + TownDistrictType.idsCsv() + " | box <type> <from> <to> | list | clear [type]"), false);
                ctx.getSource().sendFeedback(() -> blueText("[Bladelow] #blademove mode walk|auto|teleport ; reach <2.0..8.0> ; scheduler on|off ; lookahead <1..96> ; defer on|off ; maxdefer <0..8> ; autoresume on|off ; trace on|off ; traceparticles on|off"), false);
                ctx.getSource().sendFeedback(() -> blueText("[Bladelow] #bladeblueprint list|townlist|load|build|townfill|townfillsel|townpreview|townpreviewsel|townfillzone|townpreviewzone|townruncity|townclearlocks ; #bladeweb importload <index> [name] ; #bladestatus [detail] ; #bladepause ; #bladecontinue ; #bladecancel"), false);
                ctx.getSource().sendFeedback(() -> blueText("[Bladelow] #bladediag show | #bladediag export [name]"), false);
                return 1;
            })
        );
    }

    private static void registerBladeSelect(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladeselect")
            .then(literal("add")
                .then(argument("pos", BlockPosArgumentType.blockPos())
                    .executes(ctx -> {
                        ServerPlayerEntity player = ctx.getSource().getPlayer();
                        if (player == null) {
                            ctx.getSource().sendError(blueText("Player context required."));
                            return 0;
                        }
                        var worldKey = ctx.getSource().getWorld().getRegistryKey();
                        BlockPos pos = BlockPosArgumentType.getLoadedBlockPos(ctx, "pos");
                        boolean added = SelectionState.add(player.getUuid(), worldKey, pos);
                        int total = SelectionState.size(player.getUuid(), worldKey);
                        ctx.getSource().sendFeedback(() -> blueText(
                            "[Bladelow] selection " + (added ? "added" : "already had") + " " + pos.toShortString() + " total=" + total
                        ), false);
                        return 1;
                    })
                )
            )
            .then(literal("addhere")
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendError(blueText("Player context required."));
                        return 0;
                    }
                    var worldKey = ctx.getSource().getWorld().getRegistryKey();
                    BlockPos pos = player.getBlockPos();
                    boolean added = SelectionState.add(player.getUuid(), worldKey, pos);
                    int total = SelectionState.size(player.getUuid(), worldKey);
                    ctx.getSource().sendFeedback(() -> blueText(
                        "[Bladelow] selection " + (added ? "added" : "already had") + " " + pos.toShortString() + " total=" + total
                    ), false);
                    return 1;
                })
            )
            .then(literal("markerbox")
                .then(argument("from", BlockPosArgumentType.blockPos())
                    .then(argument("to", BlockPosArgumentType.blockPos())
                        .then(argument("height", IntegerArgumentType.integer(1, 256))
                            .executes(ctx -> runSelectMarkerBox(ctx, false))
                            .then(argument("mode", StringArgumentType.word())
                                .executes(ctx -> {
                                    String mode = StringArgumentType.getString(ctx, "mode");
                                    if ("solid".equalsIgnoreCase(mode)) {
                                        return runSelectMarkerBox(ctx, false);
                                    }
                                    if ("hollow".equalsIgnoreCase(mode)) {
                                        return runSelectMarkerBox(ctx, true);
                                    }
                                    ctx.getSource().sendError(blueText("[Bladelow] markerbox mode must be solid|hollow"));
                                    return 0;
                                })
                            )
                        )
                    )
                )
            )
            .then(literal("box")
                .then(argument("from", BlockPosArgumentType.blockPos())
                    .then(argument("to", BlockPosArgumentType.blockPos())
                        .executes(ctx -> runSelectBox(ctx, false))
                        .then(argument("mode", StringArgumentType.word())
                            .executes(ctx -> {
                                String mode = StringArgumentType.getString(ctx, "mode");
                                if ("solid".equalsIgnoreCase(mode)) {
                                    return runSelectBox(ctx, false);
                                }
                                if ("hollow".equalsIgnoreCase(mode)) {
                                    return runSelectBox(ctx, true);
                                }
                                ctx.getSource().sendError(blueText("[Bladelow] box mode must be solid|hollow"));
                                return 0;
                            })
                        )
                    )
                )
            )
            .then(literal("remove")
                .then(argument("pos", BlockPosArgumentType.blockPos())
                    .executes(ctx -> {
                        ServerPlayerEntity player = ctx.getSource().getPlayer();
                        if (player == null) {
                            ctx.getSource().sendError(blueText("Player context required."));
                            return 0;
                        }
                        var worldKey = ctx.getSource().getWorld().getRegistryKey();
                        BlockPos pos = BlockPosArgumentType.getLoadedBlockPos(ctx, "pos");
                        boolean removed = SelectionState.remove(player.getUuid(), worldKey, pos);
                        int total = SelectionState.size(player.getUuid(), worldKey);
                        ctx.getSource().sendFeedback(() -> blueText(
                            "[Bladelow] selection " + (removed ? "removed" : "missing") + " " + pos.toShortString() + " total=" + total
                        ), false);
                        return removed ? 1 : 0;
                    })
                )
            )
            .then(literal("undo")
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendError(blueText("Player context required."));
                        return 0;
                    }
                    var worldKey = ctx.getSource().getWorld().getRegistryKey();
                    BlockPos removed = SelectionState.popLast(player.getUuid(), worldKey);
                    if (removed == null) {
                        ctx.getSource().sendError(blueText("[Bladelow] selection is empty"));
                        return 0;
                    }
                    int total = SelectionState.size(player.getUuid(), worldKey);
                    ctx.getSource().sendFeedback(() -> blueText(
                        "[Bladelow] selection removed last " + removed.toShortString() + " total=" + total
                    ), false);
                    return 1;
                })
            )
            .then(literal("clear")
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendError(blueText("Player context required."));
                        return 0;
                    }
                    var worldKey = ctx.getSource().getWorld().getRegistryKey();
                    SelectionState.clear(player.getUuid(), worldKey);
                    ctx.getSource().sendFeedback(() -> blueText("[Bladelow] selection cleared"), false);
                    return 1;
                })
            )
            .then(literal("size")
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendError(blueText("Player context required."));
                        return 0;
                    }
                    var worldKey = ctx.getSource().getWorld().getRegistryKey();
                    ctx.getSource().sendFeedback(() -> blueText("[Bladelow] selection size=" + SelectionState.size(player.getUuid(), worldKey)), false);
                    return 1;
                })
            )
            .then(literal("list")
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendError(blueText("Player context required."));
                        return 0;
                    }
                    var worldKey = ctx.getSource().getWorld().getRegistryKey();
                    List<BlockPos> points = SelectionState.snapshot(player.getUuid(), worldKey);
                    if (points.isEmpty()) {
                        ctx.getSource().sendFeedback(() -> blueText("[Bladelow] selection list is empty"), false);
                        return 1;
                    }
                    int shown = Math.min(8, points.size());
                    for (int i = 0; i < shown; i++) {
                        BlockPos p = points.get(i);
                        int idx = i + 1;
                        ctx.getSource().sendFeedback(() -> blueText("[Bladelow] #" + idx + " " + p.toShortString()), false);
                    }
                    if (points.size() > shown) {
                        ctx.getSource().sendFeedback(() -> blueText(
                            "[Bladelow] ... +" + (points.size() - shown) + " more (use remove/undo/clear)"
                        ), false);
                    }
                    return 1;
                })
            )
            .then(literal("build")
                .then(argument("top_y", IntegerArgumentType.integer(-64, 320))
                    .then(argument("block", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayerEntity player = ctx.getSource().getPlayer();
                            if (player == null) {
                                ctx.getSource().sendError(blueText("Player context required."));
                                return 0;
                            }

                            List<Block> blocks = MaterialResolver.parseBlockSpec(StringArgumentType.getString(ctx, "block"), ctx.getSource());
                            if (blocks.isEmpty()) {
                                return 0;
                            }

                            int topY = IntegerArgumentType.getInteger(ctx, "top_y");
                            var worldKey = ctx.getSource().getWorld().getRegistryKey();
                            List<BlockPos> base = SelectionState.snapshot(player.getUuid(), worldKey);
                            if (base.isEmpty()) {
                                ctx.getSource().sendError(blueText("[Bladelow] selection is empty; use /bladeselect add"));
                                return 0;
                            }

                            List<BlockPos> targets = new ArrayList<>();
                            for (BlockPos p : base) {
                                for (int y = p.getY() + 1; y <= topY; y++) {
                                    targets.add(new BlockPos(p.getX(), y, p.getZ()));
                                }
                            }

                            if (targets.isEmpty()) {
                                ctx.getSource().sendError(blueText("[Bladelow] no targets: top_y must be above selected blocks"));
                                return 0;
                            }

                            return runPlacement(ctx.getSource(), player, blocks, targets, "selection");
                        })
                    )
                )
            )
            .then(literal("buildh")
                .then(argument("height", IntegerArgumentType.integer(1, 256))
                    .then(argument("block", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayerEntity player = ctx.getSource().getPlayer();
                            if (player == null) {
                                ctx.getSource().sendError(blueText("Player context required."));
                                return 0;
                            }

                            List<Block> blocks = MaterialResolver.parseBlockSpec(StringArgumentType.getString(ctx, "block"), ctx.getSource());
                            if (blocks.isEmpty()) {
                                return 0;
                            }

                            int height = IntegerArgumentType.getInteger(ctx, "height");
                            var worldKey = ctx.getSource().getWorld().getRegistryKey();
                            List<BlockPos> base = SelectionState.snapshot(player.getUuid(), worldKey);
                            if (base.isEmpty()) {
                                ctx.getSource().sendError(blueText("[Bladelow] selection is empty; use /bladeselect add"));
                                return 0;
                            }

                            List<BlockPos> targets = new ArrayList<>();
                            for (BlockPos p : base) {
                                for (int step = 1; step <= height; step++) {
                                    targets.add(p.add(0, step, 0));
                                }
                            }

                            if (targets.isEmpty()) {
                                ctx.getSource().sendError(blueText("[Bladelow] no targets for selection height"));
                                return 0;
                            }

                            return runPlacement(ctx.getSource(), player, blocks, targets, "selectionh");
                        })
                    )
                )
            )
            .then(literal("export")
                .then(argument("name", StringArgumentType.word())
                    .then(argument("block", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayerEntity player = ctx.getSource().getPlayer();
                            if (player == null) {
                                ctx.getSource().sendError(blueText("Player context required."));
                                return 0;
                            }
                            var worldKey = ctx.getSource().getWorld().getRegistryKey();
                            List<BlockPos> points = SelectionState.snapshot(player.getUuid(), worldKey);
                            if (points.isEmpty()) {
                                ctx.getSource().sendError(blueText("[Bladelow] selection is empty; use /bladeselect addhere or add"));
                                return 0;
                            }

                            String blockText = StringArgumentType.getString(ctx, "block");
                            Identifier id = Identifier.tryParse(blockText);
                            if (id == null || !Registries.BLOCK.containsId(id)) {
                                ctx.getSource().sendError(blueText("[Bladelow] invalid block id: " + blockText));
                                return 0;
                            }

                            String name = StringArgumentType.getString(ctx, "name");
                            var result = BlueprintLibrary.saveSelectionAsBlueprint(
                                ctx.getSource().getServer(),
                                name,
                                points,
                                id.toString()
                            );
                            if (!result.ok()) {
                                ctx.getSource().sendError(blueText("[Bladelow] " + result.message()));
                                return 0;
                            }
                            ctx.getSource().sendFeedback(() -> blueText("[Bladelow] " + result.message()), false);
                            return 1;
                        })
                    )
                )
            )
        );
    }

    private static int runSelectBox(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx, boolean hollow) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(blueText("Player context required."));
            return 0;
        }

        BlockPos from;
        BlockPos to;
        try {
            from = BlockPosArgumentType.getLoadedBlockPos(ctx, "from");
            to = BlockPosArgumentType.getLoadedBlockPos(ctx, "to");
        } catch (CommandSyntaxException ex) {
            ctx.getSource().sendError(blueText("[Bladelow] invalid box positions"));
            return 0;
        }

        int minX = Math.min(from.getX(), to.getX());
        int maxX = Math.max(from.getX(), to.getX());
        int minY = Math.min(from.getY(), to.getY());
        int maxY = Math.max(from.getY(), to.getY());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxZ = Math.max(from.getZ(), to.getZ());

        long sizeX = (long) maxX - minX + 1L;
        long sizeY = (long) maxY - minY + 1L;
        long sizeZ = (long) maxZ - minZ + 1L;
        long volume = sizeX * sizeY * sizeZ;
        if (volume > MAX_SELECTION_BOX_BLOCKS) {
            ctx.getSource().sendError(blueText(
                "[Bladelow] box too large (" + volume + " blocks). limit=" + MAX_SELECTION_BOX_BLOCKS
            ));
            return 0;
        }

        var worldKey = ctx.getSource().getWorld().getRegistryKey();
        int added = 0;
        int duplicate = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean boundary = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                    if (hollow && !boundary) {
                        continue;
                    }
                    boolean wasAdded = SelectionState.add(player.getUuid(), worldKey, new BlockPos(x, y, z));
                    if (wasAdded) {
                        added++;
                    } else {
                        duplicate++;
                    }
                }
            }
        }

        int total = SelectionState.size(player.getUuid(), worldKey);
        String mode = hollow ? "hollow" : "solid";
        int addedCount = added;
        int duplicateCount = duplicate;
        ctx.getSource().sendFeedback(() -> blueText(
            "[Bladelow] selection box " + mode
                + " size=" + sizeX + "x" + sizeY + "x" + sizeZ
                + " added=" + addedCount
                + " duplicate=" + duplicateCount
                + " total=" + total
        ), false);
        return addedCount > 0 ? 1 : 0;
    }

    private static int runSelectMarkerBox(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx, boolean hollow) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(blueText("Player context required."));
            return 0;
        }

        BlockPos from;
        BlockPos to;
        try {
            from = BlockPosArgumentType.getLoadedBlockPos(ctx, "from");
            to = BlockPosArgumentType.getLoadedBlockPos(ctx, "to");
        } catch (CommandSyntaxException ex) {
            ctx.getSource().sendError(blueText("[Bladelow] invalid marker box positions"));
            return 0;
        }

        int height = IntegerArgumentType.getInteger(ctx, "height");
        int minX = Math.min(from.getX(), to.getX());
        int maxX = Math.max(from.getX(), to.getX());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxZ = Math.max(from.getZ(), to.getZ());
        int baseY = Math.min(from.getY(), to.getY());

        long area = ((long) maxX - minX + 1L) * ((long) maxZ - minZ + 1L);
        if (area > MAX_SELECTION_BOX_BLOCKS) {
            ctx.getSource().sendError(blueText(
                "[Bladelow] marker box too large (" + area + " base blocks). limit=" + MAX_SELECTION_BOX_BLOCKS
            ));
            return 0;
        }

        var worldKey = ctx.getSource().getWorld().getRegistryKey();
        SelectionState.clear(player.getUuid(), worldKey);

        int added = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean boundary = x == minX || x == maxX || z == minZ || z == maxZ;
                if (hollow && !boundary) {
                    continue;
                }
                if (SelectionState.add(player.getUuid(), worldKey, new BlockPos(x, baseY, z))) {
                    added++;
                }
            }
        }

        showAreaMarkers(ctx.getSource(), minX, maxX, minZ, maxZ, baseY, height);
        int length = maxX - minX + 1;
        int width = maxZ - minZ + 1;
        int topY = baseY + height;
        String mode = hollow ? "hollow" : "solid";
        int total = SelectionState.size(player.getUuid(), worldKey);
        ctx.getSource().sendFeedback(() -> blueText(
            "[Bladelow] marker box set " + mode
                + " L=" + length + " W=" + width + " H=" + height
                + " baseY=" + baseY + " topY=" + topY
                + " selected=" + total
                + " (white=length blue=width red=height)"
        ), false);
        return added > 0 ? 1 : 0;
    }

    private static void showAreaMarkers(ServerCommandSource source, int minX, int maxX, int minZ, int maxZ, int baseY, int height) {
        var world = source.getWorld();
        double y = baseY + 1.05;
        for (int x = minX; x <= maxX; x++) {
            world.spawnParticles(ParticleTypes.END_ROD, x + 0.5, y, minZ + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
            world.spawnParticles(ParticleTypes.END_ROD, x + 0.5, y, maxZ + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
        }
        for (int z = minZ; z <= maxZ; z++) {
            world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, minX + 0.5, y, z + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
            world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, maxX + 0.5, y, z + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
        }

        int[][] corners = new int[][]{
            {minX, minZ},
            {minX, maxZ},
            {maxX, minZ},
            {maxX, maxZ}
        };
        for (int[] c : corners) {
            for (int step = 1; step <= height; step++) {
                world.spawnParticles(ParticleTypes.FLAME, c[0] + 0.5, baseY + step + 0.05, c[1] + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    private static int runPlacement(ServerCommandSource source, ServerPlayerEntity player, List<Block> blocks, List<BlockPos> targets, String tag) {
        return PlacementPipeline.run(source, player, blocks, targets, tag);
    }

    private static int queueStatePlacement(ServerCommandSource source, ServerPlayerEntity player, List<BlockState> requestedStates, List<BlockPos> targets, String tag) {
        return PlacementPipeline.queue(source, player, requestedStates, targets, tag, false);
    }

    private static int queueStatePlacement(
        ServerCommandSource source,
        ServerPlayerEntity player,
        List<BlockState> requestedStates,
        List<BlockPos> targets,
        String tag,
        boolean forcePreviewMode
    ) {
        return PlacementPipeline.queue(source, player, requestedStates, targets, tag, forcePreviewMode);
    }

    private static void registerBladeBlueprint(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladeblueprint")
            .then(literal("reload")
                .executes(ctx -> {
                    String result = BlueprintLibrary.reload(ctx.getSource().getServer());
                    ctx.getSource().sendFeedback(() -> blueText("[Bladelow] blueprint " + result), false);
                    return 1;
                })
            )
            .then(literal("list")
                .executes(ctx -> {
                    List<String> names = BlueprintLibrary.listNames();
                    if (names.isEmpty()) {
                        ctx.getSource().sendFeedback(() -> blueText("[Bladelow] no blueprints loaded; run /bladeblueprint reload"), false);
                        return 1;
                    }
                    ctx.getSource().sendFeedback(() -> blueText("[Bladelow] blueprints: " + String.join(", ", names)), false);
                    return 1;
                })
            )
            .then(literal("townlist")
                .executes(ctx -> {
                    List<BlueprintLibrary.BlueprintInfo> infos = BlueprintLibrary.listTownInfos();
                    if (infos.isEmpty()) {
                        ctx.getSource().sendFeedback(() -> blueText("[Bladelow] no town blueprints loaded; run /bladeblueprint reload"), false);
                        return 1;
                    }
                    List<String> names = infos.stream()
                        .map(info -> info.name() + "(" + info.plotWidth() + "x" + info.plotDepth() + ")")
                        .toList();
                    ctx.getSource().sendFeedback(() -> blueText("[Bladelow] town blueprints: " + String.join(", ", names)), false);
                    return 1;
                })
            )
            .then(literal("load")
                .then(argument("name", StringArgumentType.word())
                    .executes(ctx -> {
                        ServerPlayerEntity player = ctx.getSource().getPlayer();
                        if (player == null) {
                            ctx.getSource().sendError(blueText("Player context required."));
                            return 0;
                        }
                        String name = StringArgumentType.getString(ctx, "name");
                        boolean ok = BlueprintLibrary.select(player.getUuid(), name);
                        if (!ok) {
                            ctx.getSource().sendError(blueText("[Bladelow] unknown blueprint: " + name + " (run /bladeblueprint list)"));
                            return 0;
                        }
                        ctx.getSource().sendFeedback(() -> blueText("[Bladelow] selected blueprint " + name), false);
                        return 1;
                    })
                )
            )
            .then(literal("info")
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendError(blueText("Player context required."));
                        return 0;
                    }
                    var info = BlueprintLibrary.infoSelected(player.getUuid());
                    if (info == null) {
                        ctx.getSource().sendError(blueText("[Bladelow] no selected blueprint"));
                        return 0;
                    }
                    ctx.getSource().sendFeedback(() -> blueText("[Bladelow] " + info.summary()), false);
                    return 1;
                })
                .then(argument("name", StringArgumentType.word())
                    .executes(ctx -> {
                        String name = StringArgumentType.getString(ctx, "name");
                        var info = BlueprintLibrary.info(name);
                        if (info == null) {
                            ctx.getSource().sendError(blueText("[Bladelow] unknown blueprint: " + name));
                            return 0;
                        }
                        ctx.getSource().sendFeedback(() -> blueText("[Bladelow] " + info.summary()), false);
                        return 1;
                    })
                )
            )
            .then(literal("townfill")
                .then(argument("from", BlockPosArgumentType.blockPos())
                    .then(argument("to", BlockPosArgumentType.blockPos())
                        .executes(ctx -> runTownFill(ctx.getSource(), BlockPosArgumentType.getLoadedBlockPos(ctx, "from"), BlockPosArgumentType.getLoadedBlockPos(ctx, "to")))
                    )
                )
            )
            .then(literal("townfillsel")
                .executes(ctx -> runTownFillSelection(ctx.getSource()))
            )
            .then(literal("townpreview")
                .then(argument("from", BlockPosArgumentType.blockPos())
                    .then(argument("to", BlockPosArgumentType.blockPos())
                        .executes(ctx -> runTownPreview(ctx.getSource(), BlockPosArgumentType.getLoadedBlockPos(ctx, "from"), BlockPosArgumentType.getLoadedBlockPos(ctx, "to")))
                    )
                )
            )
            .then(literal("townpreviewsel")
                .executes(ctx -> runTownPreviewSelection(ctx.getSource()))
            )
            .then(literal("townfillzone")
                .then(argument("type", StringArgumentType.word())
                    .then(argument("from", BlockPosArgumentType.blockPos())
                        .then(argument("to", BlockPosArgumentType.blockPos())
                            .executes(ctx -> runTownFillZone(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "type"),
                                BlockPosArgumentType.getLoadedBlockPos(ctx, "from"),
                                BlockPosArgumentType.getLoadedBlockPos(ctx, "to")
                            ))
                        )
                    )
                    .executes(ctx -> runTownFillZoneSelection(ctx.getSource(), StringArgumentType.getString(ctx, "type")))
                )
            )
            .then(literal("townpreviewzone")
                .then(argument("type", StringArgumentType.word())
                    .then(argument("from", BlockPosArgumentType.blockPos())
                        .then(argument("to", BlockPosArgumentType.blockPos())
                            .executes(ctx -> runTownPreviewZone(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "type"),
                                BlockPosArgumentType.getLoadedBlockPos(ctx, "from"),
                                BlockPosArgumentType.getLoadedBlockPos(ctx, "to")
                            ))
                        )
                    )
                    .executes(ctx -> runTownPreviewZoneSelection(ctx.getSource(), StringArgumentType.getString(ctx, "type")))
                )
            )
            .then(literal("townclearlocks")
                .executes(ctx -> runTownClearLocks(ctx.getSource()))
            )
            .then(literal("townruncity")
                .executes(ctx -> runTownRunCitySelection(ctx.getSource()))
                .then(argument("from", BlockPosArgumentType.blockPos())
                    .then(argument("to", BlockPosArgumentType.blockPos())
                        .executes(ctx -> runTownRunCity(
                            ctx.getSource(),
                            BlockPosArgumentType.getLoadedBlockPos(ctx, "from"),
                            BlockPosArgumentType.getLoadedBlockPos(ctx, "to")
                        ))
                    )
                )
            )
            .then(literal("build")
                .then(argument("start", BlockPosArgumentType.blockPos())
                    .executes(ctx -> {
                        ServerPlayerEntity player = ctx.getSource().getPlayer();
                        if (player == null) {
                            ctx.getSource().sendError(blueText("Player context required."));
                            return 0;
                        }

                        BlockPos start = BlockPosArgumentType.getLoadedBlockPos(ctx, "start");
                        BlueprintLibrary.BuildPlan plan = BlueprintLibrary.resolveSelected(player.getUuid(), start);
                        if (!plan.ok()) {
                            ctx.getSource().sendError(blueText("[Bladelow] " + plan.message()));
                            return 0;
                        }
                        return queueStatePlacement(ctx.getSource(), player, plan.blockStates(), plan.targets(), "blueprint:" + plan.message());
                    })
                    .then(argument("block", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayerEntity player = ctx.getSource().getPlayer();
                            if (player == null) {
                                ctx.getSource().sendError(blueText("Player context required."));
                                return 0;
                            }

                            BlockPos start = BlockPosArgumentType.getLoadedBlockPos(ctx, "start");
                            BlueprintLibrary.BuildPlan plan = BlueprintLibrary.resolveSelected(player.getUuid(), start);
                            if (!plan.ok()) {
                                ctx.getSource().sendError(blueText("[Bladelow] " + plan.message()));
                                return 0;
                            }

                            List<Block> override = MaterialResolver.parseBlockSpec(StringArgumentType.getString(ctx, "block"), ctx.getSource());
                            if (override.isEmpty()) {
                                return 0;
                            }
                            List<Block> blocks = PaletteAssigner.applyOverride(plan.blocks(), plan.targets(), override);
                            return queueStatePlacement(ctx.getSource(), player, PaletteAssigner.defaultStates(blocks), plan.targets(), "blueprint:" + plan.message());
                        })
                    )
                )
                .then(argument("name", StringArgumentType.word())
                    .then(argument("start", BlockPosArgumentType.blockPos())
                        .executes(ctx -> {
                            ServerPlayerEntity player = ctx.getSource().getPlayer();
                            if (player == null) {
                                ctx.getSource().sendError(blueText("Player context required."));
                                return 0;
                            }

                            String name = StringArgumentType.getString(ctx, "name");
                            BlockPos start = BlockPosArgumentType.getLoadedBlockPos(ctx, "start");
                            BlueprintLibrary.BuildPlan plan = BlueprintLibrary.resolveByName(name, start);
                            if (!plan.ok()) {
                                ctx.getSource().sendError(blueText("[Bladelow] " + plan.message()));
                                return 0;
                            }
                            BlueprintLibrary.select(player.getUuid(), name);
                            return queueStatePlacement(ctx.getSource(), player, plan.blockStates(), plan.targets(), "blueprint:" + plan.message());
                        })
                        .then(argument("block", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                ServerPlayerEntity player = ctx.getSource().getPlayer();
                                if (player == null) {
                                    ctx.getSource().sendError(blueText("Player context required."));
                                    return 0;
                                }

                                String name = StringArgumentType.getString(ctx, "name");
                                BlockPos start = BlockPosArgumentType.getLoadedBlockPos(ctx, "start");
                                BlueprintLibrary.BuildPlan plan = BlueprintLibrary.resolveByName(name, start);
                                if (!plan.ok()) {
                                    ctx.getSource().sendError(blueText("[Bladelow] " + plan.message()));
                                    return 0;
                                }

                                List<Block> override = MaterialResolver.parseBlockSpec(StringArgumentType.getString(ctx, "block"), ctx.getSource());
                                if (override.isEmpty()) {
                                    return 0;
                                }
                                List<Block> blocks = PaletteAssigner.applyOverride(plan.blocks(), plan.targets(), override);
                                BlueprintLibrary.select(player.getUuid(), name);
                                return queueStatePlacement(ctx.getSource(), player, PaletteAssigner.defaultStates(blocks), plan.targets(), "blueprint:" + plan.message());
                            })
                        )
                    )
                )
            )
        );
    }

    private static int runTownFillSelection(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(blueText("Player context required."));
            return 0;
        }
        BlockPos[] bounds = selectionBoundsFromMarkers(source, player);
        if (bounds == null) {
            return 0;
        }
        return runTownFill(source, bounds[0], bounds[1]);
    }

    private static int runTownFillZoneSelection(ServerCommandSource source, String rawZoneType) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(blueText("Player context required."));
            return 0;
        }
        String zoneType = normalizeTownZoneType(rawZoneType, source);
        if (zoneType.isBlank()) {
            return 0;
        }
        BlockPos[] bounds = selectionBoundsFromMarkers(source, player);
        if (bounds == null) {
            return 0;
        }
        return runTownFillZone(source, zoneType, bounds[0], bounds[1]);
    }

    private static int runTownPreviewSelection(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(blueText("Player context required."));
            return 0;
        }
        BlockPos[] bounds = selectionBoundsFromMarkers(source, player);
        if (bounds == null) {
            return 0;
        }
        return runTownPreview(source, bounds[0], bounds[1]);
    }

    private static int runTownPreviewZoneSelection(ServerCommandSource source, String rawZoneType) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(blueText("Player context required."));
            return 0;
        }
        String zoneType = normalizeTownZoneType(rawZoneType, source);
        if (zoneType.isBlank()) {
            return 0;
        }
        BlockPos[] bounds = selectionBoundsFromMarkers(source, player);
        if (bounds == null) {
            return 0;
        }
        return runTownPreviewZone(source, zoneType, bounds[0], bounds[1]);
    }

    private static String normalizeTownZoneType(String rawZoneType, ServerCommandSource source) {
        String zoneType = TownDistrictType.normalize(rawZoneType);
        if (!zoneType.isBlank()) {
            return zoneType;
        }
        source.sendError(blueText("[Bladelow] district type must be " + TownDistrictType.idsCsv()));
        return "";
    }

    private static BlockPos[] selectionBoundsFromMarkers(ServerCommandSource source, ServerPlayerEntity player) {
        var worldKey = source.getWorld().getRegistryKey();
        List<BlockPos> points = SelectionState.snapshot(player.getUuid(), worldKey);
        if (points.isEmpty()) {
            source.sendError(blueText("[Bladelow] selection is empty; use #bladeselect markerbox first"));
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos point : points) {
            minX = Math.min(minX, point.getX());
            minY = Math.min(minY, point.getY());
            minZ = Math.min(minZ, point.getZ());
            maxX = Math.max(maxX, point.getX());
            maxY = Math.max(maxY, point.getY());
            maxZ = Math.max(maxZ, point.getZ());
        }
        return new BlockPos[]{new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ)};
    }

    private static int runTownFill(ServerCommandSource source, BlockPos from, BlockPos to) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(blueText("Player context required."));
            return 0;
        }
        BlueprintLibrary.BuildPlan plan = BlueprintLibrary.resolveTownFill(source.getWorld(), player.getUuid(), from, to);
        if (!plan.ok()) {
            source.sendError(blueText("[Bladelow] " + plan.message()));
            return 0;
        }
        source.sendFeedback(() -> blueText("[Bladelow] townfill area from=" + from.toShortString() + " to=" + to.toShortString()), false);
        return queueStatePlacement(source, player, plan.blockStates(), plan.targets(), "blueprint:" + plan.message());
    }

    private static int runTownFillZone(ServerCommandSource source, String rawZoneType, BlockPos from, BlockPos to) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(blueText("Player context required."));
            return 0;
        }
        String zoneType = normalizeTownZoneType(rawZoneType, source);
        if (zoneType.isBlank()) {
            return 0;
        }
        BlueprintLibrary.BuildPlan plan = BlueprintLibrary.resolveTownFill(source.getWorld(), player.getUuid(), from, to, zoneType, true);
        if (!plan.ok()) {
            source.sendError(blueText("[Bladelow] " + plan.message()));
            return 0;
        }
        source.sendFeedback(() -> blueText("[Bladelow] townfillzone " + zoneType + " from=" + from.toShortString() + " to=" + to.toShortString()), false);
        return queueStatePlacement(source, player, plan.blockStates(), plan.targets(), "blueprint:" + plan.message());
    }

    private static int runTownPreview(ServerCommandSource source, BlockPos from, BlockPos to) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(blueText("Player context required."));
            return 0;
        }
        BlueprintLibrary.BuildPlan plan = BlueprintLibrary.resolveTownFill(source.getWorld(), player.getUuid(), from, to, "", false);
        if (!plan.ok()) {
            source.sendError(blueText("[Bladelow] " + plan.message()));
            return 0;
        }
        source.sendFeedback(() -> blueText("[Bladelow] townpreview area from=" + from.toShortString() + " to=" + to.toShortString()), false);
        return queueStatePlacement(source, player, plan.blockStates(), plan.targets(), "blueprint:" + plan.message(), true);
    }

    private static int runTownPreviewZone(ServerCommandSource source, String rawZoneType, BlockPos from, BlockPos to) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(blueText("Player context required."));
            return 0;
        }
        String zoneType = normalizeTownZoneType(rawZoneType, source);
        if (zoneType.isBlank()) {
            return 0;
        }
        BlueprintLibrary.BuildPlan plan = BlueprintLibrary.resolveTownFill(source.getWorld(), player.getUuid(), from, to, zoneType, false);
        if (!plan.ok()) {
            source.sendError(blueText("[Bladelow] " + plan.message()));
            return 0;
        }
        source.sendFeedback(() -> blueText("[Bladelow] townpreviewzone " + zoneType + " from=" + from.toShortString() + " to=" + to.toShortString()), false);
        return queueStatePlacement(source, player, plan.blockStates(), plan.targets(), "blueprint:" + plan.message(), true);
    }

    private static int runTownClearLocks(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(blueText("Player context required."));
            return 0;
        }
        int removed = BlueprintLibrary.clearTownLotLocks(source.getWorld(), player.getUuid());
        source.sendFeedback(() -> blueText("[Bladelow] cleared town lot locks: " + removed), false);
        return 1;
    }

    private static int runTownRunCitySelection(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(blueText("Player context required."));
            return 0;
        }
        BlockPos[] bounds = selectionBoundsFromMarkers(source, player);
        if (bounds == null) {
            return 0;
        }
        return runTownRunCity(source, bounds[0], bounds[1]);
    }

    private static int runTownRunCity(ServerCommandSource source, BlockPos from, BlockPos to) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(blueText("Player context required."));
            return 0;
        }

        List<BlockState> mergedStates = new ArrayList<>();
        List<BlockPos> mergedTargets = new ArrayList<>();
        List<String> phaseSummary = new ArrayList<>();
        List<String> phases = List.of(
            TownDistrictType.CIVIC.id(),
            TownDistrictType.MARKET.id(),
            TownDistrictType.WORKSHOP.id(),
            TownDistrictType.RESIDENTIAL.id(),
            TownDistrictType.MIXED.id()
        );

        boolean roadsIncluded = false;
        for (String phase : phases) {
            BlueprintLibrary.BuildPlan phasePlan = BlueprintLibrary.resolveTownFill(
                source.getWorld(),
                player.getUuid(),
                from,
                to,
                phase,
                true,
                !roadsIncluded
            );
            if (!phasePlan.ok()) {
                String msg = phasePlan.message() == null ? "" : phasePlan.message();
                if (msg.startsWith("no " + phase + " zones in selected area")
                    || msg.startsWith("no clear lots fit town blueprints in selected area")) {
                    phaseSummary.add(phase + "=0");
                    continue;
                }
                source.sendError(blueText("[Bladelow] townruncity " + phase + " failed: " + msg));
                return 0;
            }
            if (!phasePlan.targets().isEmpty()) {
                mergedStates.addAll(phasePlan.blockStates());
                mergedTargets.addAll(phasePlan.targets());
                phaseSummary.add(phase + "=" + phasePlan.targets().size());
                roadsIncluded = true;
            } else {
                phaseSummary.add(phase + "=0");
            }
        }

        if (mergedTargets.isEmpty()) {
            source.sendError(blueText("[Bladelow] townruncity queued nothing (all phases empty or locked). Use #bladeblueprint townclearlocks if needed."));
            return 0;
        }

        source.sendFeedback(() -> blueText(
            "[Bladelow] townruncity phases "
                + String.join(" ", phaseSummary)
                + " from=" + from.toShortString()
                + " to=" + to.toShortString()
        ), false);
        return queueStatePlacement(source, player, mergedStates, mergedTargets, "blueprint:townruncity");
    }


    private static Text blueText(String message) {
        return Text.literal(message).formatted(Formatting.AQUA);
    }
}
