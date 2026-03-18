package com.bladelow.ml;

import com.google.gson.Gson;
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

/**
 * Memory-based predictor for lot-level build intent.
 *
 * This is intentionally lightweight: it reads accepted planner decisions from a
 * local JSONL dataset, finds similar past lots, and votes on the kind of
 * structure that should fit the current lot.
 */
public final class BuildIntentPredictor {
    private static final Gson GSON = new Gson();
    private static final int MAX_MATCHES = 10;

    private long fingerprint = Long.MIN_VALUE;
    private String lastError = "";
    private List<Observation> observations = List.of();

    public synchronized BuildIntent predict(BuildIntentContext context) {
        refreshIfNeeded();
        if (context == null) {
            return BuildIntent.NONE;
        }

        List<ScoredObservation> matches = observations.stream()
            .map(observation -> new ScoredObservation(observation, similarity(context, observation)))
            .filter(scored -> scored.score() > 0.14)
            .sorted(Comparator.comparingDouble(ScoredObservation::score).reversed())
            .limit(MAX_MATCHES)
            .toList();
        if (matches.isEmpty()) {
            return heuristic(context);
        }

        Map<String, Double> archetypeVotes = new HashMap<>();
        Map<String, Double> sizeVotes = new HashMap<>();
        Map<String, Double> roofVotes = new HashMap<>();
        Map<String, Double> paletteVotes = new HashMap<>();
        Map<String, Double> detailVotes = new HashMap<>();
        Map<String, Double> themeVotes = new HashMap<>();
        double totalWeight = 0.0;
        double floors = 0.0;

        for (ScoredObservation match : matches) {
            Observation observation = match.observation();
            double weight = match.score();
            vote(archetypeVotes, observation.archetype(), weight * 2.2);
            vote(sizeVotes, observation.sizeClass(), weight * 1.5);
            vote(roofVotes, observation.roofFamily(), weight);
            vote(paletteVotes, observation.paletteProfile(), weight * 1.3);
            vote(detailVotes, observation.detailDensity(), weight);
            vote(themeVotes, observation.learnedPrimaryTheme(), weight);
            vote(themeVotes, observation.stylePrimaryTheme(), weight * 0.75);
            floors += observation.floors() * weight;
            totalWeight += weight;
        }

        String primaryTheme = best(themeVotes, context.learnedPrimaryTheme(), context.stylePrimaryTheme());
        String secondaryTheme = nextBest(themeVotes, primaryTheme, context.learnedSecondaryTheme(), context.styleSecondaryTheme());
        double confidence = Math.min(1.0, totalWeight / 4.0);
        return new BuildIntent(
            best(archetypeVotes, heuristicArchetype(context)),
            best(sizeVotes, heuristicSize(context)),
            totalWeight <= 0.0 ? heuristicFloors(context) : Math.max(1, (int) Math.round(floors / totalWeight)),
            best(roofVotes, heuristicRoof(context)),
            best(paletteVotes, heuristicPalette(context)),
            best(detailVotes, heuristicDetail(context)),
            primaryTheme,
            secondaryTheme,
            confidence,
            matches.size()
        );
    }

    public synchronized String summary() {
        refreshIfNeeded();
        StringBuilder out = new StringBuilder();
        out.append("intentMemory[obs=").append(observations.size()).append("]");
        if (!lastError.isBlank()) {
            out.append(" intentMemoryError=").append(lastError);
        }
        return out.toString();
    }

    private synchronized void refreshIfNeeded() {
        Path datasetPath = BuildIntentExampleLogger.datasetPath();
        try {
            Files.createDirectories(datasetPath.getParent());
            long currentFingerprint = datasetFingerprint(datasetPath);
            if (currentFingerprint == fingerprint) {
                return;
            }
            fingerprint = currentFingerprint;
            observations = loadObservations(datasetPath);
            lastError = "";
        } catch (IOException ex) {
            lastError = ex.getMessage();
        }
    }

