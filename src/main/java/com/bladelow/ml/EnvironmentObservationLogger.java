package com.bladelow.ml;

import com.bladelow.builder.BuildSiteScan;
import com.bladelow.builder.SiteStyleProfile;
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
import java.util.List;

/**
 * Persists higher-level site observations produced by world scans.
 *
 * These rows are the bridge between deterministic site analysis and learned
 * style memory: instead of teaching from raw chunks later, we keep the planner's
 * summary of an area as structured training data now.
 */
public final class EnvironmentObservationLogger {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Path DATASET_PATH = Path.of("config", "bladelow", "ml", "environment_observations.jsonl");

    private long samples = -1L;
    private String lastError = "";

    public static Path datasetPath() {
        return DATASET_PATH;
    }

    public synchronized void recordScan(
        String source,
        ServerWorld world,
        BlockPos from,
        BlockPos to,
        BuildSiteScan scan
    ) {
        if (world == null || from == null || to == null || scan == null || scan == BuildSiteScan.EMPTY) {
            return;
        }

        try {
            Files.createDirectories(DATASET_PATH.getParent());
            SiteStyleProfile profile = scan.styleProfile();
            List<BuildSiteScan.NearbyStructure> nearby = scan.nearbyStructures();
            // Store only the planner-facing summary so later memory/model code
            // can compare sites by useful features instead of raw block dumps.
            EnvironmentObservation event = new EnvironmentObservation(
                Instant.now().toString(),
                source == null ? "" : source,
                world.getRegistryKey().getValue().toString(),
                min(from.getX(), to.getX()),
                max(from.getX(), to.getX()),
                min(from.getY(), to.getY()),
                max(from.getY(), to.getY()),
                min(from.getZ(), to.getZ()),
                max(from.getZ(), to.getZ()),
                scan.terrainMinY(),
                scan.terrainMaxY(),
                scan.terrainAverageY(),
                profile == null ? "" : profile.primaryTheme(),
                profile == null ? "" : profile.secondaryTheme(),
                profile == null ? 0 : profile.samples(),
                profile == null ? 0 : profile.nearbyStructures(),
                profile == null ? 0.0 : profile.averageWidth(),
                profile == null ? 0.0 : profile.averageDepth(),
                profile == null ? 0.0 : profile.averageHeight(),
                nearby == null ? 0 : nearby.size(),
                averageArea(nearby),
                averageAspect(nearby)
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
        out.append("env[path=").append(DATASET_PATH).append(" samples=").append(sampleCount()).append("]");
        if (!lastError.isBlank()) {
            out.append(" envError=").append(lastError);
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

    private static double averageArea(List<BuildSiteScan.NearbyStructure> nearby) {
        if (nearby == null || nearby.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (BuildSiteScan.NearbyStructure structure : nearby) {
            sum += structure.area();
        }
        return sum / nearby.size();
    }

    private static double averageAspect(List<BuildSiteScan.NearbyStructure> nearby) {
        if (nearby == null || nearby.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (BuildSiteScan.NearbyStructure structure : nearby) {
            sum += structure.aspectRatio();
        }
        return sum / nearby.size();
    }

    private static int min(int a, int b) {
        return Math.min(a, b);
    }

    private static int max(int a, int b) {
        return Math.max(a, b);
    }

    private record EnvironmentObservation(
        String timestamp,
        String source,
        String dimension,
        int minX,
        int maxX,
        int minY,
        int maxY,
        int minZ,
        int maxZ,
        int terrainMinY,
        int terrainMaxY,
        int terrainAverageY,
        String primaryTheme,
        String secondaryTheme,
        int styleSamples,
        int styleNearbyStructures,
        double averageWidth,
        double averageDepth,
        double averageHeight,
        int nearbyStructureCount,
        double nearbyAverageArea,
        double nearbyAverageAspect
    ) {
    }
}
