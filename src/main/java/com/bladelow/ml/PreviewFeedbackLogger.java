package com.bladelow.ml;

import com.bladelow.builder.IntentStructurePlanner;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Stores explicit user feedback about generated previews.
 *
 * Preview generation is where Bladelow starts making architectural decisions on
 * its own. These rows tell later training passes which generated plans were
 * accepted, rerolled, or rejected before the builder committed them.
 */
public final class PreviewFeedbackLogger {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Path DATASET_PATH = Path.of("config", "bladelow", "ml", "preview_feedback.jsonl");

    private long samples = -1L;
    private String lastError = "";

    public static Path datasetPath() {
        return DATASET_PATH;
    }

    public synchronized void recordFeedback(
        String source,
        String outcome,
        ServerWorld world,
        BlockPos from,
        BlockPos to,
        IntentStructurePlanner.GeneratedBuild plan,
        int variant
    ) {
        if (world == null || from == null || to == null || plan == null || !plan.ok() || plan.blueprint() == null || plan.intent() == null) {
            return;
        }
        try {
            Files.createDirectories(DATASET_PATH.getParent());
            PreviewFeedback event = new PreviewFeedback(
                Instant.now().toString(),
                normalize(source),
                normalize(outcome),
                world.getRegistryKey().getValue().toString(),
                Math.min(from.getX(), to.getX()),
                Math.max(from.getX(), to.getX()),
                Math.min(from.getY(), to.getY()),
                Math.max(from.getY(), to.getY()),
                Math.min(from.getZ(), to.getZ()),
                Math.max(from.getZ(), to.getZ()),
                variant,
                normalize(plan.blueprint().name()),
                normalize(plan.intent().primaryArchetype()),
                normalize(plan.intent().sizeClass()),
                Math.max(1, plan.intent().floors()),
                normalize(plan.intent().roofFamily()),
                normalize(plan.intent().paletteProfile()),
                normalize(plan.intent().detailDensity()),
                normalize(plan.intent().primaryTheme()),
                normalize(plan.intent().secondaryTheme()),
                plan.intent().confidence(),
                plan.minX(),
                plan.maxX(),
                plan.minZ(),
                plan.maxZ(),
                plan.entranceWorldX(),
                plan.entranceWorldZ()
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
        out.append("previewFeedback[path=").append(DATASET_PATH).append(" samples=").append(sampleCount()).append("]");
        if (!lastError.isBlank()) {
            out.append(" previewFeedbackError=").append(lastError);
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

    private record PreviewFeedback(
        String timestamp,
        String source,
        String outcome,
        String dimension,
        int selMinX,
        int selMaxX,
        int selMinY,
        int selMaxY,
        int selMinZ,
        int selMaxZ,
        int variant,
        String blueprintName,
        String archetype,
        String sizeClass,
        int floors,
        String roofFamily,
        String paletteProfile,
        String detailDensity,
        String primaryTheme,
        String secondaryTheme,
        double confidence,
        int previewMinX,
        int previewMaxX,
        int previewMinZ,
        int previewMaxZ,
        int entranceX,
        int entranceZ
    ) {
    }
}
