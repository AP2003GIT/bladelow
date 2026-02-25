package com.bladelow.builder;

import com.bladelow.ml.BladelowLearning;
import com.bladelow.ml.PlacementFeatureExtractor;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlacementJobRunner {
    private static final Map<UUID, PlacementJob> JOBS = new ConcurrentHashMap<>();
    private static final Map<UUID, PlacementJob> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, DiagSnapshot> LAST_DIAG = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> AUTO_RESUME_READY_AT = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<Long, Integer>> TARGET_PRESSURE = new ConcurrentHashMap<>();
    private static final Map<UUID, StuckState> STUCK = new ConcurrentHashMap<>();
    private static final Map<UUID, ProgressWatchdog> WATCHDOG = new ConcurrentHashMap<>();
    private static final int MAX_RETRIES_PER_TARGET = 3;
    private static final int MAX_BLOCKED_RETRIES = 2;
    private static final float MAX_AUTO_CLEAR_HARDNESS = 1.5f;
    private static final long AUTO_RESUME_DELAY_MS = 1_400L;
    private static final int WATCHDOG_STALL_TICKS = 130;
    private static final int WATCHDOG_COOLDOWN_TICKS = 60;
    private static final int TARGET_PRESSURE_DEFER_THRESHOLD = 4;
    private static final int TARGET_PRESSURE_SKIP_THRESHOLD = 9;
    private static final int TARGET_PRESSURE_MAX_TRACKED = 768;
    private static final int TARGET_PRESSURE_MAX_VALUE = 30;
    private static final int TARGET_PRESSURE_DECAY_TICKS = 120;
    private static final int STUCK_TICKS_THRESHOLD = 26;
    private static final int STUCK_COOLDOWN_TICKS = 18;
    private static final double STUCK_PROGRESS_EPS = 0.08;
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HUMAN_TS = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

    private PlacementJobRunner() {
    }

    private static boolean submit(PlacementJob job) {
        return JOBS.put(job.playerId(), job) != null;
    }

    public static void saveCheckpoint(MinecraftServer server) {
        PlacementCheckpointStore.save(
            server,
            new ArrayList<>(JOBS.values()),
            new ArrayList<>(PENDING.values())
        );
    }

    public static int restoreFromCheckpoint(MinecraftServer server) {
        PlacementCheckpointStore.LoadResult result = PlacementCheckpointStore.load(server);
        JOBS.clear();
        PENDING.clear();

        // Keep restored jobs paused/pending by default so they do not run before the player returns.
        for (PlacementJob job : result.active()) {
            PENDING.put(job.playerId(), job);
        }
        for (PlacementJob job : result.pending()) {
            PENDING.put(job.playerId(), job);
        }
        saveCheckpoint(server);
        return result.restoredCount();
    }

    public static void queueOrPreview(MinecraftServer server, PlacementJob job) {
        AUTO_RESUME_READY_AT.remove(job.playerId());
        TARGET_PRESSURE.remove(job.playerId());
        WATCHDOG.remove(job.playerId());
        if (!job.runtimeSettings().previewBeforeBuild()) {
            PENDING.remove(job.playerId());
            submit(job);
            saveCheckpoint(server);
            return;
        }
        PENDING.put(job.playerId(), job);
        saveCheckpoint(server);
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(job.playerId());
        if (player != null) {
            player.sendMessage(blueText("[Bladelow] preview ready. Use /bladeconfirm to start or /bladecancel to discard."), false);
            preview(job, server, player);
        }
    }

    public static boolean cancel(MinecraftServer server, UUID playerId) {
        PlacementJob activeJob = JOBS.remove(playerId);
        PlacementJob pendingJob = PENDING.remove(playerId);
        AUTO_RESUME_READY_AT.remove(playerId);
        TARGET_PRESSURE.remove(playerId);
        STUCK.remove(playerId);
        WATCHDOG.remove(playerId);
        BuildNavigation.invalidateCachedPath(playerId);
        BuildNavigation.consumeBlacklistHits(playerId);
        boolean active = activeJob != null;
        boolean pending = pendingJob != null;
        boolean changed = active || pending;
        if (activeJob != null) {
            rememberSnapshot(playerId, "canceled-active", activeJob, detailSummary("canceled", activeJob));
        } else if (pendingJob != null) {
            rememberSnapshot(playerId, "canceled-pending", pendingJob, detailSummary("canceled", pendingJob));
        }
        if (changed) {
            saveCheckpoint(server);
        }
        return changed;
    }

    public static boolean pause(MinecraftServer server, UUID playerId) {
        PlacementJob active = JOBS.remove(playerId);
        if (active == null) {
            return false;
        }
        AUTO_RESUME_READY_AT.remove(playerId);
        TARGET_PRESSURE.remove(playerId);
        STUCK.remove(playerId);
        WATCHDOG.remove(playerId);
        BuildNavigation.invalidateCachedPath(playerId);
        PENDING.put(playerId, active);
        saveCheckpoint(server);
        return true;
    }

    public static boolean resume(MinecraftServer server, UUID playerId) {
        if (JOBS.containsKey(playerId)) {
            return false;
        }
        PlacementJob pending = PENDING.remove(playerId);
        if (pending == null) {
            return false;
        }
        AUTO_RESUME_READY_AT.remove(playerId);
        TARGET_PRESSURE.remove(playerId);
        STUCK.remove(playerId);
        WATCHDOG.remove(playerId);
        BuildNavigation.invalidateCachedPath(playerId);
        JOBS.put(playerId, pending);
        saveCheckpoint(server);
        return true;
    }

    public static boolean confirm(MinecraftServer server, UUID playerId) {
        PlacementJob pending = PENDING.remove(playerId);
        if (pending == null) {
            return false;
        }
        AUTO_RESUME_READY_AT.remove(playerId);
        TARGET_PRESSURE.remove(playerId);
        STUCK.remove(playerId);
        WATCHDOG.remove(playerId);
        BuildNavigation.invalidateCachedPath(playerId);
        JOBS.put(playerId, pending);
        saveCheckpoint(server);
        return true;
    }

    public static boolean previewPending(UUID playerId, MinecraftServer server) {
        PlacementJob pending = PENDING.get(playerId);
        if (pending == null) {
            return false;
        }
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (player == null) {
            return false;
        }
        preview(pending, server, player);
        return true;
    }

    public static String status(UUID playerId) {
        PlacementJob job = JOBS.get(playerId);
        if (job != null) {
            return job.progressSummary();
        }
        PlacementJob pending = PENDING.get(playerId);
        if (pending != null) {
            return "[Bladelow] pending preview " + pending.progressSummary().replace(" progress 0/", " ");
        }
        return "[Bladelow] no active build";
    }

    public static String statusDetail(UUID playerId) {
        PlacementJob active = JOBS.get(playerId);
        if (active != null) {
            return detailSummary("active", active);
        }
        PlacementJob pending = PENDING.get(playerId);
        if (pending != null) {
            return detailSummary("pending", pending);
        }
        return "[Bladelow] no active build";
    }

    public static String diagnostic(UUID playerId) {
        DiagSnapshot snapshot = snapshotFor(playerId);
        if (snapshot == null) {
            return "[Bladelow] no diagnostic snapshot available yet";
        }
        return "[Bladelow] diag phase=" + snapshot.phase()
            + " at=" + HUMAN_TS.format(snapshot.createdAt())
            + " | " + snapshot.summary()
            + " top=noReach:" + snapshot.metrics().noReach()
            + " stuck:" + snapshot.metrics().stuckEvents()
            + " blocked:" + snapshot.metrics().blocked()
            + " failed:" + snapshot.metrics().failed();
    }

    public static DiagExportResult exportDiagnostic(MinecraftServer server, UUID playerId, String label) {
        DiagSnapshot snapshot = snapshotFor(playerId);
        if (snapshot == null) {
            return DiagExportResult.error("no diagnostic snapshot available yet");
        }

        Path dir = server.getRunDirectory().resolve("config").resolve("bladelow").resolve("diag");
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            return DiagExportResult.error("failed to create diag dir: " + ex.getMessage());
        }

        String safeLabel = sanitizeLabel(label);
        String base = "diag-" + playerId.toString().substring(0, 8) + "-" + FILE_TS.format(snapshot.createdAt());
        if (!safeLabel.isBlank()) {
            base = base + "-" + safeLabel;
        }
        Path out = dir.resolve(base + ".txt");
        String text = renderDiagnosticText(playerId, snapshot);
        try {
            Files.writeString(out, text);
            return DiagExportResult.ok("saved to " + out);
        } catch (IOException ex) {
            return DiagExportResult.error("failed to write diag file: " + ex.getMessage());
        }
    }

    public static boolean hasActive(UUID playerId) {
        return JOBS.containsKey(playerId);
    }

    public static boolean hasPending(UUID playerId) {
        return PENDING.containsKey(playerId);
    }

    public static void tick(MinecraftServer server) {
        boolean stateChanged = processPendingAutoResume(server);
        Iterator<Map.Entry<UUID, PlacementJob>> it = JOBS.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<UUID, PlacementJob> entry = it.next();
            PlacementJob job = entry.getValue();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(job.playerId());
            if (player == null) {
                PENDING.put(job.playerId(), job);
                it.remove();
                AUTO_RESUME_READY_AT.remove(job.playerId());
                TARGET_PRESSURE.remove(job.playerId());
                STUCK.remove(job.playerId());
                WATCHDOG.remove(job.playerId());
                BuildNavigation.invalidateCachedPath(job.playerId());
                BuildNavigation.consumeBlacklistHits(job.playerId());
                stateChanged = true;
                continue;
            }

            if (job.isComplete()) {
                finishJob(player, job);
                it.remove();
                AUTO_RESUME_READY_AT.remove(job.playerId());
                TARGET_PRESSURE.remove(job.playerId());
                STUCK.remove(job.playerId());
                WATCHDOG.remove(job.playerId());
                BuildNavigation.invalidateCachedPath(job.playerId());
                BuildNavigation.consumeBlacklistHits(job.playerId());
                stateChanged = true;
                continue;
            }
            job.tick();

            var world = server.getWorld(job.worldKey());
            if (world == null) {
                player.sendMessage(blueText("[Bladelow] Target world unavailable. Build canceled."), false);
                rememberSnapshot(job.playerId(), "aborted-world", job, detailSummary("aborted", job));
                it.remove();
                AUTO_RESUME_READY_AT.remove(job.playerId());
                TARGET_PRESSURE.remove(job.playerId());
                STUCK.remove(job.playerId());
                WATCHDOG.remove(job.playerId());
                BuildNavigation.invalidateCachedPath(job.playerId());
                BuildNavigation.consumeBlacklistHits(job.playerId());
                stateChanged = true;
                continue;
            }

            if (job.runtimeSettings().targetSchedulerEnabled() && job.currentNode() == PlacementJob.TaskNode.MOVE) {
                job.selectBestTargetNear(player.getBlockPos(), job.runtimeSettings().schedulerLookahead());
            }
            runTaskNode(world, player, job);
            int blacklistHits = BuildNavigation.consumeBlacklistHits(job.playerId());
            if (blacklistHits > 0) {
                job.recordBlacklistHits(blacklistHits);
            }
            trackStuckAndRecover(world, player, job);
            runProgressWatchdog(world, player, job, server.getTicks());

            if (job.shouldReportProgress()) {
                player.sendMessage(blueText(job.progressSummary()), false);
            }

            if (job.isComplete()) {
                finishJob(player, job);
                it.remove();
                AUTO_RESUME_READY_AT.remove(job.playerId());
                TARGET_PRESSURE.remove(job.playerId());
                STUCK.remove(job.playerId());
                WATCHDOG.remove(job.playerId());
                BuildNavigation.invalidateCachedPath(job.playerId());
                BuildNavigation.consumeBlacklistHits(job.playerId());
                stateChanged = true;
            }
        }

        if (stateChanged || ((!JOBS.isEmpty() || !PENDING.isEmpty()) && server.getTicks() % 40 == 0)) {
            saveCheckpoint(server);
        }
        if (server.getTicks() % TARGET_PRESSURE_DECAY_TICKS == 0) {
            decayTargetPressure();
        }
    }

    private static void runTaskNode(net.minecraft.server.world.ServerWorld world, ServerPlayerEntity player, PlacementJob job) {
        if (job.isComplete()) {
            return;
        }
        switch (job.currentNode()) {
            case MOVE -> nodeMove(world, player, job);
            case ALIGN -> nodeAlign(world, player, job);
            case PLACE -> nodePlace(world, player, job);
            case RECOVER -> nodeRecover(world, player, job);
        }
    }

    private static boolean processPendingAutoResume(MinecraftServer server) {
        long now = System.currentTimeMillis();
        boolean changed = false;
        Iterator<Map.Entry<UUID, PlacementJob>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PlacementJob> entry = it.next();
            UUID playerId = entry.getKey();
            PlacementJob job = entry.getValue();
            if (job == null) {
                it.remove();
                AUTO_RESUME_READY_AT.remove(playerId);
                TARGET_PRESSURE.remove(playerId);
                changed = true;
                continue;
            }
            if (job.runtimeSettings().previewBeforeBuild() || !job.runtimeSettings().autoResumeEnabled()) {
                AUTO_RESUME_READY_AT.remove(playerId);
                continue;
            }
            if (JOBS.containsKey(playerId)) {
                it.remove();
                AUTO_RESUME_READY_AT.remove(playerId);
                TARGET_PRESSURE.remove(playerId);
                changed = true;
                continue;
            }

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null || server.getWorld(job.worldKey()) == null) {
                AUTO_RESUME_READY_AT.remove(playerId);
                continue;
            }

            long readyAt = AUTO_RESUME_READY_AT.getOrDefault(playerId, -1L);
            if (readyAt <= 0L) {
                AUTO_RESUME_READY_AT.put(playerId, now + AUTO_RESUME_DELAY_MS);
                player.sendMessage(blueText("[Bladelow] pending build detected. Auto-resume armed..."), false);
                continue;
            }
            if (now < readyAt) {
                continue;
            }

            it.remove();
            AUTO_RESUME_READY_AT.remove(playerId);
            TARGET_PRESSURE.remove(playerId);
            STUCK.remove(playerId);
            WATCHDOG.remove(playerId);
            BuildNavigation.invalidateCachedPath(playerId);
            JOBS.put(playerId, job);
            player.sendMessage(blueText("[Bladelow] auto-resumed pending build."), false);
            changed = true;
        }
        return changed;
    }

    private static void runProgressWatchdog(
        net.minecraft.server.world.ServerWorld world,
        ServerPlayerEntity player,
        PlacementJob job,
        long serverTick
    ) {
        if (job == null || job.isComplete()) {
            if (job != null) {
                WATCHDOG.remove(job.playerId());
            }
            return;
        }

        ProgressWatchdog state = WATCHDOG.computeIfAbsent(job.playerId(), ignored -> ProgressWatchdog.fresh(job, serverTick));
        if (state.observeProgress(job, serverTick)) {
            return;
        }
        if (serverTick - state.lastProgressTick() < WATCHDOG_STALL_TICKS) {
            return;
        }
        if (serverTick - state.lastTriggerTick() < WATCHDOG_COOLDOWN_TICKS) {
            return;
        }

        BlockPos target = job.currentTarget();
        long stalled = serverTick - state.lastProgressTick();
        state.markTriggered(serverTick);
        state.bumpTriggerCount();
        int pressure = addTargetPressure(job.playerId(), target, 2);

        BuildNavigation.noteExternalFailure(job.playerId(), target, "watchdog_stall", true);
        BuildNavigation.invalidateCachedPath(job.playerId());
        boolean backtracked = BuildNavigation.backtrackToLastSafe(world, player, 7);
        if (backtracked) {
            job.recordBacktrack();
        }

        boolean replanned = false;
        int lookahead = Math.max(16, job.runtimeSettings().schedulerLookahead() + 4);
        if (job.runtimeSettings().targetSchedulerEnabled()) {
            replanned = job.selectBestTargetNear(player.getBlockPos(), lookahead);
        }
        boolean deferred = false;
        if (!replanned
            && job.runtimeSettings().deferUnreachableTargets()
            && job.currentDeferrals() < job.runtimeSettings().maxTargetDeferrals()) {
            deferred = job.deferCurrentToTail();
        }
        if (replanned || deferred) {
            job.recordPathReplan();
        }

        job.recordStuckEvent();
        job.noteEvent("watchdog stall=" + stalled + "t rep=" + replanned + " defer=" + deferred + " back=" + backtracked + " p=" + pressure + " target=" + shortTarget(target));
        if (pressure >= TARGET_PRESSURE_SKIP_THRESHOLD
            && (!job.runtimeSettings().deferUnreachableTargets()
                || job.currentDeferrals() >= job.runtimeSettings().maxTargetDeferrals())) {
            job.recordNoReach();
            job.recordSkipped();
            clearTargetPressure(job.playerId(), target);
            job.noteEvent("skip(watchdog_pressure=" + pressure + ") target=" + shortTarget(target));
            job.advance();
            return;
        }
        if (!deferred) {
            job.startRecover(PlacementJob.RecoverReason.OUT_OF_REACH, "reason=watchdog_stall target=" + shortTarget(target));
        }
    }

    private static void nodeMove(net.minecraft.server.world.ServerWorld world, ServerPlayerEntity player, PlacementJob job) {
        BlockPos target = job.currentTarget();
        int pressure = targetPressure(job.playerId(), target);
        if (pressure >= TARGET_PRESSURE_SKIP_THRESHOLD) {
            boolean canStillDefer = job.runtimeSettings().deferUnreachableTargets()
                && job.currentDeferrals() < job.runtimeSettings().maxTargetDeferrals();
            if (canStillDefer && job.deferCurrentToTail()) {
                job.recordNoReach();
                job.noteEvent("defer(pressure=" + pressure + ") target=" + shortTarget(target));
                return;
            }
            job.recordNoReach();
            job.recordSkipped();
            clearTargetPressure(job.playerId(), target);
            job.noteEvent("skip(pressure=" + pressure + ") target=" + shortTarget(target));
            job.advance();
            return;
        }
        if (pressure >= TARGET_PRESSURE_DEFER_THRESHOLD
            && job.runtimeSettings().deferUnreachableTargets()
            && job.currentDeferrals() < job.runtimeSettings().maxTargetDeferrals()
            && job.deferCurrentToTail()) {
            job.recordNoReach();
            job.noteEvent("defer(pressure=" + pressure + ") target=" + shortTarget(target));
            return;
        }

        BuildNavigation.MoveResult move = BuildNavigation.ensureInRangeForPlacement(world, player, target, job.runtimeSettings());
        if (move.status() < 0) {
            addTargetPressure(job.playerId(), target, 1);
            String detail = "target=" + shortTarget(target)
                + " reason=" + move.reason()
                + " dist=" + String.format(Locale.ROOT, "%.2f", move.finalDistance());
            job.startRecover(PlacementJob.RecoverReason.OUT_OF_REACH, detail);
            return;
        }
        if (move.status() > 0) {
            job.recordMoved();
            job.noteEvent("moved(" + move.reason() + ") target=" + shortTarget(target));
        }
        job.setNode(PlacementJob.TaskNode.ALIGN);
    }

    private static void nodeAlign(net.minecraft.server.world.ServerWorld world, ServerPlayerEntity player, PlacementJob job) {
        BlockPos target = job.currentTarget();
        double dist = Math.sqrt(player.squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5));
        if (dist > job.runtimeSettings().reachDistance() + 0.45) {
            addTargetPressure(job.playerId(), target, 1);
            job.setNode(PlacementJob.TaskNode.MOVE);
            return;
        }

        var existingState = world.getBlockState(target);
        if (existingState.isOf(job.currentBlock())) {
            clearTargetPressure(job.playerId(), target);
            job.recordAlreadyPlaced();
            job.recordSkipped();
            job.noteEvent("already target=" + shortTarget(target));
            job.advance();
            return;
        }
        if (BuildSafetyPolicy.isProtected(existingState)) {
            job.startRecover(PlacementJob.RecoverReason.PROTECTED_BLOCK, "target=" + shortTarget(target));
            return;
        }
        if (job.runtimeSettings().strictAirOnly() && !existingState.isAir()) {
            job.startRecover(PlacementJob.RecoverReason.BLOCKED_STRICT_AIR, "target=" + shortTarget(target));
            return;
        }
        if (!existingState.isAir() && !existingState.isReplaceable()) {
            job.startRecover(PlacementJob.RecoverReason.BLOCKED_SOLID, "target=" + shortTarget(target));
            return;
        }
        if (!hasPlacementItemIfNeeded(player, job.currentBlock())) {
            job.startRecover(PlacementJob.RecoverReason.NO_ITEM, "block=" + blockId(job));
            return;
        }
        job.setNode(PlacementJob.TaskNode.PLACE);
    }

    private static void nodePlace(net.minecraft.server.world.ServerWorld world, ServerPlayerEntity player, PlacementJob job) {
        BlockPos target = job.currentTarget();
        var desiredState = job.currentBlock().getDefaultState();
        var features = PlacementFeatureExtractor.extract(world, player, target);
        var model = BladelowLearning.model();
        double score = model.score(features);
        job.addScore(score);

        if (!model.shouldPlace(features)) {
            model.train(features, false);
            job.startRecover(PlacementJob.RecoverReason.ML_REJECTED, "target=" + shortTarget(target));
            return;
        }

        boolean changed = world.setBlockState(target, desiredState);
        model.train(features, changed);
        if (changed) {
            clearTargetPressure(job.playerId(), target);
            consumePlacementItemAfterSuccess(player, job.currentBlock());
            job.recordPlaced();
            job.noteEvent("placed block=" + blockId(job) + " target=" + shortTarget(target));
            job.advance();
            return;
        }
        addTargetPressure(job.playerId(), target, 2);
        job.startRecover(PlacementJob.RecoverReason.PLACE_FAILED, "target=" + shortTarget(target));
    }

    private static void nodeRecover(net.minecraft.server.world.ServerWorld world, ServerPlayerEntity player, PlacementJob job) {
        if (job.isComplete()) {
            return;
        }
        BlockPos target = job.currentTarget();
        int tries = job.incrementCurrentAttempts();
        switch (job.recoverReason()) {
            case OUT_OF_REACH -> recoverOutOfReach(player, job, target, tries);
            case PROTECTED_BLOCK -> {
                job.recordProtectedBlocked();
                job.recordSkipped();
                job.noteEvent("skip(protected) target=" + shortTarget(target));
                job.advance();
            }
            case BLOCKED_STRICT_AIR -> recoverBlocked(world, player, job, target, tries, "strict_air");
            case BLOCKED_SOLID -> recoverBlocked(world, player, job, target, tries, "solid");
            case NO_ITEM -> {
                job.recordSkipped();
                job.noteEvent("skip(no_item) block=" + blockId(job) + " target=" + shortTarget(target));
                job.advance();
            }
            case ML_REJECTED -> {
                job.recordMlRejected();
                job.recordSkipped();
                job.noteEvent("skip(ml) target=" + shortTarget(target));
                job.advance();
            }
            case PLACE_FAILED -> recoverPlaceFailure(world, player, job, target, tries);
            case NONE, UNKNOWN -> {
                job.recordFailed();
                job.noteEvent("skip(unknown) target=" + shortTarget(target));
                job.advance();
            }
        }

        if (job.shouldReportProgress()) {
            player.sendMessage(blueText(job.progressSummary()), false);
        }
    }

    private static void recoverOutOfReach(ServerPlayerEntity player, PlacementJob job, BlockPos target, int tries) {
        String recoverDetail = job.recoverDetail();
        if (recoverDetail.contains("reason=smart_move_disabled")) {
            job.recordNoReach();
            job.recordSkipped();
            job.noteEvent("skip(out_of_reach:smart_off) target=" + shortTarget(target));
            job.advance();
            return;
        }

        boolean hardPathMiss = recoverDetail.contains("reason=no_approach_candidate")
            || recoverDetail.contains("reason=walk_no_path")
            || recoverDetail.contains("reason=walk_no_progress")
            || recoverDetail.contains("reason=walk_greedy_rejected");
        int pressure = addTargetPressure(job.playerId(), target, hardPathMiss ? 2 : 1);
        int replanTryGate = hardPathMiss ? 1 : 2;
        int deferTryGate = hardPathMiss ? 1 : 2;
        if (pressure >= TARGET_PRESSURE_DEFER_THRESHOLD) {
            deferTryGate = 1;
        }

        int schedulerLookahead = Math.max(10, job.runtimeSettings().schedulerLookahead());
        boolean canReplan = tries >= replanTryGate
            && job.runtimeSettings().targetSchedulerEnabled()
            && job.selectBestTargetNear(player.getBlockPos(), schedulerLookahead);
        if (canReplan) {
            job.recordPathReplan();
            job.noteEvent("replan(out_of_reach) target=" + shortTarget(target));
            job.setNode(PlacementJob.TaskNode.MOVE);
            return;
        }

        if (tries >= deferTryGate
            && job.runtimeSettings().deferUnreachableTargets()
            && job.currentDeferrals() < job.runtimeSettings().maxTargetDeferrals()) {
            boolean deferred = job.deferCurrentToTail();
            if (deferred) {
                job.recordNoReach();
                job.noteEvent("defer(out_of_reach p=" + pressure + ") target=" + shortTarget(target));
                return;
            }
        }

        if (pressure >= TARGET_PRESSURE_SKIP_THRESHOLD
            && (!job.runtimeSettings().deferUnreachableTargets()
                || job.currentDeferrals() >= job.runtimeSettings().maxTargetDeferrals())) {
            job.recordNoReach();
            job.recordSkipped();
            clearTargetPressure(job.playerId(), target);
            job.noteEvent("skip(out_of_reach p=" + pressure + ") target=" + shortTarget(target));
            job.advance();
            return;
        }

        if (tries < MAX_RETRIES_PER_TARGET) {
            job.noteEvent("retry(out_of_reach) " + tries + "/" + MAX_RETRIES_PER_TARGET + " target=" + shortTarget(target));
            job.setNode(PlacementJob.TaskNode.MOVE);
            return;
        }

        boolean deferred = false;
        if (job.runtimeSettings().deferUnreachableTargets()
            && job.currentDeferrals() < job.runtimeSettings().maxTargetDeferrals()) {
            deferred = job.deferCurrentToTail();
        }
        job.recordNoReach();
        if (deferred) {
            job.noteEvent("defer(out_of_reach) target=" + shortTarget(target));
            return;
        }
        job.recordSkipped();
        job.noteEvent("skip(out_of_reach) target=" + shortTarget(target));
        job.advance();
    }

    private static void recoverBlocked(
        net.minecraft.server.world.ServerWorld world,
        ServerPlayerEntity player,
        PlacementJob job,
        BlockPos target,
        int tries,
        String reason
    ) {
        int pressure = addTargetPressure(job.playerId(), target, 1);
        if (pressure >= TARGET_PRESSURE_SKIP_THRESHOLD) {
            job.recordBlocked();
            job.recordSkipped();
            clearTargetPressure(job.playerId(), target);
            job.noteEvent("skip(blocked:" + reason + " p=" + pressure + ") target=" + shortTarget(target));
            job.advance();
            return;
        }
        if (tries <= MAX_BLOCKED_RETRIES && tryAutoClearSoftBlocker(world, player, job, target, reason)) {
            job.setNode(PlacementJob.TaskNode.ALIGN);
            return;
        }
        if (tries < MAX_BLOCKED_RETRIES) {
            job.noteEvent("retry(blocked:" + reason + ") " + tries + "/" + MAX_BLOCKED_RETRIES + " target=" + shortTarget(target));
            job.setNode(PlacementJob.TaskNode.ALIGN);
            return;
        }
        job.recordBlocked();
        job.recordSkipped();
        job.noteEvent("skip(blocked:" + reason + ") target=" + shortTarget(target));
        job.advance();
    }

    private static void recoverPlaceFailure(
        net.minecraft.server.world.ServerWorld world,
        ServerPlayerEntity player,
        PlacementJob job,
        BlockPos target,
        int tries
    ) {
        int pressure = addTargetPressure(job.playerId(), target, 1);
        if (pressure >= TARGET_PRESSURE_SKIP_THRESHOLD) {
            job.recordFailed();
            job.recordSkipped();
            clearTargetPressure(job.playerId(), target);
            job.noteEvent("skip(place_failed p=" + pressure + ") target=" + shortTarget(target));
            job.advance();
            return;
        }
        if (tries <= MAX_RETRIES_PER_TARGET && tryAutoPlaceSupport(world, player, job, target)) {
            job.setNode(PlacementJob.TaskNode.ALIGN);
            return;
        }
        if (tries < MAX_RETRIES_PER_TARGET) {
            job.noteEvent("retry(place) " + tries + "/" + MAX_RETRIES_PER_TARGET + " target=" + shortTarget(target));
            job.setNode(PlacementJob.TaskNode.MOVE);
            return;
        }
        job.recordFailed();
        job.recordSkipped();
        job.noteEvent("skip(place_failed) block=" + blockId(job) + " target=" + shortTarget(target));
        job.advance();
    }

    private static boolean tryAutoClearSoftBlocker(
        net.minecraft.server.world.ServerWorld world,
        ServerPlayerEntity player,
        PlacementJob job,
        BlockPos target,
        String reason
    ) {
        if (world == null || player == null || job == null || target == null) {
            return false;
        }
        BlockState state = world.getBlockState(target);
        if (state.isAir() || BuildSafetyPolicy.isProtected(state) || !isSoftRemovable(state, world, target)) {
            return false;
        }

        Block removed = state.getBlock();
        boolean changed = world.setBlockState(target, Blocks.AIR.getDefaultState());
        if (!changed) {
            return false;
        }

        world.spawnParticles(ParticleTypes.CLOUD, target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 2, 0.12, 0.12, 0.12, 0.0);
        job.recordBlocked();
        job.noteEvent("auto_clear(" + reason + ") removed=" + Registries.BLOCK.getId(removed) + " target=" + shortTarget(target));
        return true;
    }

    private static boolean isSoftRemovable(BlockState state, net.minecraft.server.world.ServerWorld world, BlockPos pos) {
        if (state == null || world == null || pos == null) {
            return false;
        }
        if (state.isAir() || state.hasBlockEntity()) {
            return false;
        }
        if (state.isOf(Blocks.BEDROCK) || state.isOf(Blocks.BARRIER) || state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.CRYING_OBSIDIAN)) {
            return false;
        }
        float hardness = state.getHardness(world, pos);
        if (hardness < 0.0f || hardness > MAX_AUTO_CLEAR_HARDNESS) {
            return false;
        }
        return true;
    }

    private static boolean tryAutoPlaceSupport(
        net.minecraft.server.world.ServerWorld world,
        ServerPlayerEntity player,
        PlacementJob job,
        BlockPos target
    ) {
        if (world == null || player == null || job == null || target == null) {
            return false;
        }
        BlockState targetState = world.getBlockState(target);
        if (!targetState.isAir() && !targetState.isReplaceable()) {
            return false;
        }

        BlockPos below = target.down();
        BlockState belowState = world.getBlockState(below);
        if (BuildSafetyPolicy.isProtected(belowState)) {
            return false;
        }
        if (!belowState.getCollisionShape(world, below).isEmpty()) {
            return false;
        }

        Block support = pickSupportBlock(player, job.currentBlock());
        if (support == null) {
            return false;
        }
        BlockState supportState = support.getDefaultState();
        if (supportState.isAir() || supportState.getCollisionShape(world, below).isEmpty() || supportState.isReplaceable()) {
            return false;
        }

        boolean placed = world.setBlockState(below, supportState);
        if (!placed) {
            return false;
        }
        consumePlacementItemAfterSuccess(player, support);
        world.spawnParticles(ParticleTypes.END_ROD, below.getX() + 0.5, below.getY() + 1.05, below.getZ() + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
        job.noteEvent("auto_support block=" + Registries.BLOCK.getId(support) + " at=" + shortTarget(below) + " for=" + shortTarget(target));
        return true;
    }

    private static Block pickSupportBlock(ServerPlayerEntity player, Block preferred) {
        if (player == null) {
            return null;
        }
        if (player.getAbilities().creativeMode) {
            return isGoodSupportBlock(preferred) ? preferred : Blocks.COBBLESTONE;
        }

        if (isGoodSupportBlock(preferred) && hasPlacementItemIfNeeded(player, preferred)) {
            return preferred;
        }

        Block best = null;
        int bestCount = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            Item item = stack.getItem();
            if (!(item instanceof BlockItem blockItem)) {
                continue;
            }
            Block candidate = blockItem.getBlock();
            if (!isGoodSupportBlock(candidate)) {
                continue;
            }
            int count = stack.getCount();
            if (best == null || count > bestCount) {
                best = candidate;
                bestCount = count;
            }
        }
        return best;
    }

    private static boolean isGoodSupportBlock(Block block) {
        if (block == null || block.asItem() == Items.AIR) {
            return false;
        }
        BlockState state = block.getDefaultState();
        if (state.isAir() || state.isReplaceable()) {
            return false;
        }
        String id = Registries.BLOCK.getId(block).toString();
        return !id.contains("sand")
            && !id.contains("gravel")
            && !id.contains("powder_snow")
            && !id.contains("torch")
            && !id.contains("rail");
    }

    private static void trackStuckAndRecover(net.minecraft.server.world.ServerWorld world, ServerPlayerEntity player, PlacementJob job) {
        if (job == null || job.isComplete()) {
            if (job != null) {
                STUCK.remove(job.playerId());
            }
            return;
        }

        boolean track = job.currentNode() == PlacementJob.TaskNode.MOVE
            || (job.currentNode() == PlacementJob.TaskNode.RECOVER && job.recoverReason() == PlacementJob.RecoverReason.OUT_OF_REACH);
        if (!track) {
            STUCK.remove(job.playerId());
            return;
        }

        BlockPos target = job.currentTarget();
        double distNow = distanceTo(player, target);
        BlockPos playerPos = player.getBlockPos();

        StuckState state = STUCK.computeIfAbsent(
            job.playerId(),
            ignored -> StuckState.fresh(job.cursor(), distNow, playerPos)
        );

        if (state.cooldownTicks() > 0) {
            state.cooldownTicks(state.cooldownTicks() - 1);
        }

        if (state.cursor() != job.cursor()) {
            state.reset(job.cursor(), distNow, playerPos);
            return;
        }

        boolean improved = distNow + STUCK_PROGRESS_EPS < state.bestDistance();
        boolean moved = !playerPos.equals(state.lastPos());
        if (improved) {
            state.bestDistance(distNow);
            state.stagnantTicks(0);
        } else if (moved && distNow + (STUCK_PROGRESS_EPS * 0.35) < state.lastDistance()) {
            state.stagnantTicks(Math.max(0, state.stagnantTicks() - 1));
        } else {
            state.stagnantTicks(state.stagnantTicks() + 1);
        }

        state.lastDistance(distNow);
        state.lastPos(playerPos);

        if (state.cooldownTicks() > 0 || state.stagnantTicks() < STUCK_TICKS_THRESHOLD) {
            return;
        }

        state.stagnantTicks(0);
        state.cooldownTicks(STUCK_COOLDOWN_TICKS);
        state.bestDistance(distNow);
        int pressure = addTargetPressure(job.playerId(), target, 2);

        job.recordStuckEvent();
        BuildNavigation.noteExternalFailure(job.playerId(), target, "stuck_target", true);

        boolean backtracked = BuildNavigation.backtrackToLastSafe(world, player, 8);
        if (backtracked) {
            job.recordBacktrack();
        }

        BuildNavigation.invalidateCachedPath(job.playerId());

        boolean replanned = false;
        int lookahead = Math.max(16, job.runtimeSettings().schedulerLookahead() + 4);
        if (job.runtimeSettings().targetSchedulerEnabled()) {
            replanned = job.selectBestTargetNear(player.getBlockPos(), lookahead);
        }
        if (!replanned
            && job.runtimeSettings().deferUnreachableTargets()
            && job.currentDeferrals() < job.runtimeSettings().maxTargetDeferrals()) {
            replanned = job.deferCurrentToTail();
        }
        if (replanned) {
            job.recordPathReplan();
        }

        if (pressure >= TARGET_PRESSURE_SKIP_THRESHOLD
            && (!job.runtimeSettings().deferUnreachableTargets()
                || job.currentDeferrals() >= job.runtimeSettings().maxTargetDeferrals())) {
            job.recordNoReach();
            job.recordSkipped();
            clearTargetPressure(job.playerId(), target);
            job.noteEvent("skip(stuck_pressure=" + pressure + ") target=" + shortTarget(target));
            job.advance();
            return;
        }

        job.setNode(PlacementJob.TaskNode.MOVE);
        job.noteEvent("stuck_recover d=" + String.format(Locale.ROOT, "%.2f", distNow)
            + " rep=" + replanned + " back=" + backtracked + " p=" + pressure);
    }

    private static double distanceTo(ServerPlayerEntity player, BlockPos target) {
        return Math.sqrt(player.squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5));
    }

    private static int targetPressure(UUID playerId, BlockPos target) {
        if (playerId == null || target == null) {
            return 0;
        }
        Map<Long, Integer> byTarget = TARGET_PRESSURE.get(playerId);
        if (byTarget == null) {
            return 0;
        }
        return Math.max(0, byTarget.getOrDefault(target.asLong(), 0));
    }

    private static int addTargetPressure(UUID playerId, BlockPos target, int delta) {
        if (playerId == null || target == null || delta <= 0) {
            return 0;
        }
        long key = target.asLong();
        Map<Long, Integer> byTarget = TARGET_PRESSURE.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
        if (!byTarget.containsKey(key) && byTarget.size() >= TARGET_PRESSURE_MAX_TRACKED) {
            return Math.max(0, byTarget.getOrDefault(key, 0));
        }
        int next = byTarget.getOrDefault(key, 0) + delta;
        if (next > TARGET_PRESSURE_MAX_VALUE) {
            next = TARGET_PRESSURE_MAX_VALUE;
        }
        byTarget.put(key, next);
        return next;
    }

    private static void clearTargetPressure(UUID playerId, BlockPos target) {
        if (playerId == null || target == null) {
            return;
        }
        Map<Long, Integer> byTarget = TARGET_PRESSURE.get(playerId);
        if (byTarget == null) {
            return;
        }
        byTarget.remove(target.asLong());
        if (byTarget.isEmpty()) {
            TARGET_PRESSURE.remove(playerId);
        }
    }

    private static void decayTargetPressure() {
        Iterator<Map.Entry<UUID, Map<Long, Integer>>> it = TARGET_PRESSURE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Map<Long, Integer>> entry = it.next();
            Map<Long, Integer> byTarget = entry.getValue();
            if (byTarget == null || byTarget.isEmpty()) {
                it.remove();
                continue;
            }
            Iterator<Map.Entry<Long, Integer>> targetIt = byTarget.entrySet().iterator();
            while (targetIt.hasNext()) {
                Map.Entry<Long, Integer> targetEntry = targetIt.next();
                int value = targetEntry.getValue() == null ? 0 : targetEntry.getValue();
                if (value <= 1) {
                    targetIt.remove();
                } else {
                    targetEntry.setValue(value - 1);
                }
            }
            if (byTarget.isEmpty()) {
                it.remove();
            }
        }
    }

    private static String detailSummary(String state, PlacementJob job) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Bladelow] ").append(state).append(" ").append(job.progressSummary());
        if (!job.isComplete()) {
            BlockPos target = job.currentTarget();
            sb.append(" nextBlock=").append(blockId(job));
            sb.append(" nextTarget=").append(shortTarget(target));
            sb.append(" pressure=").append(targetPressure(job.playerId(), target));
            sb.append(" attempts=").append(job.currentAttempts());
            sb.append(" defers=").append(job.currentDeferrals());
            sb.append(" node=").append(job.currentNode().name().toLowerCase());
            if (job.currentNode() == PlacementJob.TaskNode.RECOVER) {
                sb.append(" reason=").append(job.recoverReason().name().toLowerCase());
            }
        }
        return sb.toString();
    }

    private static void finishJob(ServerPlayerEntity player, PlacementJob job) {
        player.sendMessage(blueText(job.completionSummary()), false);
        rememberSnapshot(job.playerId(), "completed", job, detailSummary("completed", job));
        String saveStatus = BladelowLearning.save();
        player.sendMessage(blueText("[Bladelow] model " + saveStatus), false);
    }

    private static String renderDiagnosticText(UUID playerId, DiagSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Bladelow Diagnostic Report").append('\n');
        sb.append("player_id=").append(playerId).append('\n');
        sb.append("created_at_utc=").append(HUMAN_TS.format(snapshot.createdAt())).append('\n');
        sb.append("phase=").append(snapshot.phase()).append('\n');
        sb.append("tag=").append(snapshot.metrics().tag()).append('\n');
        sb.append("summary=").append(snapshot.summary()).append('\n');
        sb.append("detail=").append(snapshot.detail()).append('\n');
        sb.append("runtime=").append(snapshot.metrics().runtime()).append('\n');
        sb.append("node=").append(snapshot.metrics().node()).append('\n');
        sb.append("recover_reason=").append(snapshot.metrics().recoverReason()).append('\n');
        sb.append("recover_detail=").append(snapshot.metrics().recoverDetail()).append('\n');
        sb.append("cursor=").append(snapshot.metrics().cursor()).append('\n');
        sb.append("targets=").append(snapshot.metrics().targets()).append('\n');
        sb.append("placed=").append(snapshot.metrics().placed()).append('\n');
        sb.append("skipped=").append(snapshot.metrics().skipped()).append('\n');
        sb.append("failed=").append(snapshot.metrics().failed()).append('\n');
        sb.append("moved=").append(snapshot.metrics().moved()).append('\n');
        sb.append("deferred=").append(snapshot.metrics().deferred()).append('\n');
        sb.append("replan=").append(snapshot.metrics().replan()).append('\n');
        sb.append("already=").append(snapshot.metrics().already()).append('\n');
        sb.append("blocked=").append(snapshot.metrics().blocked()).append('\n');
        sb.append("protected=").append(snapshot.metrics().protectedBlocked()).append('\n');
        sb.append("no_reach=").append(snapshot.metrics().noReach()).append('\n');
        sb.append("no_reach_pct=").append(String.format("%.1f", snapshot.metrics().noReachPct())).append('\n');
        sb.append("ml_skip=").append(snapshot.metrics().mlSkip()).append('\n');
        sb.append("stuck_events=").append(snapshot.metrics().stuckEvents()).append('\n');
        sb.append("replans=").append(snapshot.metrics().pathReplans()).append('\n');
        sb.append("backtracks=").append(snapshot.metrics().backtracks()).append('\n');
        sb.append("blacklist_hits=").append(snapshot.metrics().blacklistHits()).append('\n');
        sb.append("avg_score=").append(String.format("%.3f", snapshot.metrics().avgScore())).append('\n');
        sb.append("last_event=").append(snapshot.metrics().lastEvent()).append('\n');
        sb.append("current_status=").append(status(playerId)).append('\n');
        sb.append("current_detail=").append(statusDetail(playerId)).append('\n');
        return sb.toString();
    }

    private static void rememberSnapshot(UUID playerId, String phase, PlacementJob job, String detail) {
        if (playerId == null || job == null) {
            return;
        }
        String summary = job.isComplete() ? job.completionSummary() : job.progressSummary();
        String details = detail == null ? "" : detail;
        LAST_DIAG.put(playerId, new DiagSnapshot(Instant.now(), phase, summary, details, metricsFromJob(job)));
    }

    private static DiagSnapshot snapshotFor(UUID playerId) {
        PlacementJob active = JOBS.get(playerId);
        if (active != null) {
            return new DiagSnapshot(
                Instant.now(),
                "active",
                active.progressSummary(),
                detailSummary("active", active),
                metricsFromJob(active)
            );
        }
        PlacementJob pending = PENDING.get(playerId);
        if (pending != null) {
            return new DiagSnapshot(
                Instant.now(),
                "pending",
                pending.progressSummary(),
                detailSummary("pending", pending),
                metricsFromJob(pending)
            );
        }
        return LAST_DIAG.get(playerId);
    }

    private static JobMetrics metricsFromJob(PlacementJob job) {
        return new JobMetrics(
            job.tag(),
            job.runtimeSettings().summary(),
            job.currentNode().name().toLowerCase(),
            job.recoverReason().name().toLowerCase(),
            job.recoverDetail(),
            job.cursor(),
            job.totalTargets(),
            job.placedCount(),
            job.skippedCount(),
            job.failedCount(),
            job.movedCount(),
            job.deferredCount(),
            job.reprioritizedCount(),
            job.alreadyPlacedCount(),
            job.blockedCount(),
            job.protectedBlockedCount(),
            job.noReachCount(),
            job.noReachPercent(),
            job.mlRejectedCount(),
            job.stuckEventsCount(),
            job.pathReplansCount(),
            job.backtracksCount(),
            job.blacklistHitsCount(),
            job.averageScore(),
            job.lastEvent()
        );
    }

    private static String sanitizeLabel(String label) {
        if (label == null) {
            return "";
        }
        String cleaned = label.trim().toLowerCase().replaceAll("[^a-z0-9._-]+", "_");
        if (cleaned.length() > 40) {
            cleaned = cleaned.substring(0, 40);
        }
        return cleaned;
    }

    private static void preview(PlacementJob job, MinecraftServer server, ServerPlayerEntity player) {
        var world = server.getWorld(job.worldKey());
        if (world == null) {
            return;
        }
        int shown = 0;
        for (int i = 0; i < Math.min(job.totalTargets(), 250); i++) {
            BlockPos p = job.targetAt(i);
            world.spawnParticles(
                ParticleTypes.END_ROD,
                p.getX() + 0.5,
                p.getY() + 1.05,
                p.getZ() + 0.5,
                1,
                0.0,
                0.0,
                0.0,
                0.0
            );
            shown++;
        }
        player.sendMessage(blueText("[Bladelow] preview markers shown: " + shown), false);
    }

    private static boolean hasPlacementItemIfNeeded(ServerPlayerEntity player, net.minecraft.block.Block block) {
        if (player.getAbilities().creativeMode) {
            return true;
        }
        Item item = block.asItem();
        if (item == Items.AIR) {
            return false;
        }

        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty() || !stack.isOf(item)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static void consumePlacementItemAfterSuccess(ServerPlayerEntity player, net.minecraft.block.Block block) {
        if (player.getAbilities().creativeMode) {
            return;
        }
        Item item = block.asItem();
        if (item == Items.AIR) {
            return;
        }

        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty() || !stack.isOf(item)) {
                continue;
            }
            stack.decrement(1);
            inv.markDirty();
            return;
        }
    }

    private static String blockId(PlacementJob job) {
        return Registries.BLOCK.getId(job.currentBlock()).toString();
    }

    private static String shortTarget(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static Text blueText(String message) {
        return Text.literal(message).formatted(Formatting.AQUA);
    }

    private static final class ProgressWatchdog {
        private int cursor;
        private int placed;
        private int skipped;
        private int moved;
        private long lastProgressTick;
        private long lastTriggerTick;
        private int triggerCount;

        private ProgressWatchdog(int cursor, int placed, int skipped, int moved, long nowTick) {
            this.cursor = cursor;
            this.placed = placed;
            this.skipped = skipped;
            this.moved = moved;
            this.lastProgressTick = nowTick;
            this.lastTriggerTick = Long.MIN_VALUE / 4L;
            this.triggerCount = 0;
        }

        static ProgressWatchdog fresh(PlacementJob job, long nowTick) {
            return new ProgressWatchdog(job.cursor(), job.placedCount(), job.skippedCount(), job.movedCount(), nowTick);
        }

        boolean observeProgress(PlacementJob job, long nowTick) {
            if (job.cursor() != cursor
                || job.placedCount() != placed
                || job.skippedCount() != skipped
                || job.movedCount() != moved) {
                this.cursor = job.cursor();
                this.placed = job.placedCount();
                this.skipped = job.skippedCount();
                this.moved = job.movedCount();
                this.lastProgressTick = nowTick;
                return true;
            }
            return false;
        }

        long lastProgressTick() {
            return lastProgressTick;
        }

        long lastTriggerTick() {
            return lastTriggerTick;
        }

        void markTriggered(long nowTick) {
            this.lastTriggerTick = nowTick;
            this.lastProgressTick = nowTick;
        }

        void bumpTriggerCount() {
            this.triggerCount++;
        }
    }

    private static final class StuckState {
        private int cursor;
        private double bestDistance;
        private double lastDistance;
        private BlockPos lastPos;
        private int stagnantTicks;
        private int cooldownTicks;

        private StuckState(int cursor, double distance, BlockPos lastPos) {
            this.cursor = cursor;
            this.bestDistance = distance;
            this.lastDistance = distance;
            this.lastPos = lastPos;
            this.stagnantTicks = 0;
            this.cooldownTicks = 0;
        }

        static StuckState fresh(int cursor, double distance, BlockPos pos) {
            return new StuckState(cursor, distance, pos);
        }

        void reset(int cursor, double distance, BlockPos pos) {
            this.cursor = cursor;
            this.bestDistance = distance;
            this.lastDistance = distance;
            this.lastPos = pos;
            this.stagnantTicks = 0;
            this.cooldownTicks = 0;
        }

        int cursor() {
            return cursor;
        }

        double bestDistance() {
            return bestDistance;
        }

        void bestDistance(double bestDistance) {
            this.bestDistance = bestDistance;
        }

        double lastDistance() {
            return lastDistance;
        }

        void lastDistance(double lastDistance) {
            this.lastDistance = lastDistance;
        }

        BlockPos lastPos() {
            return lastPos;
        }

        void lastPos(BlockPos lastPos) {
            this.lastPos = lastPos;
        }

        int stagnantTicks() {
            return stagnantTicks;
        }

        void stagnantTicks(int stagnantTicks) {
            this.stagnantTicks = stagnantTicks;
        }

        int cooldownTicks() {
            return cooldownTicks;
        }

        void cooldownTicks(int cooldownTicks) {
            this.cooldownTicks = cooldownTicks;
        }
    }

    private record DiagSnapshot(Instant createdAt, String phase, String summary, String detail, JobMetrics metrics) {
    }

    private record JobMetrics(
        String tag,
        String runtime,
        String node,
        String recoverReason,
        String recoverDetail,
        int cursor,
        int targets,
        int placed,
        int skipped,
        int failed,
        int moved,
        int deferred,
        int replan,
        int already,
        int blocked,
        int protectedBlocked,
        int noReach,
        double noReachPct,
        int mlSkip,
        int stuckEvents,
        int pathReplans,
        int backtracks,
        int blacklistHits,
        double avgScore,
        String lastEvent
    ) {
    }

    public record DiagExportResult(boolean ok, String message) {
        public static DiagExportResult ok(String message) {
            return new DiagExportResult(true, message);
        }

        public static DiagExportResult error(String message) {
            return new DiagExportResult(false, message);
        }
    }
}
