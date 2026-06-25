package com.bladelow.ml;

import com.bladelow.builder.PlacementJob;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Logs completed execution outcomes so later training can score plans.
 */
public final class BuildEvaluationLogger {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Path DATASET_PATH = Path.of("config", "bladelow", "ml", "build_evaluations.jsonl");

    private long samples = -1L;
    private String lastError = "";

    public synchronized void recordCompletion(ServerPlayerEntity player, PlacementJob job) {
        if (player == null || job == null) {
            return;
        }
        try {
            Files.createDirectories(DATASET_PATH.getParent());
            BuildEvaluation event = new BuildEvaluation(
                Instant.now().toString(),
                player.getUuidAsString(),
                player.getEntityWorld().getRegistryKey().getValue().toString(),
                normalize(job.tag()),
                job.totalTargets(),
                job.placedCount(),
                job.skippedCount(),
                job.failedCount(),
                job.movedCount(),
                job.deferredCount(),
                job.alreadyPlacedCount(),
                job.blockedCount(),
                job.protectedBlockedCount(),
                job.noReachCount(),
                job.mlRejectedCount(),
                job.stuckEventsCount(),
                job.pathReplansCount(),
                job.backtracksCount(),
                job.blacklistHitsCount(),
                job.averageScore(),
                job.noReachPercent(),
                normalize(job.lastEvent())
            );
            try (Writer out = Files.newBufferedWriter(
                DATASET_PATH,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
            )) {
                GSON.toJson(event, out);
                out.write('\n');
            }
            if (samples >= 0) {
                samples++;
            } else {
                sampleCount();
            }
            lastError = "";
        } catch (IOException ex) {
            lastError = ex.getMessage();
        }
    }

    public synchronized String summary() {
        StringBuilder out = new StringBuilder();
        out.append("buildEval[path=").append(DATASET_PATH).append(" samples=").append(sampleCount()).append("]");
        if (!lastError.isBlank()) {
            out.append(" buildEvalError=").append(lastError);
        }
        return out.toString();
    }

    public synchronized long sampleCount() {
        if (samples >= 0) {
            return samples;
        }
        if (!Files.exists(DATASET_PATH)) {
            samples = 0;
            return samples;
        }
        try (var lines = Files.lines(DATASET_PATH)) {
            samples = lines.count();
        } catch (IOException ex) {
            lastError = ex.getMessage();
            samples = 0;
        }
        return samples;
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase();
    }

    private record BuildEvaluation(
        String createdAt,
        String playerId,
        String worldId,
        String tag,
        int totalTargets,
        int placed,
        int skipped,
        int failed,
        int moved,
        int deferred,
        int alreadyPlaced,
        int blocked,
        int protectedBlocked,
        int noReach,
        int mlRejected,
        int stuckEvents,
        int pathReplans,
        int backtracks,
        int blacklistHits,
        double averageScore,
        double noReachPercent,
        String lastEvent
    ) {
    }
}
