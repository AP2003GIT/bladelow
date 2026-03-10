package com.bladelow.auto;

import com.bladelow.builder.BlueprintLibrary;
import com.bladelow.builder.PlacementJobRunner;
import com.bladelow.ml.BladelowLearning;
import com.bladelow.ml.PlacementFeatures;
import com.bladelow.auto.PhasedBuildPlan;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The semi-autonomous build planner.
 *
 * Given a player's goal queue, the AutoPlanner:
 *   1. Picks the next blueprint from BuildGoalQueue
 *   2. Scans terrain near the player via TerrainScanner
 *   3. Checks inventory coverage for the blueprint's materials
 *   4. Scores the plan using PlacementModel
 *   5. Produces a Proposal the player can confirm or skip
 *
 * Nothing is placed until the player runs /bladeauto confirm.
 * On confirm: the proposal is handed to PlacementJobRunner as a normal job.
 * On skip:    the site is blacklisted for this session and a new scan runs.
 * On cancel:  the goal is returned to the front of the queue.
 *
 * Thread-safety: proposals are stored per-player in a ConcurrentHashMap.
 * All world access happens on the caller's thread (server tick thread).
 */
public final class AutoPlanner {

    private AutoPlanner() {
    }

    // -------------------------------------------------------------------------
    // Proposal record
    // -------------------------------------------------------------------------

    public record Proposal(
        UUID playerId,
        String blueprintName,
        BlockPos site,
        int groundY,
        double siteScore,
        double modelScore,
        double materialCoverage,
        String materialSummary,
        List<BlockState> blockStates,
        List<BlockPos> targets
    ) {
        public String summary() {
            return String.format(
                "[Bladelow] AI proposes: %s at (%d, %d, %d)\n" +
                "  site score=%.2f  model confidence=%.2f  materials=%.0f%%\n" +
                "  %s\n" +
                "  Run /bladeauto confirm  or  /bladeauto skip",
                blueprintName,
                site.getX(), groundY, site.getZ(),
                siteScore, modelScore, materialCoverage * 100.0,
                materialSummary
            );
        }
    }

    // -------------------------------------------------------------------------
    // Per-player state
    // -------------------------------------------------------------------------

    private static final Map<UUID, Proposal> PENDING_PROPOSALS = new ConcurrentHashMap<>();

    public static boolean hasProposal(UUID playerId) {
        return PENDING_PROPOSALS.containsKey(playerId);
    }

    public static Proposal getProposal(UUID playerId) {
        return PENDING_PROPOSALS.get(playerId);
    }

    public static void clearProposal(UUID playerId) {
        PENDING_PROPOSALS.remove(playerId);
    }

    // -------------------------------------------------------------------------
    // Planning
    // -------------------------------------------------------------------------

    public enum PlanResult {
        OK, NO_GOALS, NO_BLUEPRINT, NO_SITE, MODEL_REJECTED, ALREADY_BUILDING
    }

    public record PlanOutcome(PlanResult result, String message, Proposal proposal) {
        static PlanOutcome ok(Proposal p) {
            return new PlanOutcome(PlanResult.OK, p.summary(), p);
        }
        static PlanOutcome fail(PlanResult r, String msg) {
            return new PlanOutcome(r, msg, null);
        }
    }

    /**
     * Main entry: produces a Proposal for the player without placing anything.
     * Call this from the tick thread or a command handler.
     */
    public static PlanOutcome plan(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();

        if (PlacementJobRunner.hasActive(playerId) || PlacementJobRunner.hasPending(playerId)) {
            return PlanOutcome.fail(PlanResult.ALREADY_BUILDING,
                "[Bladelow] A build is already active. Finish or cancel it first.");
        }

        // 1. Get next goal
        BuildGoalQueue.Goal goal = BuildGoalQueue.peek(playerId);
        if (goal == null) {
            return PlanOutcome.fail(PlanResult.NO_GOALS,
                "[Bladelow] No build goals. Add one with /bladeauto add <blueprint> <count>");
        }

        // 2. Resolve blueprint info
        BlueprintLibrary.BlueprintInfo info = BlueprintLibrary.info(goal.blueprintName());
        if (info == null) {
            return PlanOutcome.fail(PlanResult.NO_BLUEPRINT,
                "[Bladelow] Blueprint not found: " + goal.blueprintName() +
                ". Run /bladeblueprint reload if you just added it.");
        }

        // 3. Scan for sites
        int footprintW = Math.max(info.plotWidth(), info.width());
        int footprintD = Math.max(info.plotDepth(), info.depth());
        TerrainScanner.Site site = TerrainScanner.bestSite(player, footprintW, footprintD);
        if (site == null) {
            return PlanOutcome.fail(PlanResult.NO_SITE,
                "[Bladelow] No suitable build site found within range. Try a different area.");
        }

        // 4. Resolve blueprint at site
        BlockPos corner = new BlockPos(site.corner().getX(), site.groundY() + 1, site.corner().getZ());
        BlueprintLibrary.BuildPlan buildPlan = BlueprintLibrary.resolveByName(goal.blueprintName(), corner);
        if (!buildPlan.ok()) {
            return PlanOutcome.fail(PlanResult.NO_BLUEPRINT,
                "[Bladelow] Blueprint resolve failed: " + buildPlan.message());
        }

        // 5. Check material coverage
        double coverage = estimateMaterialCoverage(player, buildPlan.blockStates());
        String matSummary = formatMaterialSummary(player, buildPlan.blockStates());

        // 6. Score with PlacementModel
        double modelScore = scoreWithModel(site, buildPlan, coverage);

        // 7. Decide — low confidence surfaces in the proposal but doesn't block
        Proposal proposal = new Proposal(
            playerId,
            goal.blueprintName(),
            site.corner(),
            site.groundY() + 1,
            site.score(),
            modelScore,
            coverage,
            matSummary,
            buildPlan.blockStates(),
            buildPlan.targets()
        );

        PENDING_PROPOSALS.put(playerId, proposal);
        return PlanOutcome.ok(proposal);
    }

