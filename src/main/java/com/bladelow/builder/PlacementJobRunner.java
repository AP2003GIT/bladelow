package com.bladelow.builder;

import com.bladelow.ml.BladelowLearning;
import com.bladelow.ml.PlacementFeatureExtractor;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

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

    public static boolean submit(PlacementJob job) {
        return JOBS.put(job.playerId(), job) != null;
    }

    public static void queueOrPreview(MinecraftServer server, PlacementJob job) {
        if (!BuildRuntimeSettings.previewBeforeBuild()) {
            submit(job);
            return;
        }
        PENDING.put(job.playerId(), job);
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(job.playerId());
        if (player != null) {
            player.sendMessage(Text.literal("[Bladelow] preview ready. Use /bladeconfirm to start or /bladecancel to discard."), false);
            preview(job, server, player);
        }
    }

    public static boolean cancel(UUID playerId) {
        boolean active = JOBS.remove(playerId) != null;
        boolean pending = PENDING.remove(playerId) != null;
        return active || pending;
    }

    public static boolean confirm(UUID playerId) {
        PlacementJob pending = PENDING.remove(playerId);
        if (pending == null) {
            return false;
        }
        JOBS.put(playerId, pending);
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
        PlacementJob pending = PENDING.get(playerId);
        if (pending != null) {
            return "[Bladelow] pending preview " + pending.progressSummary().replace(" progress 0/", " ");
        }
        PlacementJob job = JOBS.get(playerId);
        if (job == null) {
            return "[Bladelow] no active build";
        }
        return job.progressSummary();
    }

    public static boolean hasActive(UUID playerId) {
        return JOBS.containsKey(playerId);
    }

    public static boolean hasPending(UUID playerId) {
        return PENDING.containsKey(playerId);
    }

    public static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, PlacementJob>> it = JOBS.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<UUID, PlacementJob> entry = it.next();
            PlacementJob job = entry.getValue();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(job.playerId());
            if (player == null) {
                it.remove();
                continue;
            }

            if (job.isComplete()) {
                finishJob(player, job);
                it.remove();
                continue;
            }
            job.tick();

            var world = server.getWorld(job.worldKey());
            if (world == null) {
                player.sendMessage(Text.literal("[Bladelow] Target world unavailable. Build canceled."), false);
                it.remove();
                continue;
            }

            var target = job.currentTarget();
            int moveStatus = BuildNavigation.ensureInRangeForPlacement(world, player, target);
            if (moveStatus < 0) {
                int tries = job.incrementCurrentAttempts();
                if (tries >= MAX_RETRIES_PER_TARGET) {
                    job.recordNoReach();
                    job.recordSkipped();
                    job.advance();
                }
                if (job.shouldReportProgress()) {
                    player.sendMessage(Text.literal(job.progressSummary()), false);
                }
                continue;
            }
            if (moveStatus > 0) {
                job.recordMoved();
            }

            var desiredState = job.currentBlock().getDefaultState();
            var existingState = world.getBlockState(target);
            if (existingState.isOf(job.currentBlock())) {
                job.recordAlreadyPlaced();
                job.recordSkipped();
                job.advance();
                if (job.shouldReportProgress()) {
                    player.sendMessage(Text.literal(job.progressSummary()), false);
                }
                continue;
            }
            if (BuildSafetyPolicy.isProtected(existingState)) {
                job.recordProtectedBlocked();
                job.recordSkipped();
                job.advance();
                if (job.shouldReportProgress()) {
                    player.sendMessage(Text.literal(job.progressSummary()), false);
                }
                continue;
            }
            if (BuildRuntimeSettings.strictAirOnly() && !existingState.isAir()) {
                job.recordBlocked();
                job.recordSkipped();
                job.advance();
                if (job.shouldReportProgress()) {
                    player.sendMessage(Text.literal(job.progressSummary()), false);
                }
                continue;
            }
            if (!existingState.isAir() && !existingState.isReplaceable()) {
                job.recordBlocked();
                job.recordSkipped();
                job.advance();
                if (job.shouldReportProgress()) {
                    player.sendMessage(Text.literal(job.progressSummary()), false);
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
                model.train(features, false);
                job.advance();
                if (job.shouldReportProgress()) {
                    player.sendMessage(Text.literal(job.progressSummary()), false);
                }
                continue;
            }

            boolean changed = world.setBlockState(target, desiredState);
            if (changed) {
                job.recordPlaced();
                job.advance();
            } else {
                int tries = job.incrementCurrentAttempts();
                if (tries >= MAX_RETRIES_PER_TARGET) {
                    job.recordFailed();
                    job.advance();
                }
            }
            model.train(features, changed);
            if (job.shouldReportProgress()) {
                player.sendMessage(Text.literal(job.progressSummary()), false);
            }

            if (job.isComplete()) {
                finishJob(player, job);
                it.remove();
            }
        }
    }

    private static void finishJob(ServerPlayerEntity player, PlacementJob job) {
        player.sendMessage(Text.literal(job.completionSummary()), false);
        String saveStatus = BladelowLearning.save();
        player.sendMessage(Text.literal("[Bladelow] model " + saveStatus), false);
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
        player.sendMessage(Text.literal("[Bladelow] preview markers shown: " + shown), false);
    }
}
