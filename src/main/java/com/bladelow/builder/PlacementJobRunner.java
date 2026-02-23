package com.bladelow.builder;

import com.bladelow.ml.BladelowLearning;
import com.bladelow.ml.PlacementFeatureExtractor;
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlacementJobRunner {
    private static final Map<UUID, PlacementJob> JOBS = new ConcurrentHashMap<>();
    private static final Map<UUID, PlacementJob> PENDING = new ConcurrentHashMap<>();
    private static final int MAX_RETRIES_PER_TARGET = 3;
    private static final int MAX_BLOCKED_RETRIES = 2;

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
        boolean active = JOBS.remove(playerId) != null;
        boolean pending = PENDING.remove(playerId) != null;
        boolean changed = active || pending;
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
        JOBS.put(playerId, pending);
        saveCheckpoint(server);
        return true;
    }

    public static boolean confirm(MinecraftServer server, UUID playerId) {
        PlacementJob pending = PENDING.remove(playerId);
        if (pending == null) {
            return false;
        }
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

    public static boolean hasActive(UUID playerId) {
        return JOBS.containsKey(playerId);
    }

    public static boolean hasPending(UUID playerId) {
        return PENDING.containsKey(playerId);
    }

    public static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, PlacementJob>> it = JOBS.entrySet().iterator();
        boolean stateChanged = false;

        while (it.hasNext()) {
            Map.Entry<UUID, PlacementJob> entry = it.next();
            PlacementJob job = entry.getValue();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(job.playerId());
            if (player == null) {
                PENDING.put(job.playerId(), job);
                it.remove();
                stateChanged = true;
                continue;
            }

            if (job.isComplete()) {
                finishJob(player, job);
                it.remove();
                stateChanged = true;
                continue;
            }
            job.tick();

            var world = server.getWorld(job.worldKey());
            if (world == null) {
                player.sendMessage(blueText("[Bladelow] Target world unavailable. Build canceled."), false);
                it.remove();
                stateChanged = true;
                continue;
            }

            if (job.runtimeSettings().targetSchedulerEnabled() && job.currentNode() == PlacementJob.TaskNode.MOVE) {
                job.selectBestTargetNear(player.getBlockPos(), job.runtimeSettings().schedulerLookahead());
            }
            runTaskNode(world, player, job);

            if (job.shouldReportProgress()) {
                player.sendMessage(blueText(job.progressSummary()), false);
            }

            if (job.isComplete()) {
                finishJob(player, job);
                it.remove();
                stateChanged = true;
            }
        }

        if (stateChanged || ((!JOBS.isEmpty() || !PENDING.isEmpty()) && server.getTicks() % 40 == 0)) {
            saveCheckpoint(server);
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
            case RECOVER -> nodeRecover(player, job);
        }
    }

    private static void nodeMove(net.minecraft.server.world.ServerWorld world, ServerPlayerEntity player, PlacementJob job) {
        BlockPos target = job.currentTarget();
        int moveStatus = BuildNavigation.ensureInRangeForPlacement(world, player, target, job.runtimeSettings());
        if (moveStatus < 0) {
            job.startRecover(PlacementJob.RecoverReason.OUT_OF_REACH, "target=" + shortTarget(target));
            return;
        }
        if (moveStatus > 0) {
            job.recordMoved();
            job.noteEvent("moved target=" + shortTarget(target));
        }
        job.setNode(PlacementJob.TaskNode.ALIGN);
    }

    private static void nodeAlign(net.minecraft.server.world.ServerWorld world, ServerPlayerEntity player, PlacementJob job) {
        BlockPos target = job.currentTarget();
        double dist = Math.sqrt(player.squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5));
        if (dist > job.runtimeSettings().reachDistance() + 0.45) {
            job.setNode(PlacementJob.TaskNode.MOVE);
            return;
        }

        var existingState = world.getBlockState(target);
        if (existingState.isOf(job.currentBlock())) {
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
            consumePlacementItemAfterSuccess(player, job.currentBlock());
            job.recordPlaced();
            job.noteEvent("placed block=" + blockId(job) + " target=" + shortTarget(target));
            job.advance();
            return;
        }
        job.startRecover(PlacementJob.RecoverReason.PLACE_FAILED, "target=" + shortTarget(target));
    }

    private static void nodeRecover(ServerPlayerEntity player, PlacementJob job) {
        if (job.isComplete()) {
            return;
        }
        BlockPos target = job.currentTarget();
        int tries = job.incrementCurrentAttempts();
        switch (job.recoverReason()) {
            case OUT_OF_REACH -> recoverOutOfReach(job, target, tries);
            case PROTECTED_BLOCK -> {
                job.recordProtectedBlocked();
                job.recordSkipped();
                job.noteEvent("skip(protected) target=" + shortTarget(target));
                job.advance();
            }
            case BLOCKED_STRICT_AIR -> recoverBlocked(job, target, tries, "strict_air");
            case BLOCKED_SOLID -> recoverBlocked(job, target, tries, "solid");
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
            case PLACE_FAILED -> recoverPlaceFailure(job, target, tries);
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

    private static void recoverOutOfReach(PlacementJob job, BlockPos target, int tries) {
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

    private static void recoverBlocked(PlacementJob job, BlockPos target, int tries, String reason) {
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

    private static void recoverPlaceFailure(PlacementJob job, BlockPos target, int tries) {
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

    private static String detailSummary(String state, PlacementJob job) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Bladelow] ").append(state).append(" ").append(job.progressSummary());
        if (!job.isComplete()) {
            BlockPos target = job.currentTarget();
            sb.append(" nextBlock=").append(blockId(job));
            sb.append(" nextTarget=").append(shortTarget(target));
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
        String saveStatus = BladelowLearning.save();
        player.sendMessage(blueText("[Bladelow] model " + saveStatus), false);
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
}
