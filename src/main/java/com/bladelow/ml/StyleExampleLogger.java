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
 * Stores player-curated area scans as style examples for offline training.
 *
 * Unlike general environment observations, these samples are explicitly saved
 * by the user from the HUD, so later training scripts can give them more weight
 * when learning district style priors.
 */
public final class StyleExampleLogger {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Path DATASET_PATH = Path.of("config", "bladelow", "ml", "style_examples.jsonl");

    private long samples = -1L;
    private String lastError = "";

    public static Path datasetPath() {
        return DATASET_PATH;
    }

    public synchronized void recordExample(
        String source,
        String label,
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
            StyleExample event = new StyleExample(
                Instant.now().toString(),
                normalize(source),
                normalize(label),
                world.getRegistryKey().getValue().toString(),
                Math.min(from.getX(), to.getX()),
                Math.max(from.getX(), to.getX()),
                Math.min(from.getY(), to.getY()),
                Math.max(from.getY(), to.getY()),
                Math.min(from.getZ(), to.getZ()),
                Math.max(from.getZ(), to.getZ()),
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
        out.append("styleExamples[path=").append(DATASET_PATH).append(" samples=").append(sampleCount()).append("]");
        if (!lastError.isBlank()) {
            out.append(" styleExampleError=").append(lastError);
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

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase();
    }

    private record StyleExample(
        String timestamp,
        String source,
        String label,
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
