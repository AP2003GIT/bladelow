package com.bladelow.command;

import com.bladelow.builder.BuildRuntimeSettings;
import com.bladelow.builder.BlueprintLibrary;
import com.bladelow.builder.BuildItWebService;
import com.bladelow.builder.BuildProfileStore;
import com.bladelow.builder.PlacementAxis;
import com.bladelow.builder.PlacementJob;
import com.bladelow.builder.PlacementJobRunner;
import com.bladelow.builder.SelectionState;
import com.bladelow.ml.BladelowLearning;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.block.Block;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class BladePlaceCommand {
    private BladePlaceCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        registerBladePlace(dispatcher);
        registerBladeCancel(dispatcher);
        registerBladeConfirm(dispatcher);
        registerBladePreview(dispatcher);
        registerBladeStatus(dispatcher);
        registerBladeModel(dispatcher);
        registerBladeMove(dispatcher);
        registerBladeSafety(dispatcher);
        registerBladeProfile(dispatcher);
        registerBladeWeb(dispatcher);
        registerBladeBlueprint(dispatcher);
        registerBladeSelect(dispatcher);
    }

    private static void registerBladePlace(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladeplace")
            .then(argument("block", StringArgumentType.word())
                .then(argument("start", BlockPosArgumentType.blockPos())
                    .then(argument("count", IntegerArgumentType.integer(1, 4096))
                        .executes(ctx -> runBladePlaceWithAxis(ctx, "x"))
                        .then(argument("axis", StringArgumentType.word())
                            .executes(ctx -> runBladePlaceWithAxis(ctx, StringArgumentType.getString(ctx, "axis")))
                        )
                    )
                )
            )
        );
    }

    private static int runBladePlaceWithAxis(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx, String axisText) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(blueText("Player context required."));
            return 0;
        }

        List<Block> blocks = parseBlockSpec(StringArgumentType.getString(ctx, "block"), ctx.getSource());
        if (blocks.isEmpty()) {
            return 0;
        }

        BlockPos start;
        try {
            start = BlockPosArgumentType.getLoadedBlockPos(ctx, "start");
        } catch (CommandSyntaxException ex) {
            ctx.getSource().sendError(blueText("[Bladelow] invalid start position"));
            return 0;
        }
        int count = IntegerArgumentType.getInteger(ctx, "count");

        PlacementAxis axis;
        try {
            axis = PlacementAxis.fromInput(axisText);
        } catch (IllegalArgumentException ex) {
            ctx.getSource().sendError(blueText(ex.getMessage()));
            return 0;
        }

        List<BlockPos> targets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            targets.add(start.add(axis.offset(i)));
        }

        return runPlacement(ctx.getSource(), player, blocks, targets, "bladeplace");
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
                .then(argument("block", StringArgumentType.word())
                    .then(argument("top_y", IntegerArgumentType.integer(-64, 320))
                        .executes(ctx -> {
                            ServerPlayerEntity player = ctx.getSource().getPlayer();
                            if (player == null) {
                                ctx.getSource().sendError(blueText("Player context required."));
                                return 0;
                            }

                            List<Block> blocks = parseBlockSpec(StringArgumentType.getString(ctx, "block"), ctx.getSource());
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
        );
    }

    private static int runPlacement(ServerCommandSource source, ServerPlayerEntity player, List<Block> blocks, List<BlockPos> targets, String tag) {
        List<BlockPos> orderedTargets = orderTargetsForExecution(player, targets, tag);
        List<Block> perTargetBlocks = new ArrayList<>(orderedTargets.size());
        for (int i = 0; i < orderedTargets.size(); i++) {
            perTargetBlocks.add(blocks.get(i % blocks.size()));
        }
        return queuePlacement(source, player, perTargetBlocks, orderedTargets, tag);
    }

    private static List<BlockPos> orderTargetsForExecution(ServerPlayerEntity player, List<BlockPos> targets, String tag) {
        if (targets.size() <= 1) {
            return targets;
        }
        if ("bladeplace".equals(tag)) {
            return targets;
        }

        List<BlockPos> remaining = new ArrayList<>(targets);
        List<BlockPos> ordered = new ArrayList<>(targets.size());

        BlockPos current = nearestTo(remaining, player.getX(), player.getY(), player.getZ());
        if (current == null) {
            return targets;
        }
        ordered.add(current);
        remaining.remove(current);

        while (!remaining.isEmpty()) {
            BlockPos next = nearestTo(remaining, current.getX() + 0.5, current.getY() + 0.5, current.getZ() + 0.5);
            ordered.add(next);
            remaining.remove(next);
            current = next;
        }
        return ordered;
    }

    private static BlockPos nearestTo(List<BlockPos> points, double x, double y, double z) {
        if (points.isEmpty()) {
            return null;
        }
        return points.stream()
            .min(Comparator.comparingDouble(p -> p.getSquaredDistance(x, y, z)))
            .orElse(null);
    }

    private static int queuePlacement(ServerCommandSource source, ServerPlayerEntity player, List<Block> perTargetBlocks, List<BlockPos> targets, String tag) {
        PlacementJob job = new PlacementJob(
            player.getUuid(),
            source.getWorld().getRegistryKey(),
            perTargetBlocks,
            targets,
            tag
        );
        var server = source.getServer();
        boolean previewMode = BuildRuntimeSettings.previewBeforeBuild();
        boolean replaced = previewMode
            ? PlacementJobRunner.hasPending(player.getUuid())
            : PlacementJobRunner.hasActive(player.getUuid());
        PlacementJobRunner.queueOrPreview(server, job);
        String queuedMessage = "[Bladelow] queued " + tag + " targets=" + targets.size()
            + " blocks=" + perTargetBlocks.size()
            + " " + BuildRuntimeSettings.summary()
            + (previewMode ? " [pending]" : " [active]")
            + (replaced ? " (replaced previous pending job)" : "");
        source.sendFeedback(() -> blueText(queuedMessage), false);
        return 1;
    }

    private static List<Block> parseBlockSpec(String blockSpec, ServerCommandSource source) {
        String[] tokens = blockSpec.split(",");
        if (tokens.length < 1 || tokens.length > 3) {
            source.sendError(blueText("Block list must have 1 to 3 ids (comma-separated)."));
            return List.of();
        }

        List<Block> blocks = new ArrayList<>();
        for (String token : tokens) {
            String blockIdText = token.trim();
            Identifier id = Identifier.tryParse(blockIdText);
            if (id == null || !Registries.BLOCK.containsId(id)) {
                source.sendError(blueText("Invalid block id: " + blockIdText));
                return List.of();
            }
            blocks.add(Registries.BLOCK.get(id));
        }
        return blocks;
    }

    private static void registerBladeCancel(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladecancel")
            .executes(ctx -> {
                ServerPlayerEntity player = ctx.getSource().getPlayer();
                if (player == null) {
                    ctx.getSource().sendError(blueText("Player context required."));
                    return 0;
                }
                boolean canceled = PlacementJobRunner.cancel(player.getUuid());
                if (canceled) {
                    ctx.getSource().sendFeedback(() -> blueText("[Bladelow] active build canceled."), false);
                } else {
                    ctx.getSource().sendFeedback(() -> blueText("[Bladelow] no active build to cancel."), false);
                }
                return 1;
            })
        );
    }

    private static void registerBladeConfirm(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladeconfirm")
            .executes(ctx -> {
                ServerPlayerEntity player = ctx.getSource().getPlayer();
                if (player == null) {
                    ctx.getSource().sendError(blueText("Player context required."));
                    return 0;
                }
                if (PlacementJobRunner.hasActive(player.getUuid())) {
                    ctx.getSource().sendError(blueText("[Bladelow] active build is running; cancel it before confirm"));
                    return 0;
                }
                boolean ok = PlacementJobRunner.confirm(player.getUuid());
                if (!ok) {
                    ctx.getSource().sendError(blueText("[Bladelow] no pending preview to confirm"));
                    return 0;
                }
                ctx.getSource().sendFeedback(() -> blueText("[Bladelow] preview confirmed; build started"), false);
                return 1;
            })
        );
    }

    private static void registerBladePreview(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladepreview")
            .then(literal("show")
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendError(blueText("Player context required."));
                        return 0;
                    }
                    boolean shown = PlacementJobRunner.previewPending(player.getUuid(), ctx.getSource().getServer());
                    if (!shown) {
                        ctx.getSource().sendError(blueText("[Bladelow] no pending preview"));
                        return 0;
                    }
                    return 1;
                })
            )
        );
    }

    private static void registerBladeStatus(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladestatus")
            .executes(ctx -> {
                ServerPlayerEntity player = ctx.getSource().getPlayer();
                if (player == null) {
                    ctx.getSource().sendError(blueText("Player context required."));
                    return 0;
                }
                String message = PlacementJobRunner.status(player.getUuid());
                ctx.getSource().sendFeedback(() -> blueText(message), false);
                return 1;
            })
        );
    }

    private static void registerBladeMove(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("blademove")
            .then(literal("show")
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> blueText("[Bladelow] " + BuildRuntimeSettings.summary()), false);
                    return 1;
                })
            )
            .then(literal("on")
                .executes(ctx -> {
                    BuildRuntimeSettings.setSmartMoveEnabled(true);
                    ctx.getSource().sendFeedback(() -> blueText("[Bladelow] smart move enabled"), false);
                    return 1;
                })
            )
            .then(literal("off")
                .executes(ctx -> {
                    BuildRuntimeSettings.setSmartMoveEnabled(false);
                    ctx.getSource().sendFeedback(() -> blueText("[Bladelow] smart move disabled"), false);
                    return 1;
                })
            )
            .then(literal("mode")
                .then(argument("type", StringArgumentType.word())
                    .executes(ctx -> {
                        String mode = StringArgumentType.getString(ctx, "type");
                        if (!BuildRuntimeSettings.setMoveMode(mode)) {
                            ctx.getSource().sendError(blueText("[Bladelow] mode must be walk, auto, or teleport"));
                            return 0;
                        }
                        ctx.getSource().sendFeedback(() -> blueText("[Bladelow] mode set to " + mode), false);
                        return 1;
                    })
                )
            )
            .then(literal("reach")
                .then(argument("distance", DoubleArgumentType.doubleArg(2.0, 8.0))
                    .executes(ctx -> {
                        BuildRuntimeSettings.setReachDistance(DoubleArgumentType.getDouble(ctx, "distance"));
                        ctx.getSource().sendFeedback(() -> blueText(
                            "[Bladelow] reach distance set to " + String.format("%.2f", BuildRuntimeSettings.reachDistance())
                        ), false);
                        return 1;
                    })
                )
            )
        );
    }

    private static void registerBladeSafety(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladesafety")
            .then(literal("show")
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> blueText("[Bladelow] " + BuildRuntimeSettings.summary()), false);
                    return 1;
                })
            )
            .then(literal("strict")
                .then(argument("enabled", StringArgumentType.word())
                    .executes(ctx -> {
                        String enabled = StringArgumentType.getString(ctx, "enabled");
                        if (!enabled.equalsIgnoreCase("on") && !enabled.equalsIgnoreCase("off")) {
                            ctx.getSource().sendError(blueText("[Bladelow] use on|off"));
                            return 0;
                        }
                        BuildRuntimeSettings.setStrictAirOnly(enabled.equalsIgnoreCase("on"));
                        ctx.getSource().sendFeedback(() -> blueText("[Bladelow] strictAir set to " + enabled), false);
                        return 1;
                    })
                )
            )
            .then(literal("preview")
                .then(argument("enabled", StringArgumentType.word())
                    .executes(ctx -> {
                        String enabled = StringArgumentType.getString(ctx, "enabled");
                        if (!enabled.equalsIgnoreCase("on") && !enabled.equalsIgnoreCase("off")) {
                            ctx.getSource().sendError(blueText("[Bladelow] use on|off"));
                            return 0;
                        }
                        BuildRuntimeSettings.setPreviewBeforeBuild(enabled.equalsIgnoreCase("on"));
                        ctx.getSource().sendFeedback(() -> blueText("[Bladelow] preview-before-build set to " + enabled), false);
                        return 1;
                    })
                )
            )
        );
    }

    private static void registerBladeProfile(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladeprofile")
            .then(literal("list")
                .executes(ctx -> {
                    List<String> names = BuildProfileStore.list(ctx.getSource().getServer());
                    ctx.getSource().sendFeedback(() -> blueText("[Bladelow] profiles: " + String.join(", ", names)), false);
                    return 1;
                })
            )
            .then(literal("save")
                .then(argument("name", StringArgumentType.word())
                    .executes(ctx -> {
                        String name = StringArgumentType.getString(ctx, "name");
                        String status = BuildProfileStore.save(ctx.getSource().getServer(), name);
                        ctx.getSource().sendFeedback(() -> blueText("[Bladelow] " + status), false);
                        return 1;
                    })
                )
            )
            .then(literal("load")
                .then(argument("name", StringArgumentType.word())
                    .executes(ctx -> {
                        String name = StringArgumentType.getString(ctx, "name");
                        String status = BuildProfileStore.load(ctx.getSource().getServer(), name);
                        ctx.getSource().sendFeedback(() -> blueText("[Bladelow] " + status + " => " + BuildRuntimeSettings.summary()), false);
                        return 1;
                    })
                )
            )
        );
    }

    private static void registerBladeWeb(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladeweb")
            .then(literal("catalog")
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendError(blueText("Player context required."));
                        return 0;
                    }
                    var res = BuildItWebService.syncCatalog(player.getUuid(), 12);
                    if (!res.ok()) {
                        ctx.getSource().sendError(blueText("[Bladelow] " + res.message()));
                        return 0;
                    }
                    ctx.getSource().sendFeedback(() -> blueText("[Bladelow] " + res.message()), false);
                    List<BuildItWebService.CatalogItem> list = BuildItWebService.catalog(player.getUuid());
                    for (BuildItWebService.CatalogItem item : list) {
                        ctx.getSource().sendFeedback(() -> blueText("[" + item.index() + "] " + item.title()), false);
                    }
                    return 1;
                })
                .then(argument("limit", IntegerArgumentType.integer(1, 50))
                    .executes(ctx -> {
                        ServerPlayerEntity player = ctx.getSource().getPlayer();
                        if (player == null) {
                            ctx.getSource().sendError(blueText("Player context required."));
                            return 0;
                        }
                        int limit = IntegerArgumentType.getInteger(ctx, "limit");
                        var res = BuildItWebService.syncCatalog(player.getUuid(), limit);
                        if (!res.ok()) {
                            ctx.getSource().sendError(blueText("[Bladelow] " + res.message()));
                            return 0;
                        }
                        ctx.getSource().sendFeedback(() -> blueText("[Bladelow] " + res.message()), false);
                        List<BuildItWebService.CatalogItem> list = BuildItWebService.catalog(player.getUuid());
                        for (BuildItWebService.CatalogItem item : list) {
                            ctx.getSource().sendFeedback(() -> blueText("[" + item.index() + "] " + item.title()), false);
                        }
                        return 1;
                    })
                )
            )
            .then(literal("import")
                .then(argument("index", IntegerArgumentType.integer(1, 100))
                    .executes(ctx -> {
                        ServerPlayerEntity player = ctx.getSource().getPlayer();
                        if (player == null) {
                            ctx.getSource().sendError(blueText("Player context required."));
                            return 0;
                        }
                        int index = IntegerArgumentType.getInteger(ctx, "index");
                        var res = BuildItWebService.importPicked(ctx.getSource().getServer(), player.getUuid(), index, "");
                        if (!res.ok()) {
                            ctx.getSource().sendError(blueText("[Bladelow] " + res.message()));
                            return 0;
                        }
                        ctx.getSource().sendFeedback(() -> blueText("[Bladelow] " + res.message()), false);
                        return 1;
                    })
                    .then(argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayerEntity player = ctx.getSource().getPlayer();
                            if (player == null) {
                                ctx.getSource().sendError(blueText("Player context required."));
                                return 0;
                            }
                            int index = IntegerArgumentType.getInteger(ctx, "index");
                            String name = StringArgumentType.getString(ctx, "name");
                            var res = BuildItWebService.importPicked(ctx.getSource().getServer(), player.getUuid(), index, name);
                            if (!res.ok()) {
                                ctx.getSource().sendError(blueText("[Bladelow] " + res.message()));
                                return 0;
                            }
                            ctx.getSource().sendFeedback(() -> blueText("[Bladelow] " + res.message()), false);
                            return 1;
                        })
                    )
                )
                .then(argument("url", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String url = StringArgumentType.getString(ctx, "url");
                        var res = BuildItWebService.importFromUrl(ctx.getSource().getServer(), url, "");
                        if (!res.ok()) {
                            ctx.getSource().sendError(blueText("[Bladelow] " + res.message()));
                            return 0;
                        }
                        ctx.getSource().sendFeedback(() -> blueText("[Bladelow] " + res.message()), false);
                        return 1;
                    })
                )
            )
        );
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
                        return queuePlacement(ctx.getSource(), player, plan.blocks(), plan.targets(), "blueprint:" + plan.message());
                    })
                    .then(argument("block", StringArgumentType.word())
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

                            List<Block> override = parseBlockSpec(StringArgumentType.getString(ctx, "block"), ctx.getSource());
                            if (override.isEmpty()) {
                                return 0;
                            }
                            List<Block> blocks = applyPaletteOverride(plan.blocks(), override);
                            return queuePlacement(ctx.getSource(), player, blocks, plan.targets(), "blueprint:" + plan.message());
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
                            return queuePlacement(ctx.getSource(), player, plan.blocks(), plan.targets(), "blueprint:" + plan.message());
                        })
                        .then(argument("block", StringArgumentType.word())
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

                                List<Block> override = parseBlockSpec(StringArgumentType.getString(ctx, "block"), ctx.getSource());
                                if (override.isEmpty()) {
                                    return 0;
                                }
                                List<Block> blocks = applyPaletteOverride(plan.blocks(), override);
                                BlueprintLibrary.select(player.getUuid(), name);
                                return queuePlacement(ctx.getSource(), player, blocks, plan.targets(), "blueprint:" + plan.message());
                            })
                        )
                    )
                )
            )
        );
    }

    private static List<Block> applyPaletteOverride(List<Block> targets, List<Block> palette) {
        if (palette.isEmpty()) {
            return targets;
        }
        List<Block> out = new ArrayList<>(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            out.add(palette.get(i % palette.size()));
        }
        return out;
    }

    private static void registerBladeModel(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("blademodel")
            .then(literal("show")
                .executes(ctx -> {
                    String message = "[Bladelow] " + BladelowLearning.model().summary() + " " + BuildRuntimeSettings.summary();
                    ctx.getSource().sendFeedback(() -> blueText(message), false);
                    return 1;
                })
            )
            .then(literal("reset")
                .executes(ctx -> {
                    BladelowLearning.model().reset();
                    String saveStatus = BladelowLearning.save();
                    ctx.getSource().sendFeedback(() -> blueText("[Bladelow] model reset; " + saveStatus), false);
                    return 1;
                })
            )
            .then(literal("save")
                .executes(ctx -> {
                    String saveStatus = BladelowLearning.save();
                    ctx.getSource().sendFeedback(() -> blueText("[Bladelow] model " + saveStatus), false);
                    return 1;
                })
            )
            .then(literal("load")
                .executes(ctx -> {
                    String loadStatus = BladelowLearning.load();
                    ctx.getSource().sendFeedback(() -> blueText("[Bladelow] model " + loadStatus), false);
                    return 1;
                })
            )
            .then(literal("configure")
                .then(argument("threshold", DoubleArgumentType.doubleArg(-2.0, 2.0))
                    .then(argument("learning_rate", DoubleArgumentType.doubleArg(0.001, 1.0))
                        .executes(ctx -> {
                            double threshold = DoubleArgumentType.getDouble(ctx, "threshold");
                            double learningRate = DoubleArgumentType.getDouble(ctx, "learning_rate");
                            BladelowLearning.model().configure(threshold, learningRate);
                            String saveStatus = BladelowLearning.save();
                            ctx.getSource().sendFeedback(() -> blueText(
                                "[Bladelow] model configured: threshold=" + threshold + " lr=" + learningRate + "; " + saveStatus
                            ), false);
                            return 1;
                        })
                    )
                )
            )
        );
    }

    private static Text blueText(String message) {
        return Text.literal(message).formatted(Formatting.AQUA);
    }
}
