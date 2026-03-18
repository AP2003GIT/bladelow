package com.bladelow.ml;

import com.bladelow.builder.BuildSiteScan;
import com.bladelow.builder.SiteStyleProfile;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small nearest-neighbor memory over recorded environment observations.
 *
 * This is the first learned planner signal that changes behavior in-game:
 * current site scans are compared against past scans, then the nearest matches
 * vote on likely themes and typical building scale.
 */
public final class EnvironmentStyleMemory {
    private static final Gson GSON = new Gson();
    private static final int MAX_MATCHES = 8;

    private final StyleReferenceLibrary referenceLibrary;
    private long fingerprint = Long.MIN_VALUE;
    private String lastError = "";
    private List<Observation> observations = List.of();

    public EnvironmentStyleMemory(StyleReferenceLibrary referenceLibrary) {
        this.referenceLibrary = referenceLibrary;
    }

    public synchronized LearnedStyleHint suggest(BuildSiteScan scan) {
        refreshIfNeeded();
        if (scan == null || scan == BuildSiteScan.EMPTY || observations.isEmpty()) {
            return LearnedStyleHint.NONE;
        }

        // Compare the current scan against prior observations and keep only the
        // strongest matches. This behaves like a tiny local memory model.
        List<ScoredObservation> matches = observations.stream()
            .filter(observation -> !observation.primaryTheme().isBlank() || !observation.secondaryTheme().isBlank())
            .map(observation -> new ScoredObservation(observation, similarity(scan, observation)))
            .filter(scored -> scored.score() > 0.12)
            .sorted(Comparator.comparingDouble(ScoredObservation::score).reversed())
            .limit(MAX_MATCHES)
            .toList();

        if (matches.isEmpty()) {
            return directHint(scan.styleProfile());
        }

        // Nearby matches vote on themes, while their average dimensions provide
        // a learned target size for later blueprint scoring.
        Map<String, Double> themeVotes = new HashMap<>();
        double totalWeight = 0.0;
        double width = 0.0;
        double depth = 0.0;
        double height = 0.0;
        for (ScoredObservation match : matches) {
            Observation observation = match.observation();
            double weight = match.score();
            vote(themeVotes, observation.primaryTheme(), weight);
            vote(themeVotes, observation.secondaryTheme(), weight * 0.55);
            width += observation.averageWidth() * weight;
            depth += observation.averageDepth() * weight;
            height += observation.averageHeight() * weight;
            totalWeight += weight;
        }
        vote(themeVotes, scan.styleProfile().primaryTheme(), 1.8);
        vote(themeVotes, scan.styleProfile().secondaryTheme(), 1.0);

        List<Map.Entry<String, Double>> rankedThemes = themeVotes.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .toList();
        String primary = rankedThemes.isEmpty() ? "" : rankedThemes.get(0).getKey();
        String secondary = rankedThemes.size() > 1 ? rankedThemes.get(1).getKey() : "";
        double confidence = Math.min(1.0, totalWeight / 3.5);
        String referenceLabel = "";
        List<String> referenceTags = List.of();
        StyleReferenceLibrary.StyleReference ref = bestReference(primary, secondary);
        if (ref != null) {
            referenceLabel = !ref.label().isBlank() ? ref.label() : ref.fileName();
            referenceTags = ref.tags();
        }

        if (totalWeight <= 0.0) {
            return new LearnedStyleHint(primary, secondary, Math.min(0.4, confidence), matches.size(), 0.0, 0.0, 0.0, referenceLabel, referenceTags);
        }
        return new LearnedStyleHint(
            primary,
            secondary,
            confidence,
            matches.size(),
            width / totalWeight,
            depth / totalWeight,
            height / totalWeight,
            referenceLabel,
            referenceTags
        );
    }

    public synchronized String summary() {
        refreshIfNeeded();
        StringBuilder out = new StringBuilder();
        out.append("memory[obs=").append(observations.size()).append(" refs=").append(referenceLibrary.references().size()).append("]");
        if (!lastError.isBlank()) {
            out.append(" memoryError=").append(lastError);
        }
        return out.toString();
    }

    private LearnedStyleHint directHint(SiteStyleProfile profile) {
        if (profile == null || profile.primaryTheme().isBlank()) {
            return LearnedStyleHint.NONE;
        }
        // Fall back to the live scan if the memory is still empty, so the
        // planner keeps working before enough local data exists.
        StyleReferenceLibrary.StyleReference ref = bestReference(profile.primaryTheme(), profile.secondaryTheme());
        return new LearnedStyleHint(
            profile.primaryTheme(),
            profile.secondaryTheme(),
            0.25,
            0,
            profile.averageWidth(),
            profile.averageDepth(),
            profile.averageHeight(),
            ref == null ? "" : (!ref.label().isBlank() ? ref.label() : ref.fileName()),
            ref == null ? List.of() : ref.tags()
        );
    }