    private static List<Observation> loadObservations(Path datasetPath) throws IOException {
        if (!Files.exists(datasetPath)) {
            return List.of();
        }
        List<Observation> out = new ArrayList<>();
        try (var lines = Files.lines(datasetPath)) {
            lines.forEach(line -> {
                if (line == null || line.isBlank()) {
                    return;
                }
                try {
                    Observation observation = GSON.fromJson(line, Observation.class);
                    if (observation != null) {
                        out.add(observation.normalized());
                    }
                } catch (JsonSyntaxException ignored) {
                    // Ignore malformed rows so a bad append does not poison the dataset.
                }
            });
        }
        return List.copyOf(out);
    }

    private static long datasetFingerprint(Path datasetPath) throws IOException {
        if (!Files.exists(datasetPath)) {
            return 0L;
        }
        return Files.getLastModifiedTime(datasetPath).toMillis() ^ Files.size(datasetPath);
    }

    private static BuildIntent heuristic(BuildIntentContext context) {
        return new BuildIntent(
            heuristicArchetype(context),
            heuristicSize(context),
            heuristicFloors(context),
            heuristicRoof(context),
            heuristicPalette(context),
            heuristicDetail(context),
            bestTheme(context),
            fallbackTheme(context),
            0.28 + (context.learnedConfidence() * 0.35),
            0
        );
    }

    private static String heuristicArchetype(BuildIntentContext context) {
        String zone = normalize(context.zoneType());
        if (!zone.isBlank()) {
            return zone;
        }
        if (context.gateScore() >= 0.7 && context.roadScore() >= 0.65) {
            return "market";
        }
        if (context.centerScore() >= 0.72 && context.primaryRoad()) {
            return "civic";
        }
        if (context.wallScore() >= 0.65) {
            return "workshop";
        }
        return "residential";
    }

    private static String heuristicSize(BuildIntentContext context) {
        double styleArea = Math.max(1.0, context.styleAverageWidth() * context.styleAverageDepth());
        if (context.centerScore() >= 0.78 || styleArea >= 110.0) {
            return "large";
        }
        if (styleArea >= 55.0 || context.roadScore() >= 0.6) {
            return "medium";
        }
        return "small";
    }

    private static int heuristicFloors(BuildIntentContext context) {
        if (context.styleAverageHeight() >= 10.0) {
            return 3;
        }
        if (context.styleAverageHeight() >= 6.0 || context.centerScore() >= 0.7) {
            return 2;
        }
        return 1;
    }

    private static String heuristicRoof(BuildIntentContext context) {
        String theme = bestTheme(context);
        if ("market".equals(theme) || context.gateScore() >= 0.7) {
            return "gable";
        }
        if ("stone".equals(theme) && context.centerScore() >= 0.7) {
            return "low";
        }
        return "steep";
    }

    private static String heuristicPalette(BuildIntentContext context) {
        String first = bestTheme(context);
        String second = fallbackTheme(context);
        if (first.isBlank() && second.isBlank()) {
            return "";
        }
        if (second.isBlank() || second.equals(first)) {
            return first;
        }
        return first + "_" + second;
    }

    private static String heuristicDetail(BuildIntentContext context) {
        if (context.nearbyStructureCount() >= 5 || context.gateScore() >= 0.7) {
            return "high";
        }
        if (context.nearbyStructureCount() >= 2 || context.roadScore() >= 0.55) {
            return "medium";
        }
        return "low";
    }

    private static String bestTheme(BuildIntentContext context) {
        if (!normalize(context.learnedPrimaryTheme()).isBlank()) {
            return normalize(context.learnedPrimaryTheme());
        }
        return normalize(context.stylePrimaryTheme());
    }

    private static String fallbackTheme(BuildIntentContext context) {
        String learnedSecondary = normalize(context.learnedSecondaryTheme());
        if (!learnedSecondary.isBlank()) {
            return learnedSecondary;
        }
        return normalize(context.styleSecondaryTheme());
    }

