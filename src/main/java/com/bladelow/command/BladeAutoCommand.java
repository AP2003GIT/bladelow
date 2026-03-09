package com.bladelow.command;

import com.bladelow.auto.AutoPlanner;
import com.bladelow.auto.BuildGoalQueue;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * /bladeauto — semi-autonomous AI build planner.
 *
 * Usage:
 *   /bladeauto add <blueprint> [count]   — add to goal queue
 *   /bladeauto goals                     — show current goal queue
 *   /bladeauto plan                      — AI scans terrain and proposes a build
 *   /bladeauto confirm                   — accept proposal, start building
 *   /bladeauto skip                      — reject site, find another (trains model)
 *   /bladeauto cancel                    — discard proposal, keep goal in queue
 *   /bladeauto clear                     — clear all goals
 *   /bladeauto remove <index>            — remove a goal by queue position
 */
public final class BladeAutoCommand {

    private BladeAutoCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladeauto")

            // --- add ---
            .then(literal("add")
                .then(argument("blueprint", StringArgumentType.word())
                    .executes(ctx -> runAdd(ctx.getSource(),
                        StringArgumentType.getString(ctx, "blueprint"), 1))
                    .then(argument("count", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> runAdd(ctx.getSource(),
                            StringArgumentType.getString(ctx, "blueprint"),
                            IntegerArgumentType.getInteger(ctx, "count")))
                    )
                )
            )

            // --- goals ---
            .then(literal("goals")
                .executes(ctx -> runGoals(ctx.getSource()))
            )

            // --- plan ---
            .then(literal("plan")
                .executes(ctx -> runPlan(ctx.getSource()))
            )

            // --- confirm ---
            .then(literal("confirm")
                .executes(ctx -> runConfirm(ctx.getSource()))
            )

            // --- skip ---
            .then(literal("skip")
                .executes(ctx -> runSkip(ctx.getSource()))
            )

            // --- cancel ---
            .then(literal("cancel")
                .executes(ctx -> runCancel(ctx.getSource()))
            )

            // --- clear ---
            .then(literal("clear")
                .executes(ctx -> runClear(ctx.getSource()))
            )

            // --- remove ---
            .then(literal("remove")
                .then(argument("index", IntegerArgumentType.integer(1, 64))
                    .executes(ctx -> runRemove(ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "index")))
                )
            )
        );
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    private static int runAdd(ServerCommandSource source, String blueprint, int count) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(blue("Player context required.")); return 0; }
        BuildGoalQueue.add(player.getUuid(), blueprint, count);
        source.sendFeedback(() -> blue(
            "[Bladelow] Added goal: " + blueprint + " x" + count
            + ". Queue has " + BuildGoalQueue.snapshot(player.getUuid()).size() + " goal(s)."
            + " Run /bladeauto plan when ready."
        ), false);
        return 1;
    }

    private static int runGoals(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(blue("Player context required.")); return 0; }
        UUID playerId = player.getUuid();
        List<BuildGoalQueue.Goal> goals = BuildGoalQueue.snapshot(playerId);
        if (goals.isEmpty()) {
            source.sendFeedback(() -> blue("[Bladelow] No build goals. Use /bladeauto add <blueprint> [count]"), false);
            return 1;
        }
        source.sendFeedback(() -> blue("[Bladelow] Build queue (" + goals.size() + " goal(s)):"), false);
        for (int i = 0; i < goals.size(); i++) {
            BuildGoalQueue.Goal g = goals.get(i);
            int idx = i + 1;
            source.sendFeedback(() -> blue("  [" + idx + "] " + g.blueprintName() + " x" + g.remaining()), false);
        }
        if (AutoPlanner.hasProposal(playerId)) {
            AutoPlanner.Proposal p = AutoPlanner.getProposal(playerId);
            source.sendFeedback(() -> blue("[Bladelow] Pending proposal: "
                + p.blueprintName() + " at ("
                + p.site().getX() + ", " + p.groundY() + ", " + p.site().getZ() + ")"
                + " — confirm or skip"), false);
        }
        return 1;
    }

    private static int runPlan(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(blue("Player context required.")); return 0; }

        // Clear any stale proposal first
        AutoPlanner.clearProposal(player.getUuid());

        source.sendFeedback(() -> blue("[Bladelow] Scanning terrain and planning..."), false);

        AutoPlanner.PlanOutcome outcome = AutoPlanner.plan(player);
        if (outcome.result() == AutoPlanner.PlanResult.OK) {
            // Multi-line proposal — send each line separately so chat wraps nicely
            for (String line : outcome.message().split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    source.sendFeedback(() -> blue(trimmed), false);
                }
            }
        } else {
            source.sendError(blue(outcome.message()));
        }
        return outcome.result() == AutoPlanner.PlanResult.OK ? 1 : 0;
    }

    private static int runConfirm(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(blue("Player context required.")); return 0; }
        String result = AutoPlanner.confirm(player);
        boolean ok = !result.contains("No pending");
        if (ok) source.sendFeedback(() -> blue(result), false);
        else    source.sendError(blue(result));
        return ok ? 1 : 0;
    }

    private static int runSkip(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(blue("Player context required.")); return 0; }
        String result = AutoPlanner.skip(player);
        source.sendFeedback(() -> blue(result), false);
        return 1;
    }

    private static int runCancel(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(blue("Player context required.")); return 0; }
        String result = AutoPlanner.cancel(player);
        source.sendFeedback(() -> blue(result), false);
        return 1;
    }

    private static int runClear(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(blue("Player context required.")); return 0; }
        AutoPlanner.clearProposal(player.getUuid());
        BuildGoalQueue.clear(player.getUuid());
        source.sendFeedback(() -> blue("[Bladelow] All goals cleared."), false);
        return 1;
    }

    private static int runRemove(ServerCommandSource source, int index) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(blue("Player context required.")); return 0; }
        boolean removed = BuildGoalQueue.remove(player.getUuid(), index);
        if (!removed) {
            source.sendError(blue("[Bladelow] No goal at index " + index + "."));
            return 0;
        }
        source.sendFeedback(() -> blue("[Bladelow] Removed goal #" + index + "."), false);
        return 1;
    }

    // -------------------------------------------------------------------------

    private static Text blue(String msg) {
        return Text.literal(msg).formatted(Formatting.AQUA);
    }
}
