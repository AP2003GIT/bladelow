package com.bladelow.client;

import com.bladelow.ml.BladelowLearning;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Lightweight client-side view of local training assets.
 *
 * The HUD uses this to show dataset counts and top learned themes without
 * waiting for a server command round-trip.
 */
public final class BladelowModelStatus {
    private static final Gson GSON = new Gson();
    private static final Path ML_DIR = Path.of("config", "bladelow", "ml");
    private static final Path PLACEMENT_DATASET = ML_DIR.resolve("placement_style_events.jsonl");
    private static final Path ENVIRONMENT_DATASET = ML_DIR.resolve("environment_observations.jsonl");
    private static final Path BUILD_INTENT_DATASET = ML_DIR.resolve("build_intent_examples.jsonl");
    private static final Path STYLE_EXAMPLES_DATASET = ML_DIR.resolve("style_examples.jsonl");
    private static final Path STYLE_REFS_DIR = ML_DIR.resolve("style_refs");

    private static long fingerprint = Long.MIN_VALUE;
    private static Snapshot cached = Snapshot.empty();

    private BladelowModelStatus() {
    }

    public static synchronized Snapshot snapshot() {
        long currentFingerprint = fingerprint();
        if (currentFingerprint != fingerprint) {
            fingerprint = currentFingerprint;
            cached = loadSnapshot();
        }
        return cached;
    }

    private static Snapshot loadSnapshot() {
        long placement = countLines(PLACEMENT_DATASET);
        long environment = countLines(ENVIRONMENT_DATASET);
        long intent = countLines(BUILD_INTENT_DATASET);
        long styleExamples = countLines(STYLE_EXAMPLES_DATASET);
        long refs = countImages(STYLE_REFS_DIR);
        List<String> rawThemes = topThemesFromRawData();
        var offline = BladelowLearning.offlineModel().snapshot();
        List<String> learnedThemes = offline.topThemes().isEmpty() ? rawThemes : offline.topThemes();
        return new Snapshot(
            placement,
            environment,
            intent,
            styleExamples,
            refs,
            offline.trained(),
            offline.generatedAt(),
            learnedThemes,
            offline.zoneKeys()
        );
    }

    private static long fingerprint() {
        return fingerprintFor(PLACEMENT_DATASET)
            ^ fingerprintFor(ENVIRONMENT_DATASET)
            ^ fingerprintFor(BUILD_INTENT_DATASET)
            ^ fingerprintFor(STYLE_EXAMPLES_DATASET)
            ^ fingerprintForDirectory(STYLE_REFS_DIR)
            ^ BladelowLearning.offlineModel().snapshot().generatedAt().hashCode();
    }

    private static long fingerprintFor(Path path) {
        if (!Files.exists(path)) {
            return 0L;
        }
        try {
            return Files.getLastModifiedTime(path).toMillis() ^ Files.size(path);
        } catch (IOException ex) {
            return path.toString().hashCode();
        }
    }

    private static long fingerprintForDirectory(Path path) {
        if (!Files.isDirectory(path)) {
            return 0L;
        }
        try (Stream<Path> stream = Files.list(path)) {
            return stream
                .filter(Files::isRegularFile)
                .mapToLong(BladelowModelStatus::fingerprintFor)
                .sum();
        } catch (IOException ex) {
            return path.toString().hashCode();
        }
    }

    private static long countLines(Path path) {
        if (!Files.exists(path)) {
            return 0L;
        }
        try (var lines = Files.lines(path)) {
            return lines.count();
        } catch (IOException ex) {
            return 0L;
        }
    }

    private static long countImages(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(BladelowModelStatus::isImageFile)
                .count();
        } catch (IOException ex) {
            return 0L;
        }
    }

    private static List<String> topThemesFromRawData() {
        Map<String, Integer> counts = new HashMap<>();
        accumulateThemes(ENVIRONMENT_DATASET, counts, 1);
        accumulateThemes(STYLE_EXAMPLES_DATASET, counts, 3);
        return counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
            .limit(4)
            .map(entry -> entry.getKey() + "(" + entry.getValue() + ")")
            .toList();
    }

    private static void accumulateThemes(Path path, Map<String, Integer> counts, int weight) {
        if (!Files.exists(path)) {
            return;
        }
        try (var lines = Files.lines(path)) {
            lines.forEach(line -> {
                if (line == null || line.isBlank()) {
                    return;
                }
                try {
                    JsonObject json = GSON.fromJson(line, JsonObject.class);
                    if (json == null) {
                        return;
                    }
                    voteTheme(counts, readString(json, "primaryTheme"), weight * 2);
                    voteTheme(counts, readString(json, "secondaryTheme"), weight);
                } catch (JsonSyntaxException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static void voteTheme(Map<String, Integer> counts, String theme, int weight) {
        String normalized = normalize(theme);
        if (normalized.isBlank()) {
            return;
        }
        counts.merge(normalized, weight, Integer::sum);
    }

    private static String readString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        try {
            return json.get(key).getAsString();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static boolean isImageFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase(Locale.ROOT);
    }

    public record Snapshot(
        long placementSamples,
        long environmentSamples,
        long intentSamples,
        long styleExampleSamples,
        long referenceImages,
        boolean offlineModelPresent,
        String generatedAt,
        List<String> learnedThemes,
        List<String> zonePriors
    ) {
        private static Snapshot empty() {
            return new Snapshot(0L, 0L, 0L, 0L, 0L, false, "", List.of(), List.of());
        }

        public List<String> lines() {
            List<String> out = new ArrayList<>();
            out.add("placements=" + placementSamples + " env=" + environmentSamples + " intent=" + intentSamples);
            out.add("style examples=" + styleExampleSamples + " refs=" + referenceImages);
            out.add(offlineModelPresent
                ? "offline model: trained"
                : "offline model: not trained");
            if (offlineModelPresent && generatedAt != null && !generatedAt.isBlank()) {
                out.add("generated: " + generatedAt);
            }
            out.add("themes: " + (learnedThemes.isEmpty() ? "none yet" : String.join(", ", learnedThemes)));
            out.add("zones: " + (zonePriors.isEmpty() ? "none yet" : String.join(", ", zonePriors)));
            return out;
        }
    }
}