    private StyleReferenceLibrary.StyleReference bestReference(String primary, String secondary) {
        if ((primary == null || primary.isBlank()) && (secondary == null || secondary.isBlank())) {
            return null;
        }
        StyleReferenceLibrary.StyleReference best = null;
        double bestScore = 0.0;
        for (StyleReferenceLibrary.StyleReference reference : referenceLibrary.references()) {
            double score = referenceScore(reference, primary, secondary);
            if (score > bestScore) {
                bestScore = score;
                best = reference;
            }
        }
        return bestScore > 0.0 ? best : null;
    }

    private static double referenceScore(StyleReferenceLibrary.StyleReference reference, String primary, String secondary) {
        if (reference == null) {
            return 0.0;
        }
        double score = 0.0;
        for (String tag : reference.tags()) {
            if (!primary.isBlank() && tag.equals(primary)) {
                score += 3.0;
            } else if (!secondary.isBlank() && tag.equals(secondary)) {
                score += 1.5;
            }
        }
        String label = normalize(reference.label());
        if (!primary.isBlank() && label.contains(primary)) {
            score += 1.5;
        }
        if (!secondary.isBlank() && label.contains(secondary)) {
            score += 0.75;
        }
        return score;
    }

    private static double similarity(BuildSiteScan scan, Observation observation) {
        SiteStyleProfile profile = scan.styleProfile();
        double distance = 0.0;
        distance += normalizedDifference(scan.terrainAverageY(), observation.terrainAverageY(), 12.0) * 0.6;
        distance += normalizedDifference(scan.terrainMaxY() - scan.terrainMinY(), observation.terrainSpan(), 10.0) * 0.8;
        distance += normalizedDifference(profile.averageWidth(), observation.averageWidth(), Math.max(4.0, observation.averageWidth())) * 1.4;
        distance += normalizedDifference(profile.averageDepth(), observation.averageDepth(), Math.max(4.0, observation.averageDepth())) * 1.2;
        distance += normalizedDifference(profile.averageHeight(), observation.averageHeight(), Math.max(3.0, observation.averageHeight())) * 1.0;
        distance += normalizedDifference(scan.nearbyStructures().size(), observation.nearbyStructureCount(), Math.max(2.0, observation.nearbyStructureCount())) * 0.9;
        distance += normalizedDifference(averageArea(scan.nearbyStructures()), observation.nearbyAverageArea(), Math.max(16.0, observation.nearbyAverageArea())) * 0.7;
        distance += normalizedDifference(averageAspect(scan.nearbyStructures()), observation.nearbyAverageAspect(), 1.5) * 0.5;
        return 1.0 / (1.0 + distance);
    }

    private synchronized void refreshIfNeeded() {
        Path datasetPath = EnvironmentObservationLogger.datasetPath();
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
                    // Ignore malformed rows to keep the local dataset resilient.
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

    private static void vote(Map<String, Double> votes, String theme, double weight) {
        String normalized = normalize(theme);
        if (normalized.isBlank() || weight <= 0.0) {
            return;
        }
        votes.merge(normalized, weight, Double::sum);
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

    private static double normalizedDifference(double a, double b, double scale) {
        if (scale <= 0.0) {
            return 0.0;
        }
        return Math.abs(a - b) / scale;
    }

    private static String normalize(String input) {
        if (input == null) {
            return "";
        }
        return input.trim().toLowerCase(Locale.ROOT);
    }

    private record Observation(
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
        Observation normalized() {
            return new Observation(
                timestamp,
                source,
                dimension,
                minX,
                maxX,
                minY,
                maxY,
                minZ,
                maxZ,
                terrainMinY,
                terrainMaxY,
                terrainAverageY,
                normalize(primaryTheme),
                normalize(secondaryTheme),
                styleSamples,
                styleNearbyStructures,
                averageWidth,
                averageDepth,
                averageHeight,
                nearbyStructureCount,
                nearbyAverageArea,
                nearbyAverageAspect
            );
        }

        int terrainSpan() {
            return terrainMaxY - terrainMinY;
        }
    }

    private record ScoredObservation(Observation observation, double score) {
    }
}
