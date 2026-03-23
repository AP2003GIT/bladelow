package com.bladelow.command;

import com.bladelow.builder.BuildRuntimeSettings;
import com.bladelow.builder.PlacementJob;
import com.bladelow.builder.PlacementJobRunner;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Orchestrates the full placement pipeline:
 *   palette assignment → material resolution → dependency ordering → job queue.
 *
 * Shared placement entrypoint used by the HUD action service and any future
 * server-side build orchestrators.
 */
public final class PlacementPipeline {

    private PlacementPipeline() {
    }

    // -------------------------------------------------------------------------
    // Public entry points
    // -------------------------------------------------------------------------

    /** Run placement: assign palette, resolve materials, queue job. */
    public static int run(ServerCommandSource source, ServerPlayerEntity player,
                          List<Block> blocks, List<BlockPos> targets, String tag) {
        List<Block> perTarget = PaletteAssigner.assign(blocks, targets, tag);
        return queue(source, player, PaletteAssigner.defaultStates(perTarget), targets, tag, false);
    }

    /** Queue a job with explicit block states (used by blueprint commands). */
    public static int queue(ServerCommandSource source, ServerPlayerEntity player,
                            List<BlockState> states, List<BlockPos> targets, String tag) {
        return queue(source, player, states, targets, tag, false);
    }

    /** Queue a job, optionally forcing preview mode. */
    public static int queue(ServerCommandSource source, ServerPlayerEntity player,
                            List<BlockState> states, List<BlockPos> targets, String tag,
                            boolean forcePreview) {
        MaterialResolver.Resolution mat = MaterialResolver.resolve(player, states);
        if (!mat.summary().isBlank()) {
            source.sendFeedback(() -> blueText("[Bladelow] " + mat.summary()), false);
        }

        ExecutionPlan plan = dependencyOrder(player, mat.blockStates(), targets, tag);
        BuildRuntimeSettings.Snapshot snapshot = BuildRuntimeSettings.snapshot();
        if (forcePreview && !snapshot.previewBeforeBuild()) {
            snapshot = withPreview(snapshot);
        }

        PlacementJob job = new PlacementJob(
            player.getUuid(),
            source.getWorld().getRegistryKey(),
            plan.states(),
            plan.targets(),
            tag,
            snapshot
        );

        boolean previewMode = snapshot.previewBeforeBuild();
        boolean replaced = previewMode
            ? PlacementJobRunner.hasPending(player.getUuid())
            : PlacementJobRunner.hasActive(player.getUuid());
        PlacementJobRunner.queueOrPreview(source.getServer(), job);

        String msg = "[Bladelow] queued " + tag
            + " targets=" + plan.targets().size()
            + " blocks=" + plan.states().size()
            + " feasible=" + String.format(Locale.ROOT, "%.1f", mat.feasibilityPercent()) + "%"
            + " deps=" + plan.dependencyEdges()
            + " order=" + (plan.dependencyOrdered() ? "support-first" : "path-first")
            + " " + snapshot.summary()
            + (previewMode ? " [pending]" : " [active]")
            + (replaced ? " (replaced previous pending job)" : "");
        source.sendFeedback(() -> blueText(msg), false);
        return 1;
    }

    // -------------------------------------------------------------------------
    // Dependency-aware ordering
    // -------------------------------------------------------------------------

    private record ExecutionPlan(List<BlockState> states, List<BlockPos> targets,
                                  int dependencyEdges, boolean dependencyOrdered) {
    }

    private static ExecutionPlan dependencyOrder(ServerPlayerEntity player,
                                                  List<BlockState> states,
                                                  List<BlockPos> targets,
                                                  String tag) {
        if (states == null || targets == null
                || states.size() != targets.size() || targets.isEmpty()) {
            return new ExecutionPlan(
                states == null ? List.of() : states,
                targets == null ? List.of() : targets, 0, false);
        }
        if (targets.size() == 1) {
            return new ExecutionPlan(states, targets, 0, false);
        }

        Map<BlockPos, Integer> indexByPos = new HashMap<>(targets.size() * 2);
        for (int i = 0; i < targets.size(); i++) indexByPos.putIfAbsent(targets.get(i), i);

        int edges = 0;
        for (BlockPos pos : targets) {
            if (indexByPos.containsKey(pos.down())) edges++;
        }
        if (edges <= 0) return new ExecutionPlan(states, targets, 0, false);

        double px = player.getX(), py = player.getY(), pz = player.getZ();
        List<Integer> order = new ArrayList<>(targets.size());
        for (int i = 0; i < targets.size(); i++) order.add(i);
        Collections.sort(order, Comparator
            .comparingInt((Integer i) -> targets.get(i).getY())
            .thenComparingDouble(i -> targets.get(i).getSquaredDistance(px, py, pz))
            .thenComparingInt(i -> targets.get(i).getX())
            .thenComparingInt(i -> targets.get(i).getZ()));

        List<BlockState> orderedStates = new ArrayList<>(states.size());
        List<BlockPos> orderedTargets = new ArrayList<>(targets.size());
        for (int idx : order) {
            orderedStates.add(states.get(idx));
            orderedTargets.add(targets.get(idx));
        }
        return new ExecutionPlan(orderedStates, orderedTargets, edges, true);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static BuildRuntimeSettings.Snapshot withPreview(BuildRuntimeSettings.Snapshot s) {
        return new BuildRuntimeSettings.Snapshot(
            s.smartMoveEnabled(), s.reachDistance(), s.moveMode(),
            s.strictAirOnly(), true,
            s.targetSchedulerEnabled(), s.schedulerLookahead(),
            s.deferUnreachableTargets(), s.maxTargetDeferrals(),
            s.autoResumeEnabled(), s.pathTraceEnabled(), s.pathTraceParticles()
        );
    }

    static Text blueText(String msg) {
        return Text.literal(msg).formatted(Formatting.AQUA);
    }
}
