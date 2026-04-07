package com.bladelow.ml;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads offline-trained priors produced by the external training script.
 *
 * The runtime still relies on deterministic planners plus lightweight online
 * learning, but this file lets an offline pass feed stronger priors back into
 * lot intent prediction without requiring a heavy inference runtime in-game.
 */
public final class OfflineTrainingModel {
    private static final Gson GSON = new Gson();
    private static final Path MODEL_PATH = Path.of("config", "bladelow", "ml", "offline_model.json");

    private long fingerprint = Long.MIN_VALUE;
    private String lastError = "";
    private ModelFile cache = new ModelFile();

    public synchronized BuildIntent suggest(BuildIntentContext context) {
        refreshIfNeeded();
        if (context == null) {
            return BuildIntent.NONE;
        }

        Prior zonePrior = priorForZone(normalize(context.zoneType()));
        Prior themePrior = priorForTheme(primaryTheme(context));
        if (zonePrior == null && themePrior == null) {
            return BuildIntent.NONE;
        }

        Prior chosen = zonePrior != null ? zonePrior : themePrior;
        String primaryTheme = !chosen.primaryTheme.isBlank() ? chosen.primaryTheme : primaryTheme(context);
        String secondaryTheme = !chosen.secondaryTheme.isBlank() ? chosen.secondaryTheme : secondaryTheme(context, primaryTheme);
        return new BuildIntent(
            chosen.archetype,
            chosen.sizeClass,
            chosen.floors,
            chosen.roofFamily,
            chosen.paletteProfile,
            chosen.detailDensity,
            primaryTheme,
            secondaryTheme,
            chosen.confidence,
            chosen.samples
        );
    }

    public synchronized Snapshot snapshot() {
        refreshIfNeeded();
        return new Snapshot(
            Files.exists(MODEL_PATH),
            blankIfNull(cache.generatedAt),
            cache.sampleCounts == null ? 0 : cache.sampleCounts.placementEvents,
            cache.sampleCounts == null ? 0 : cache.sampleCounts.environmentObservations,
            cache.sampleCounts == null ? 0 : cache.sampleCounts.buildIntentExamples,
            cache.sampleCounts == null ? 0 : cache.sampleCounts.styleExamples,
            topThemeLabels(cache.topThemes),
            new ArrayList<>(normalizedZonePriors().keySet())
        );
    }

    public synchronized String summary() {
        refreshIfNeeded();
        Snapshot snapshot = snapshot();
        StringBuilder out = new StringBuilder();
        out.append("offline[path=").append(MODEL_PATH)
            .append(" trained=").append(snapshot.trained())
            .append(" zones=").append(snapshot.zoneKeys().size())
            .append(" themes=").append(snapshot.topThemes().size())
            .append("]");
        if (!lastError.isBlank()) {
            out.append(" offlineError=").append(lastError);
        }
        return out.toString();
    }

    private void refreshIfNeeded() {
        try {
            Path dir = MODEL_PATH.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            long currentFingerprint = datasetFingerprint(MODEL_PATH);
            if (currentFingerprint == fingerprint) {
                return;
            }
            fingerprint = currentFingerprint;
            lastError = "";
            cache = loadModel();
        } catch (IOException ex) {
            lastError = ex.getMessage();
        }
    }

    private ModelFile loadModel() {
        if (!Files.exists(MODEL_PATH)) {
            return new ModelFile();
        }
        try (Reader reader = Files.newBufferedReader(MODEL_PATH)) {
            ModelFile file = GSON.fromJson(reader, ModelFile.class);
            return file == null ? new ModelFile() : file.normalized();
        } catch (IOException | JsonSyntaxException ex) {
            lastError = ex.getMessage();
            return new ModelFile();
        }
    }

    private Prior priorForZone(String zone) {
        if (zone.isBlank()) {
            return null;
        }
        return normalizedZonePriors().get(zone);
    }

    private Prior priorForTheme(String theme) {
        if (theme.isBlank()) {
            return null;
        }
        return normalizedThemePriors().get(theme);
    }

