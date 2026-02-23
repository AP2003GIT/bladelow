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

            if (job.runtimeSettings().targetSchedulerEnabled()) {
                job.selectBestTargetNear(player.getBlockPos(), job.runtimeSettings().schedulerLookahead());
            }

            var target = job.currentTarget();
            int moveStatus = BuildNavigation.ensureInRangeForPlacement(world, player, target, job.runtimeSettings());
            if (moveStatus < 0) {
                int tries = job.incrementCurrentAttempts();
                if (tries >= MAX_RETRIES_PER_TARGET) {
                    boolean wasDeferred = false;
                    if (job.runtimeSettings().deferUnreachableTargets()
                        && job.currentDeferrals() < job.runtimeSettings().maxTargetDeferrals()) {
                        wasDeferred = job.deferCurrentToTail();
                    }

                    job.recordNoReach();
                    if (!wasDeferred) {
                        job.recordSkipped();
                        job.noteEvent("out_of_reach->skipped target=" + shortTarget(target));
                        job.advance();
                    } else {
                        job.noteEvent("out_of_reach->deferred target=" + shortTarget(target));
                    }
                } else {
                    job.noteEvent("out_of_reach retry=" + tries + "/" + MAX_RETRIES_PER_TARGET + " target=" + shortTarget(target));
                }
                if (job.shouldReportProgress()) {
                    player.sendMessage(blueText(job.progressSummary()), false);
                }
                continue;
            }
            if (moveStatus > 0) {
                job.recordMoved();
                job.noteEvent("moved target=" + shortTarget(target));
            }

            var desiredState = job.currentBlock().getDefaultState();
            var existingState = world.getBlockState(target);
            if (existingState.isOf(job.currentBlock())) {
                job.recordAlreadyPlaced();
                job.recordSkipped();
                job.noteEvent("already target=" + shortTarget(target));
                job.advance();
                if (job.shouldReportProgress()) {
                    player.sendMessage(blueText(job.progressSummary()), false);
                }
                continue;
            }
            if (BuildSafetyPolicy.isProtected(existingState)) {
                job.recordProtectedBlocked();
                job.recordSkipped();
                job.noteEvent("protected target=" + shortTarget(target));
                job.advance();
                if (job.shouldReportProgress()) {
                    player.sendMessage(blueText(job.progressSummary()), false);
                }
                continue;
            }
            if (job.runtimeSettings().strictAirOnly() && !existingState.isAir()) {
                job.recordBlocked();
                job.recordSkipped();
                job.noteEvent("blocked(strict_air) target=" + shortTarget(target));
                job.advance();
                if (job.shouldReportProgress()) {
                    player.sendMessage(blueText(job.progressSummary()), false);
                }
                continue;
            }
            if (!existingState.isAir() && !existingState.isReplaceable()) {
                job.recordBlocked();
                job.recordSkipped();
                job.noteEvent("blocked(non_replaceable) target=" + shortTarget(target));
                job.advance();
                if (job.shouldReportProgress()) {
                    player.sendMessage(blueText(job.progressSummary()), false);
                }
                continue;
            }

            if (!hasPlacementItemIfNeeded(player, job.currentBlock())) {
                job.recordSkipped();
                job.noteEvent("no_item block=" + blockId(job) + " target=" + shortTarget(target));
                job.advance();
                if (job.shouldReportProgress()) {
                    player.sendMessage(blueText(job.progressSummary()), false);
                }
                continue;
            }

            var features = PlacementFeatureExtractor.extract(world, player, target);
            var model = BladelowLearning.model();
            double score = model.score(features);
            job.addScore(score);

            if (!model.shouldPlace(features)) {
                job.recordMlRejected();
                job.recordSkipped();
                job.noteEvent("ml_skip target=" + shortTarget(target));
                model.train(features, false);
                job.advance();
                if (job.shouldReportProgress()) {
                    player.sendMessage(blueText(job.progressSummary()), false);
                }
                continue;
            }

            boolean changed = world.setBlockState(target, desiredState);
            if (changed) {
                consumePlacementItemAfterSuccess(player, job.currentBlock());
                job.recordPlaced();
                job.noteEvent("placed block=" + blockId(job) + " target=" + shortTarget(target));
                job.advance();
            } else {
                int tries = job.incrementCurrentAttempts();
                if (tries >= MAX_RETRIES_PER_TARGET) {
                    job.recordFailed();
                    job.noteEvent("failed(set_block) block=" + blockId(job) + " target=" + shortTarget(target));
                    job.advance();
                } else {
                    job.noteEvent("set_block_retry=" + tries + "/" + MAX_RETRIES_PER_TARGET + " target=" + shortTarget(target));
                }
            }
            model.train(features, changed);
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

    private static String detailSummary(String state, PlacementJob job) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Bladelow] ").append(state).append(" ").append(job.progressSummary());
        if (!job.isComplete()) {
            BlockPos target = job.currentTarget();
            sb.append(" nextBlock=").append(blockId(job));
            sb.append(" nextTarget=").append(shortTarget(target));
            sb.append(" attempts=").append(job.currentAttempts());
            sb.append(" defers=").append(job.currentDeferrals());
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
