package com.bladelow.command;

import com.bladelow.builder.BuildRuntimeSettings;
import com.bladelow.builder.BuildTaskGraph;
import com.bladelow.builder.PlacementJob;
import com.bladelow.builder.PlacementJobRunner;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.List;

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

        BuildTaskGraph graph = BuildTaskGraph.fromPlacements(tag, mat.blockStates(), targets, player);
        BuildTaskGraph.OrderedPlacements plan = graph.orderedPlacements();
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

        String msg = "[Bladelow] Build queued: " + plan.targets().size() + " blocks"
            + (previewMode ? " | Preview ready" : " | Progress appears above the hotbar")
            + (replaced ? " | Previous build replaced" : "");
        source.sendFeedback(() -> blueText(msg), false);
        return 1;
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
