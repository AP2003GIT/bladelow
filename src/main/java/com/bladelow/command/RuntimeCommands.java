package com.bladelow.command;

import com.bladelow.builder.BuildProfileStore;
import com.bladelow.builder.BuildRuntimeSettings;
import com.bladelow.builder.PlacementJobRunner;
import com.bladelow.builder.SelectionState;
import com.bladelow.builder.TownPlanner;
import com.bladelow.builder.TownZoneStore;
import com.bladelow.ml.BladelowLearning;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.List;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Runtime control commands: cancel, pause, continue, confirm, preview,
 * status, diag, move, safety, profile, model.
 *
 * Extracted from BladePlaceCommand.
 */
public final class RuntimeCommands {

    private RuntimeCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        // Group runtime controls by concern so the top-level registration stays
        // readable even as more knobs are added.
        registerCancel(dispatcher);
        registerPause(dispatcher);
        registerContinue(dispatcher);
        registerConfirm(dispatcher);
        registerPreview(dispatcher);
        registerStatus(dispatcher);
        registerDiag(dispatcher);
        registerMove(dispatcher);
        registerSafety(dispatcher);
        registerProfile(dispatcher);
        registerModel(dispatcher);
    }

    // -------------------------------------------------------------------------
    // Job lifecycle
    // -------------------------------------------------------------------------

    private static void registerCancel(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladecancel").executes(ctx -> {
            ServerPlayerEntity player = ctx.getSource().getPlayer();
            if (player == null) { ctx.getSource().sendError(blue("Player context required.")); return 0; }
            boolean canceled = PlacementJobRunner.cancel(ctx.getSource().getServer(), player.getUuid());
            ctx.getSource().sendFeedback(() -> blue(canceled
                ? "[Bladelow] active build canceled."
                : "[Bladelow] no active build to cancel."), false);
            return 1;
        }));
    }

    private static void registerPause(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladepause").executes(ctx -> {
            ServerPlayerEntity player = ctx.getSource().getPlayer();
            if (player == null) { ctx.getSource().sendError(blue("Player context required.")); return 0; }
            boolean paused = PlacementJobRunner.pause(ctx.getSource().getServer(), player.getUuid());
            if (paused) {
                ctx.getSource().sendFeedback(() -> blue("[Bladelow] build paused. Use #bladecontinue to resume."), false);
                return 1;
            }
            if (PlacementJobRunner.hasPending(player.getUuid())) {
                ctx.getSource().sendFeedback(() -> blue("[Bladelow] build is already paused/pending."), false);
                return 1;
            }
            ctx.getSource().sendFeedback(() -> blue("[Bladelow] no active build to pause."), false);
            return 0;
        }));
    }

    private static void registerContinue(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladecontinue").executes(ctx -> {
            ServerPlayerEntity player = ctx.getSource().getPlayer();
            if (player == null) { ctx.getSource().sendError(blue("Player context required.")); return 0; }
            if (PlacementJobRunner.hasActive(player.getUuid())) {
                ctx.getSource().sendFeedback(() -> blue("[Bladelow] build is already running."), false);
                return 1;
            }
            boolean resumed = PlacementJobRunner.resume(ctx.getSource().getServer(), player.getUuid());
            if (!resumed) { ctx.getSource().sendError(blue("[Bladelow] no paused/pending build to continue.")); return 0; }
            ctx.getSource().sendFeedback(() -> blue("[Bladelow] build continued."), false);
            return 1;
        }));
    }

    private static void registerConfirm(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladeconfirm").executes(ctx -> {
            ServerPlayerEntity player = ctx.getSource().getPlayer();
            if (player == null) { ctx.getSource().sendError(blue("Player context required.")); return 0; }
            if (PlacementJobRunner.hasActive(player.getUuid())) {
                ctx.getSource().sendError(blue("[Bladelow] active build is running; cancel it before confirm"));
                return 0;
            }
            boolean ok = PlacementJobRunner.confirm(ctx.getSource().getServer(), player.getUuid());
            if (!ok) { ctx.getSource().sendError(blue("[Bladelow] no pending preview to confirm")); return 0; }
            ctx.getSource().sendFeedback(() -> blue("[Bladelow] preview confirmed; build started"), false);
            return 1;
        }));
    }

    private static void registerPreview(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladepreview")
            .then(literal("show").executes(ctx -> {
                ServerPlayerEntity player = ctx.getSource().getPlayer();
                if (player == null) { ctx.getSource().sendError(blue("Player context required.")); return 0; }
                boolean shown = PlacementJobRunner.previewPending(player.getUuid(), ctx.getSource().getServer());
                if (!shown) { ctx.getSource().sendError(blue("[Bladelow] no pending preview")); return 0; }
                return 1;
            }))
        );
    }

    // -------------------------------------------------------------------------
    // Status / diagnostics
    // -------------------------------------------------------------------------

    private static void registerStatus(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladestatus")
            .executes(ctx -> {
                ServerPlayerEntity player = ctx.getSource().getPlayer();
                if (player == null) { ctx.getSource().sendError(blue("Player context required.")); return 0; }
                String msg = PlacementJobRunner.status(player.getUuid());
                ctx.getSource().sendFeedback(() -> blue(msg), false);
                return 1;
            })
            .then(literal("detail").executes(ctx -> {
                ServerPlayerEntity player = ctx.getSource().getPlayer();
                if (player == null) { ctx.getSource().sendError(blue("Player context required.")); return 0; }
                String msg = PlacementJobRunner.statusDetail(player.getUuid());
                ctx.getSource().sendFeedback(() -> blue(msg), false);
                return 1;
            }))
        );
    }

    private static void registerDiag(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladediag")
            .executes(ctx -> runDiagShow(ctx.getSource()))
            .then(literal("show").executes(ctx -> runDiagShow(ctx.getSource())))
            .then(literal("export")
                .executes(ctx -> runDiagExport(ctx.getSource(), ""))
                .then(argument("name", StringArgumentType.word())
                    .executes(ctx -> runDiagExport(ctx.getSource(), StringArgumentType.getString(ctx, "name")))
                )
            )
        );
    }

    private static int runDiagShow(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(blue("Player context required.")); return 0; }
        source.sendFeedback(() -> blue(PlacementJobRunner.diagnostic(player.getUuid())), false);
        return 1;
    }

    private static int runDiagExport(ServerCommandSource source, String name) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(blue("Player context required.")); return 0; }
        PlacementJobRunner.DiagExportResult result =
            PlacementJobRunner.exportDiagnostic(source.getServer(), player.getUuid(), name);
        if (!result.ok()) { source.sendError(blue("[Bladelow] " + result.message())); return 0; }
        source.sendFeedback(() -> blue("[Bladelow] diag " + result.message()), false);
        return 1;
    }

    // -------------------------------------------------------------------------
    // Move / safety / profile
    // -------------------------------------------------------------------------

    private static void registerMove(CommandDispatcher<ServerCommandSource> dispatcher) {
        // Movement/runtime settings are exposed through subcommands rather than
        // one giant setter so the HUD and chat commands can stay aligned.
        dispatcher.register(literal("blademove")
            .then(literal("show").executes(ctx -> { ctx.getSource().sendFeedback(() -> blue("[Bladelow] " + BuildRuntimeSettings.summary()), false); return 1; }))
            .then(literal("on").executes(ctx ->  { BuildRuntimeSettings.setSmartMoveEnabled(true);  ctx.getSource().sendFeedback(() -> blue("[Bladelow] smart move enabled"),  false); return 1; }))
            .then(literal("off").executes(ctx -> { BuildRuntimeSettings.setSmartMoveEnabled(false); ctx.getSource().sendFeedback(() -> blue("[Bladelow] smart move disabled"), false); return 1; }))
            .then(literal("mode").then(argument("type", StringArgumentType.word()).executes(ctx -> {
                String mode = StringArgumentType.getString(ctx, "type");
                if (!BuildRuntimeSettings.setMoveMode(mode)) { ctx.getSource().sendError(blue("[Bladelow] mode must be walk, auto, or teleport")); return 0; }
                ctx.getSource().sendFeedback(() -> blue("[Bladelow] mode set to " + mode), false); return 1;
            })))
            .then(literal("reach").then(argument("distance", DoubleArgumentType.doubleArg(2.0, 8.0)).executes(ctx -> {
                BuildRuntimeSettings.setReachDistance(DoubleArgumentType.getDouble(ctx, "distance"));
                ctx.getSource().sendFeedback(() -> blue("[Bladelow] reach distance set to " + String.format("%.2f", BuildRuntimeSettings.reachDistance())), false); return 1;
            })))
            .then(onOffArg("scheduler",      v -> BuildRuntimeSettings.setTargetSchedulerEnabled(v),  "scheduler"))
            .then(literal("lookahead").then(argument("size", IntegerArgumentType.integer(1, 96)).executes(ctx -> {
                BuildRuntimeSettings.setSchedulerLookahead(IntegerArgumentType.getInteger(ctx, "size"));
                ctx.getSource().sendFeedback(() -> blue("[Bladelow] scheduler lookahead set to " + BuildRuntimeSettings.schedulerLookahead()), false); return 1;
            })))
            .then(onOffArg("defer",          v -> BuildRuntimeSettings.setDeferUnreachableTargets(v), "defer-unreachable"))
            .then(literal("maxdefer").then(argument("count", IntegerArgumentType.integer(0, 8)).executes(ctx -> {
                BuildRuntimeSettings.setMaxTargetDeferrals(IntegerArgumentType.getInteger(ctx, "count"));
                ctx.getSource().sendFeedback(() -> blue("[Bladelow] max deferrals per target set to " + BuildRuntimeSettings.maxTargetDeferrals()), false); return 1;
            })))
            .then(onOffArg("autoresume",     v -> BuildRuntimeSettings.setAutoResumeEnabled(v),       "auto-resume"))
            .then(onOffArg("trace",          v -> BuildRuntimeSettings.setPathTraceEnabled(v),        "path trace"))
            .then(onOffArg("traceparticles", v -> BuildRuntimeSettings.setPathTraceParticles(v),      "path trace particles"))
        );
    }

    private static void registerSafety(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladesafety")
            .then(literal("show").executes(ctx -> { ctx.getSource().sendFeedback(() -> blue("[Bladelow] " + BuildRuntimeSettings.summary()), false); return 1; }))
            .then(onOffArg("strict",  v -> BuildRuntimeSettings.setStrictAirOnly(v),        "strictAir"))
            .then(onOffArg("preview", v -> BuildRuntimeSettings.setPreviewBeforeBuild(v),   "preview-before-build"))
        );
    }

    private static void registerProfile(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladeprofile")
            .then(literal("list").executes(ctx -> {
                List<String> names = BuildProfileStore.list(ctx.getSource().getServer());
                ctx.getSource().sendFeedback(() -> blue("[Bladelow] profiles: " + String.join(", ", names)), false);
                return 1;
            }))
            .then(literal("save").then(argument("name", StringArgumentType.word()).executes(ctx -> {
                String name = StringArgumentType.getString(ctx, "name");
                String status = BuildProfileStore.save(ctx.getSource().getServer(), name);
                ctx.getSource().sendFeedback(() -> blue("[Bladelow] " + status), false);
                return 1;
            })))
            .then(literal("load").then(argument("name", StringArgumentType.word()).executes(ctx -> {
                String name = StringArgumentType.getString(ctx, "name");
                String status = BuildProfileStore.load(ctx.getSource().getServer(), name);
                ctx.getSource().sendFeedback(() -> blue("[Bladelow] " + status + " => " + BuildRuntimeSettings.summary()), false);
                return 1;
            })))
        );
    }

    // -------------------------------------------------------------------------
    // Model
    // -------------------------------------------------------------------------

    private static void registerModel(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("blademodel")
            .then(literal("show").executes(ctx -> {
                String msg = "[Bladelow] " + BladelowLearning.summary() + " " + BuildRuntimeSettings.summary();
                ctx.getSource().sendFeedback(() -> blue(msg), false); return 1;
            }))
            .then(literal("reset").executes(ctx -> {
                BladelowLearning.model().reset();
                String saved = BladelowLearning.save();
                ctx.getSource().sendFeedback(() -> blue("[Bladelow] model reset; " + saved), false); return 1;
            }))
            .then(literal("save").executes(ctx -> {
                ctx.getSource().sendFeedback(() -> blue("[Bladelow] model " + BladelowLearning.save()), false); return 1;
            }))
            .then(literal("intent").executes(ctx -> runIntent(ctx.getSource())))
            .then(literal("load").executes(ctx -> {
                ctx.getSource().sendFeedback(() -> blue("[Bladelow] model " + BladelowLearning.load()), false); return 1;
            }))
            .then(literal("configure")
                .then(argument("threshold", DoubleArgumentType.doubleArg(-2.0, 2.0))
                    .then(argument("learning_rate", DoubleArgumentType.doubleArg(0.001, 1.0)).executes(ctx -> {
                        double t  = DoubleArgumentType.getDouble(ctx, "threshold");
                        double lr = DoubleArgumentType.getDouble(ctx, "learning_rate");
                        BladelowLearning.model().configure(t, lr);
                        String saved = BladelowLearning.save();
                        ctx.getSource().sendFeedback(() -> blue("[Bladelow] model configured: threshold=" + t + " lr=" + lr + "; " + saved), false);
                        return 1;
                    }))
                )
            )
        );
    }

    private static int runIntent(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(blue("Player context required.")); return 0; }
        BlockPos[] bounds = selectionBoundsFromMarkers(player);
        if (bounds == null) {
            source.sendError(blue("[Bladelow] selection is empty; mark an area first"));
            return 0;
        }
        TownPlanner.IntentSuggestion suggestion = TownPlanner.suggestBuildIntent(
            source.getWorld(),
            bounds[0],
            bounds[1],
            TownZoneStore.snapshot(player.getUuid(), source.getWorld().getRegistryKey())
        );
        if (!suggestion.ok()) {
            source.sendError(blue("[Bladelow] " + suggestion.message()));
            return 0;
        }
        source.sendFeedback(() -> blue("[Bladelow] " + suggestion.message()), false);
        return 1;
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private static BlockPos[] selectionBoundsFromMarkers(ServerPlayerEntity player) {
        if (player == null) {
            return null;
        }
        List<BlockPos> points = SelectionState.snapshot(player.getUuid(), player.getEntityWorld().getRegistryKey());
        if (points.isEmpty()) {
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

    /** Build a literal(name) → argument("enabled", word) → on/off handler. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> onOffArg(
        String name, java.util.function.Consumer<Boolean> setter, String label) {
        return literal(name).then(argument("enabled", StringArgumentType.word()).executes(ctx -> {
            String v = StringArgumentType.getString(ctx, "enabled");
            if (!v.equalsIgnoreCase("on") && !v.equalsIgnoreCase("off")) {
                ctx.getSource().sendError(blue("[Bladelow] use on|off")); return 0;
            }
            setter.accept(v.equalsIgnoreCase("on"));
            ctx.getSource().sendFeedback(() -> blue("[Bladelow] " + label + " set to " + v), false);
            return 1;
        }));
    }

    static Text blue(String msg) {
        return Text.literal(msg).formatted(Formatting.AQUA);
    }
}
