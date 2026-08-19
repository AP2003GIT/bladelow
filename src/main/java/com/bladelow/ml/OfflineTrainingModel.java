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
    private static final List<String> GENERATION_FEATURE_NAMES = List.of(
        "widthFill",
        "depthFill",
        "areaFill",
        "compactness",
        "floors",
        "roofFill"
    );

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

    /**
     * Score one concrete procedural design with the preference model trained
     * from accepted versus rejected/rerolled previews.
     */
    public synchronized GenerationScore scoreGeneration(GenerationFeatures features) {
        refreshIfNeeded();
        GenerationPreference preference = cache.generationPreference;
        if (features == null || preference == null || !preference.usable()) {
            return GenerationScore.untrained();
        }

        double[] values = features.values();
        double logit = preference.bias;
        for (int i = 0; i < values.length; i++) {
            double scale = Math.max(0.0001, preference.scales[i]);
            double normalized = (values[i] - preference.means[i]) / scale;
            logit += normalized * preference.weights[i];
        }
        double probability = sigmoid(logit);
        return new GenerationScore(true, probability, preference.samples, preference.balancedAccuracy);
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
            cache.sampleCounts == null ? 0 : cache.sampleCounts.previewFeedback,
            cache.generationPreference != null && cache.generationPreference.usable(),
            cache.generationPreference == null ? 0 : Math.max(0, cache.generationPreference.samples),
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
            .append(" generation=").append(snapshot.generationPreferenceTrained() ? "trained" : "fallback")
            .append(" generationSamples=").append(snapshot.generationPreferenceSamples())
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

    private static double sigmoid(double value) {
        if (value >= 0.0) {
            double exp = Math.exp(-value);
            return 1.0 / (1.0 + exp);
        }
        double exp = Math.exp(value);
        return exp / (1.0 + exp);
    }

    public record Snapshot(
        boolean trained,
        String generatedAt,
        long placementEvents,
        long environmentObservations,
        long buildIntentExamples,
        long styleExamples,
        long previewFeedback,
        boolean generationPreferenceTrained,
        int generationPreferenceSamples,
        List<String> topThemes,
        List<String> zoneKeys
    ) {
    }

    public record GenerationFeatures(
        int selectionWidth,
        int selectionDepth,
        int bodyWidth,
        int bodyDepth,
        int floors,
        int roofLayers
    ) {
        public GenerationFeatures {
            selectionWidth = Math.max(1, selectionWidth);
            selectionDepth = Math.max(1, selectionDepth);
            bodyWidth = Math.max(1, bodyWidth);
            bodyDepth = Math.max(1, bodyDepth);
            floors = Math.max(1, Math.min(4, floors));
            roofLayers = Math.max(1, roofLayers);
        }

        private double[] values() {
            double widthFill = Math.min(1.25, bodyWidth / (double) selectionWidth);
            double depthFill = Math.min(1.25, bodyDepth / (double) selectionDepth);
            int maxRoofLayers = Math.max(1, Math.min(bodyWidth, bodyDepth) / 2);
            return new double[]{
                widthFill,
                depthFill,
                Math.min(1.5, widthFill * depthFill),
                Math.min(bodyWidth, bodyDepth) / (double) Math.max(bodyWidth, bodyDepth),
                floors,
                Math.min(1.0, roofLayers / (double) maxRoofLayers)
            };
        }
    }

    public record GenerationScore(boolean trained, double probability, int samples, double balancedAccuracy) {
        private static GenerationScore untrained() {
            return new GenerationScore(false, 0.5, 0, 0.0);
        }
    }

    private static final class ModelFile {
        int version;
        String generatedAt;
        SampleCounts sampleCounts;
        List<NamedCount> topThemes;
        List<NamedCount> topPalettes;
        Map<String, Prior> zonePriors;
        Map<String, Prior> themePriors;
        GenerationPreference generationPreference;

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
            if (generationPreference == null) {
                generationPreference = new GenerationPreference();
            }
            generationPreference.normalized();
            return this;
        }
    }

    private static final class SampleCounts {
        long placementEvents;
        long environmentObservations;
        long buildIntentExamples;
        long styleExamples;
        long previewFeedback;
    }

    private static final class GenerationPreference {
        boolean enabled;
        int minimumSamples;
        int samples;
        int positiveSamples;
        int negativeSamples;
        List<String> featureNames;
        double[] means;
        double[] scales;
        double[] weights;
        double bias;
        double balancedAccuracy;
        double logLoss;

        private void normalized() {
            if (featureNames == null) {
                featureNames = List.of();
            }
            means = validVector(means, 0.0);
            scales = validVector(scales, 1.0);
            weights = validVector(weights, 0.0);
            samples = Math.max(0, samples);
            positiveSamples = Math.max(0, positiveSamples);
            negativeSamples = Math.max(0, negativeSamples);
            balancedAccuracy = clampFinite(balancedAccuracy, 0.0, 1.0, 0.0);
            bias = Double.isFinite(bias) ? bias : 0.0;
        }

        private boolean usable() {
            return enabled
                && samples >= Math.max(1, minimumSamples)
                && positiveSamples > 0
                && negativeSamples > 0
                && featureNames.equals(GENERATION_FEATURE_NAMES)
                && means.length == GENERATION_FEATURE_NAMES.size()
                && scales.length == GENERATION_FEATURE_NAMES.size()
                && weights.length == GENERATION_FEATURE_NAMES.size();
        }

        private static double[] validVector(double[] source, double fallback) {
            int size = GENERATION_FEATURE_NAMES.size();
            double[] result = new double[size];
            for (int i = 0; i < size; i++) {
                double value = source != null && i < source.length ? source[i] : fallback;
                result[i] = Double.isFinite(value) ? value : fallback;
            }
            return result;
        }

        private static double clampFinite(double value, double min, double max, double fallback) {
            if (!Double.isFinite(value)) {
                return fallback;
            }
            return Math.max(min, Math.min(max, value));
        }
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
