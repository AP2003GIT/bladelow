package com.bladelow.ml;

import com.bladelow.builder.TownBlueprint;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.world.ServerWorld;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Writes planner-approved lot decisions to a local dataset.
 *
 * These rows are the training signal for the first lot-level model:
 * "given this site context, what kind of building did Bladelow decide fit?"
 */
public final class BuildIntentExampleLogger {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Path DATASET_PATH = Path.of("config", "bladelow", "ml", "build_intent_examples.jsonl");

    private long samples = -1L;
    private String lastError = "";

    public static Path datasetPath() {
        return DATASET_PATH;
    }

    public synchronized void recordTownPlacement(
        String source,
        ServerWorld world,
        BuildIntentContext context,
        TownBlueprint blueprint
    ) {
        if (world == null || context == null || blueprint == null) {
            return;
        }
        try {
            Files.createDirectories(DATASET_PATH.getParent());
            BuildIntentExample event = new BuildIntentExample(
                Instant.now().toString(),
                source == null ? "" : source,
                world.getRegistryKey().getValue().toString(),
                context.zoneType(),
                context.areaWidth(),
                context.areaDepth(),
                context.centerScore(),
                context.wallScore(),
                context.gateScore(),
                context.roadScore(),
                context.primaryRoad(),
                context.terrainSpan(),
                context.nearbyStructureCount(),
                context.stylePrimaryTheme(),
                context.styleSecondaryTheme(),
                context.styleAverageWidth(),
                context.styleAverageDepth(),
                context.styleAverageHeight(),
                context.learnedPrimaryTheme(),
                context.learnedSecondaryTheme(),
                context.learnedConfidence(),
                blueprint.name(),
                BuildIntent.archetypeFor(blueprint),
                BuildIntent.sizeClassFor(blueprint),
                BuildIntent.floorsFor(blueprint),
                BuildIntent.roofFamilyFor(blueprint),
                BuildIntent.paletteProfileFor(blueprint),
                BuildIntent.detailDensityFor(blueprint)
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
        out.append("intent[path=").append(DATASET_PATH).append(" samples=").append(sampleCount()).append("]");
        if (!lastError.isBlank()) {
            out.append(" intentError=").append(lastError);
        }
        return out.toString();
    }

    private long sampleCount() {
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

    private record BuildIntentExample(
        String timestamp,
        String source,
        String dimension,
        String zoneType,
        int areaWidth,
        int areaDepth,
        double centerScore,
        double wallScore,
        double gateScore,
        double roadScore,
        boolean primaryRoad,
        int terrainSpan,
        int nearbyStructureCount,
        String stylePrimaryTheme,
        String styleSecondaryTheme,
        double styleAverageWidth,
        double styleAverageDepth,
        double styleAverageHeight,
        String learnedPrimaryTheme,
        String learnedSecondaryTheme,
        double learnedConfidence,
        String blueprintName,
        String archetype,
        String sizeClass,
        int floors,
        String roofFamily,
        String paletteProfile,
        String detailDensity
    ) {
    }
}
