package com.bladelow.ml;

import com.bladelow.builder.TownBlueprint;

import java.util.List;
import java.util.Locale;

/**
 * A planner-facing summary of what the learned memory thinks this site should
 * look like.
 *
 * It is deliberately soft guidance: the hint biases scoring, but deterministic
 * constraints such as zone fit, road access, and safety still decide whether a
 * build can actually be placed.
 */
public record LearnedStyleHint(
    String primaryTheme,
    String secondaryTheme,
    double confidence,
    int matchedObservations,
    double averageWidth,
    double averageDepth,
    double averageHeight,
    String referenceLabel,
    List<String> referenceTags
) {
    public static final LearnedStyleHint NONE = new LearnedStyleHint(
        "",
        "",
        0.0,
        0,
        0.0,
        0.0,
        0.0,
        "",
        List.of()
    );

    public double score(TownBlueprint blueprint) {
        if (blueprint == null || confidence <= 0.0) {
            return 0.0;
        }

        // Reward theme and rough size agreement. This keeps the learned signal
        // interpretable and stops it from overpowering the rest of the planner.
        double score = 0.0;
        if (!primaryTheme.isBlank() && blueprint.hasAnyTag(primaryTheme)) {
            score += 8.0 * confidence;
        }
        if (!secondaryTheme.isBlank() && blueprint.hasAnyTag(secondaryTheme)) {
            score += 3.0 * confidence;
        }
        if (referenceTags != null && !referenceTags.isEmpty() && blueprint.hasAnyTag(referenceTags.toArray(String[]::new))) {
            score += 2.5 * confidence;
        }
        if (matchedObservations > 0) {
            score += sizeFit(blueprint.plotWidth(), averageWidth) * 4.0 * confidence;
            score += sizeFit(blueprint.plotDepth(), averageDepth) * 3.5 * confidence;
            score += sizeFit(blueprint.height(), averageHeight) * 2.5 * confidence;
        }
        return score;
    }

    public String summary() {
        if (confidence <= 0.0 || primaryTheme.isBlank()) {
            return "none";
        }
        String theme = secondaryTheme.isBlank() ? primaryTheme : primaryTheme + "/" + secondaryTheme;
        String base = String.format(
            Locale.ROOT,
            "%s conf=%.2f mem=%d",
            theme,
            confidence,
            matchedObservations
        );
        if (referenceLabel == null || referenceLabel.isBlank()) {
            return base;
        }
        return base + " ref=" + referenceLabel;
    }

    private static double sizeFit(double blueprintSize, double targetSize) {
        if (blueprintSize <= 0.0 || targetSize <= 0.0) {
            return 0.0;
        }
        double diff = Math.abs(blueprintSize - targetSize);
        double scale = Math.max(4.0, targetSize);
        double normalized = 1.0 - Math.min(1.0, diff / scale);
        return Math.max(0.0, normalized);
    }
}