    // -------------------------------------------------------------------------
    // Confirm / Skip / Cancel
    // -------------------------------------------------------------------------

    /**
     * Confirm: consume the goal, clear the proposal, start phased build.
     */
    public static String confirm(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        Proposal proposal = PENDING_PROPOSALS.remove(playerId);
        if (proposal == null) {
            return "[Bladelow] No pending proposal. Run /bladeauto plan first.";
        }

        // Consume one from the goal queue
        BuildGoalQueue.consume(playerId);

        // Train model positively — player approved this placement
        trainModel(proposal, true);

        // Start phased build — foundation → walls → roof → details
        return PhasedBuildPlan.start(player,
            proposal.blueprintName(),
            proposal.blockStates(),
            proposal.targets());
    }

    /**
     * Skip: train model negatively, discard proposal, don't consume the goal.
     * The goal stays in the queue; next /bladeauto plan will find a different site.
     */
    public static String skip(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        Proposal proposal = PENDING_PROPOSALS.remove(playerId);
        if (proposal == null) {
            return "[Bladelow] No pending proposal to skip.";
        }
        trainModel(proposal, false);
        return "[Bladelow] Skipped. Run /bladeauto plan to find a different site for: "
            + proposal.blueprintName();
    }

    /**
     * Cancel: discard proposal and put the goal back (don't consume it).
     */
    public static String cancel(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        boolean canceledProposal = PENDING_PROPOSALS.remove(playerId) != null;
        boolean canceledPlan = PhasedBuildPlan.clearPlan(playerId);

        if (canceledProposal && canceledPlan) {
            return "[Bladelow] Proposal cancelled and phased build plan cleared.";
        }
        if (canceledProposal) {
            return "[Bladelow] Proposal cancelled. Goal remains in queue.";
        }
        if (canceledPlan) {
            return "[Bladelow] Active phased build plan cleared.";
        }
        return "[Bladelow] Nothing to cancel: no proposal or phased build plan.";
    }

    // -------------------------------------------------------------------------
    // Material coverage estimation
    // -------------------------------------------------------------------------

    private static double estimateMaterialCoverage(ServerPlayerEntity player, List<BlockState> states) {
        if (states == null || states.isEmpty()) return 1.0;
        if (player.getAbilities().creativeMode) return 1.0;

        Map<net.minecraft.block.Block, Integer> needed = new HashMap<>();
        for (BlockState state : states) {
            if (state == null) continue;
            net.minecraft.block.Block b = state.getBlock();
            if (b.asItem() == Items.AIR) continue;
            needed.merge(b, 1, Integer::sum);
        }
        if (needed.isEmpty()) return 1.0;

        Map<net.minecraft.block.Block, Integer> stock = inventoryStock(player);
        int covered = 0;
        int total = 0;
        for (Map.Entry<net.minecraft.block.Block, Integer> entry : needed.entrySet()) {
            int require = entry.getValue();
            int have    = stock.getOrDefault(entry.getKey(), 0);
            covered += Math.min(require, have);
            total   += require;
        }
        return total == 0 ? 1.0 : (double) covered / total;
    }

    private static String formatMaterialSummary(ServerPlayerEntity player, List<BlockState> states) {
        if (player.getAbilities().creativeMode) return "creative mode (unlimited materials)";
        double coverage = estimateMaterialCoverage(player, states);
        int pct = (int) Math.round(coverage * 100);
        if (pct >= 100) return "all materials available";
        if (pct == 0)   return "no matching materials in inventory";
        return pct + "% materials available (will substitute remainder)";
    }

    private static Map<net.minecraft.block.Block, Integer> inventoryStock(ServerPlayerEntity player) {
        Map<net.minecraft.block.Block, Integer> stock = new HashMap<>();
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (!(item instanceof BlockItem bi)) continue;
            net.minecraft.block.Block block = bi.getBlock();
            if (block.asItem() == Items.AIR) continue;
            stock.merge(block, stack.getCount(), Integer::sum);
        }
        return stock;
    }

    // -------------------------------------------------------------------------
    // Model scoring + training
    // -------------------------------------------------------------------------

    private static double scoreWithModel(TerrainScanner.Site site,
                                          BlueprintLibrary.BuildPlan plan,
                                          double coverage) {
        // Map our signals onto the existing PlacementFeatures schema:
        //   bias       = 1.0 (always present)
        //   replaceable = site score (higher = better terrain)
        //   support     = material coverage
        //   distance    = inverted — we repurpose as "model confidence in plan size"
        //                 smaller blueprints = higher confidence
        int targets = plan.targets() == null ? 0 : plan.targets().size();
        double sizeConfidence = targets < 50 ? 1.0 : Math.max(0.1, 1.0 - targets / 2000.0);

        PlacementFeatures features = new PlacementFeatures(
            1.0,
            site.score(),
            coverage,
            sizeConfidence
        );
        return BladelowLearning.model().score(features);
    }

    private static void trainModel(Proposal proposal, boolean approved) {
        int targets = proposal.targets() == null ? 0 : proposal.targets().size();
        double sizeConfidence = targets < 50 ? 1.0 : Math.max(0.1, 1.0 - targets / 2000.0);

        PlacementFeatures features = new PlacementFeatures(
            1.0,
            proposal.siteScore(),
            proposal.materialCoverage(),
            sizeConfidence
        );
        BladelowLearning.model().train(features, approved);
        BladelowLearning.save();
    }
}