    private static double similarity(BuildIntentContext context, Observation observation) {
        double distance = 0.0;
        distance += normalizedDifference(context.centerScore(), observation.centerScore(), 1.0) * 0.8;
        distance += normalizedDifference(context.wallScore(), observation.wallScore(), 1.0) * 0.8;
        distance += normalizedDifference(context.gateScore(), observation.gateScore(), 1.0) * 0.9;
        distance += normalizedDifference(context.roadScore(), observation.roadScore(), 1.0) * 1.0;
        distance += normalizedDifference(context.terrainSpan(), observation.terrainSpan(), Math.max(4.0, observation.terrainSpan())) * 0.5;
        distance += normalizedDifference(context.nearbyStructureCount(), observation.nearbyStructureCount(), Math.max(2.0, observation.nearbyStructureCount())) * 0.6;
        distance += normalizedDifference(context.styleAverageWidth(), observation.styleAverageWidth(), Math.max(4.0, observation.styleAverageWidth())) * 0.8;
        distance += normalizedDifference(context.styleAverageDepth(), observation.styleAverageDepth(), Math.max(4.0, observation.styleAverageDepth())) * 0.8;
        distance += normalizedDifference(context.styleAverageHeight(), observation.styleAverageHeight(), Math.max(3.0, observation.styleAverageHeight())) * 0.6;
        distance += exactPenalty(context.zoneType(), observation.zoneType(), 0.65);
        distance += exactPenalty(bestTheme(context), observation.learnedPrimaryTheme(), 0.45);
        distance += exactPenalty(bestTheme(context), observation.stylePrimaryTheme(), 0.35);
        if (context.primaryRoad() != observation.primaryRoad()) {
            distance += 0.3;
        }
        return 1.0 / (1.0 + distance);
    }

    private static void vote(Map<String, Double> votes, String label, double weight) {
        String normalized = normalize(label);
        if (normalized.isBlank() || weight <= 0.0) {
            return;
        }
        votes.merge(normalized, weight, Double::sum);
    }

    private static String best(Map<String, Double> votes, String... fallbacks) {
        String best = votes.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("");
        if (!best.isBlank()) {
            return best;
        }
        for (String fallback : fallbacks) {
            String normalized = normalize(fallback);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private static String nextBest(Map<String, Double> votes, String first, String... fallbacks) {
        String normalizedFirst = normalize(first);
        String next = votes.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .map(Map.Entry::getKey)
            .filter(value -> !value.equals(normalizedFirst))
            .findFirst()
            .orElse("");
        if (!next.isBlank()) {
            return next;
        }
        for (String fallback : fallbacks) {
            String normalized = normalize(fallback);
            if (!normalized.isBlank() && !normalized.equals(normalizedFirst)) {
                return normalized;
            }
        }
        return "";
    }

    private static double normalizedDifference(double a, double b, double scale) {
        if (scale <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, Math.abs(a - b) / scale);
    }

    private static double exactPenalty(String current, String prior, double mismatchPenalty) {
        String a = normalize(current);
        String b = normalize(prior);
        if (a.isBlank() || b.isBlank() || a.equals(b)) {
            return 0.0;
        }
        return mismatchPenalty;
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase(Locale.ROOT);
    }

    private record ScoredObservation(Observation observation, double score) {
    }

    private record Observation(
        String zoneType,
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
        String archetype,
        String sizeClass,
        int floors,
        String roofFamily,
        String paletteProfile,
        String detailDensity
    ) {
        private Observation normalized() {
            return new Observation(
                normalize(zoneType),
                Math.max(0.0, Math.min(1.0, centerScore)),
                Math.max(0.0, Math.min(1.0, wallScore)),
                Math.max(0.0, Math.min(1.0, gateScore)),
                Math.max(0.0, Math.min(1.0, roadScore)),
                primaryRoad,
                Math.max(0, terrainSpan),
                Math.max(0, nearbyStructureCount),
                normalize(stylePrimaryTheme),
                normalize(styleSecondaryTheme),
                Math.max(0.0, styleAverageWidth),
                Math.max(0.0, styleAverageDepth),
                Math.max(0.0, styleAverageHeight),
                normalize(learnedPrimaryTheme),
                normalize(learnedSecondaryTheme),
                Math.max(0.0, Math.min(1.0, learnedConfidence)),
                normalize(archetype),
                normalize(sizeClass),
                Math.max(0, floors),
                normalize(roofFamily),
                normalize(paletteProfile),
                normalize(detailDensity)
            );
        }
    }
}