    private Map<String, Prior> normalizedZonePriors() {
        Map<String, Prior> map = new LinkedHashMap<>();
        if (cache.zonePriors == null) {
            return map;
        }
        for (Map.Entry<String, Prior> entry : cache.zonePriors.entrySet()) {
            String key = normalize(entry.getKey());
            Prior value = entry.getValue() == null ? null : entry.getValue().normalized();
            if (!key.isBlank() && value != null) {
                map.put(key, value);
            }
        }
        return map;
    }

    private Map<String, Prior> normalizedThemePriors() {
        Map<String, Prior> map = new LinkedHashMap<>();
        if (cache.themePriors == null) {
            return map;
        }
        for (Map.Entry<String, Prior> entry : cache.themePriors.entrySet()) {
            String key = normalize(entry.getKey());
            Prior value = entry.getValue() == null ? null : entry.getValue().normalized();
            if (!key.isBlank() && value != null) {
                map.put(key, value);
            }
        }
        return map;
    }

    private static long datasetFingerprint(Path path) throws IOException {
        if (!Files.exists(path)) {
            return 0L;
        }
        return Files.getLastModifiedTime(path).toMillis() ^ Files.size(path);
    }

    private static String primaryTheme(BuildIntentContext context) {
        String learned = normalize(context.learnedPrimaryTheme());
        if (!learned.isBlank()) {
            return learned;
        }
        return normalize(context.stylePrimaryTheme());
    }

    private static String secondaryTheme(BuildIntentContext context, String primaryTheme) {
        String learned = normalize(context.learnedSecondaryTheme());
        if (!learned.isBlank() && !learned.equals(primaryTheme)) {
            return learned;
        }
        String style = normalize(context.styleSecondaryTheme());
        if (!style.isBlank() && !style.equals(primaryTheme)) {
            return style;
        }
        return "";
    }

    private static List<String> topThemeLabels(List<NamedCount> themes) {
        if (themes == null || themes.isEmpty()) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < Math.min(4, themes.size()); i++) {
            NamedCount entry = themes.get(i);
            if (entry == null) {
                continue;
            }
            String name = normalize(entry.name);
            if (name.isBlank()) {
                continue;
            }
            labels.add(name + "(" + Math.max(0, entry.count) + ")");
        }
        return List.copyOf(labels);
    }

    private static String blankIfNull(String text) {
        return text == null ? "" : text;
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase(Locale.ROOT);
    }

    public record Snapshot(
        boolean trained,
        String generatedAt,
        long placementEvents,
        long environmentObservations,
        long buildIntentExamples,
        long styleExamples,
        List<String> topThemes,
        List<String> zoneKeys
    ) {
    }

    private static final class ModelFile {
        int version;
        String generatedAt;
        SampleCounts sampleCounts;
        List<NamedCount> topThemes;
        List<NamedCount> topPalettes;
        Map<String, Prior> zonePriors;
        Map<String, Prior> themePriors;

        private ModelFile normalized() {
            if (sampleCounts == null) {
                sampleCounts = new SampleCounts();
            }
            if (topThemes == null) {
                topThemes = List.of();
            }
            if (topPalettes == null) {
                topPalettes = List.of();
            }
            if (zonePriors == null) {
                zonePriors = Map.of();
            }
            if (themePriors == null) {
                themePriors = Map.of();
            }
            return this;
        }
    }

    private static final class SampleCounts {
        long placementEvents;
        long environmentObservations;
        long buildIntentExamples;
        long styleExamples;
    }

    private static final class NamedCount {
        String name;
        int count;
    }

    private static final class Prior {
        String archetype;
        String sizeClass;
        int floors;
        String roofFamily;
        String paletteProfile;
        String detailDensity;
        String primaryTheme;
        String secondaryTheme;
        double confidence;
        int samples;

        private Prior normalized() {
            archetype = normalize(archetype);
            sizeClass = normalize(sizeClass);
            roofFamily = normalize(roofFamily);
            paletteProfile = normalize(paletteProfile);
            detailDensity = normalize(detailDensity);
            primaryTheme = normalize(primaryTheme);
            secondaryTheme = normalize(secondaryTheme);
            floors = Math.max(1, floors);
            confidence = Math.max(0.0, Math.min(1.0, confidence));
            samples = Math.max(0, samples);
            return this;
        }
    }
}
