package com.bladelow.auto;

import com.bladelow.builder.BuildRuntimeSettings;
import com.bladelow.builder.PlacementJob;
import com.bladelow.builder.PlacementJobRunner;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Manages phased execution of a build plan.
 *
 * After /bladeauto confirm, the build is split into phases (FOUNDATION →
 * WALLS → ROOF → DETAILS). Each phase is submitted as a normal PlacementJob.
 * When a phase completes, PhasedBuildPlan automatically queues the next one
 * and notifies the player.
 *
 * Phase chaining is triggered from PlacementJobRunner's completion callback.
 * The player can still use /bladecancel at any time to stop mid-build.
 */
public final class PhasedBuildPlan {

    private PhasedBuildPlan() {
    }

    // -------------------------------------------------------------------------
    // Active plan registry (one per player at most)
    // -------------------------------------------------------------------------

    private record ActivePlan(
        UUID playerId,
        RegistryKey<World> worldKey,
        String blueprintName,
        List<BuildPhase.PhaseSlice> phases,
        int currentPhase
    ) {
        ActivePlan advance() {
            return new ActivePlan(playerId, worldKey, blueprintName, phases, currentPhase + 1);
        }

        boolean hasNextPhase() {
            return currentPhase + 1 < phases.size();
        }

        BuildPhase.PhaseSlice current() {
            return phases.get(currentPhase);
        }
    }

    private static final ConcurrentMap<UUID, ActivePlan> ACTIVE_PLANS = new ConcurrentHashMap<>();

    public static boolean hasActivePlan(UUID playerId) {
        return ACTIVE_PLANS.containsKey(playerId);
    }

    public static String planSummary(UUID playerId) {
        ActivePlan plan = ACTIVE_PLANS.get(playerId);
        if (plan == null) return "no active phased build";
        BuildPhase.PhaseSlice current = plan.current();
        return plan.blueprintName()
            + " phase " + (plan.currentPhase() + 1) + "/" + plan.phases().size()
            + " [" + current.phase().label + "]"
            + " targets=" + current.size();
    }

    // -------------------------------------------------------------------------
    // Start a phased build
    // -------------------------------------------------------------------------

    /**
     * Split the proposal into phases and queue phase 1 immediately.
     * Called from AutoPlanner.confirm().
     */
    public static String start(ServerPlayerEntity player,
                                String blueprintName,
                                List<BlockState> blockStates,
                                List<BlockPos> targets) {
        UUID playerId = player.getUuid();
        if (!(player.getEntityWorld() instanceof ServerWorld serverWorld)) {
            return "[Bladelow] Unable to resolve server world for phased build.";
        }
        MinecraftServer server = serverWorld.getServer();
        if (server == null) {
            return "[Bladelow] Unable to resolve server for phased build.";
        }
        RegistryKey<World> worldKey = serverWorld.getRegistryKey();

        List<BuildPhase.PhaseSlice> phases = BuildPhase.split(blockStates, targets);
        if (phases.isEmpty()) {
            return "[Bladelow] No phases found in blueprint — nothing to build.";
        }

        // Single-phase builds: just queue directly with no chaining overhead
        if (phases.size() == 1) {
            queuePhase(server, playerId, worldKey, phases.get(0), blueprintName, 1, 1);
            return formatStartMessage(blueprintName, phases, 0);
        }

        ActivePlan plan = new ActivePlan(playerId, worldKey, blueprintName, phases, 0);
        ACTIVE_PLANS.put(playerId, plan);
        queuePhase(server, playerId, worldKey, phases.get(0), blueprintName, 1, phases.size());
        return formatStartMessage(blueprintName, phases, 0);
    }

    // -------------------------------------------------------------------------
    // Phase completion callback — called by PlacementJobRunner when a job ends
    // -------------------------------------------------------------------------

    /**
     * Should be called when a job tagged "auto:*" completes.
     * Advances to the next phase if one exists.
     *
     * Returns true if a next phase was queued, false if the build is done.
     */
    public static boolean onPhaseComplete(MinecraftServer server, UUID playerId) {
        ActivePlan plan = ACTIVE_PLANS.get(playerId);
        if (plan == null) return false;

        if (!plan.hasNextPhase()) {
            ACTIVE_PLANS.remove(playerId);
            // Notify player on the server thread
            server.execute(() -> notifyPlayer(server, playerId,
                "[Bladelow] ✓ Build complete: " + plan.blueprintName()
                + " — all " + plan.phases().size() + " phases done!"));
            return false;
        }

        ActivePlan next = plan.advance();
        ACTIVE_PLANS.put(playerId, next);

        BuildPhase.PhaseSlice nextSlice = next.current();
        int phaseNum = next.currentPhase() + 1;

        server.execute(() -> {
            notifyPlayer(server, playerId,
                "[Bladelow] Phase " + phaseNum + "/" + plan.phases().size()
                + " starting: " + nextSlice.phase().label
                + " (" + nextSlice.size() + " blocks)");
            queuePhase(server, playerId, plan.worldKey(), nextSlice,
                plan.blueprintName(), phaseNum, plan.phases().size());
        });
        return true;
    }

    /**
     * Cancel the active phased plan (does not cancel the running job —
     * use /bladecancel for that).
     */
    public static String cancelPlan(UUID playerId) {
        ActivePlan plan = ACTIVE_PLANS.remove(playerId);
        if (plan == null) return "[Bladelow] No active phased build to cancel.";
        return "[Bladelow] Phased build cancelled after phase "
            + (plan.currentPhase() + 1) + "/" + plan.phases().size()
            + " of " + plan.blueprintName() + ".";
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void queuePhase(MinecraftServer server, UUID playerId,
                                    RegistryKey<World> worldKey,
                                    BuildPhase.PhaseSlice slice,
                                    String blueprintName,
                                    int phaseNum, int totalPhases) {
        String tag = "auto:" + blueprintName + ":phase" + phaseNum + "of" + totalPhases
            + ":" + slice.phase().name().toLowerCase();

        PlacementJob job = new PlacementJob(
            playerId,
            worldKey,
            slice.blockStates(),
            slice.targets(),
            tag,
            BuildRuntimeSettings.snapshot()
        );
        PlacementJobRunner.queueOrPreview(server, job);
    }

    private static String formatStartMessage(String blueprintName,
                                              List<BuildPhase.PhaseSlice> phases,
                                              int startIndex) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Bladelow] Starting phased build: ").append(blueprintName);
        sb.append(" — ").append(phases.size()).append(" phase(s)\n");
        for (int i = 0; i < phases.size(); i++) {
            BuildPhase.PhaseSlice s = phases.get(i);
            sb.append("  ").append(i + 1).append(". ")
              .append(s.phase().label)
              .append(" (").append(s.size()).append(" blocks)");
            if (i == startIndex) sb.append(" ← starting now");
            sb.append("\n");
        }
        sb.append("Each phase starts automatically when the previous one completes.");
        return sb.toString();
    }

    private static void notifyPlayer(MinecraftServer server, UUID playerId, String message) {
        var playerList = server.getPlayerManager();
        var player = playerList.getPlayer(playerId);
        if (player != null) {
            player.sendMessage(Text.literal(message).formatted(Formatting.AQUA), false);
        }
    }
}
