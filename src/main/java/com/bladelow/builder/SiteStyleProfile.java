package com.bladelow.builder;

import java.util.Locale;

/**
 * Coarse style description extracted directly from nearby world geometry.
 *
 * This is the deterministic style signal. It pairs with {@link
 * com.bladelow.ml.LearnedStyleHint}, which is the memory-based style signal.
 */
public record SiteStyleProfile(
    String primaryTheme,
    String secondaryTheme,
    int samples,
    int nearbyStructures,
    double averageWidth,
    double averageDepth,
    double averageHeight
) {
    public static final SiteStyleProfile NONE = new SiteStyleProfile("", "", 0, 0, 0.0, 0.0, 0.0);

    public double score(TownBlueprint blueprint) {
        if (blueprint == null || samples < 24) {
            return 0.0;
        }

        // Reward the blueprint for matching nearby material themes first, then
        // nudge it toward the average observed footprint and height.
        double score = 0.0;
        if (!primaryTheme.isBlank() && blueprint.hasAnyTag(primaryTheme)) {
            score += 10.0;
        }
        if (!secondaryTheme.isBlank() && blueprint.hasAnyTag(secondaryTheme)) {
            score += 4.0;
        }
        if (nearbyStructures <= 0) {
            return score;
        }

        score += sizeFit(blueprint.plotWidth(), averageWidth) * 9.0;
        score += sizeFit(blueprint.plotDepth(), averageDepth) * 8.0;
        score += sizeFit(blueprint.height(), averageHeight) * 6.0;
        return score;
    }

    public String summary() {
        if (samples <= 0 || primaryTheme.isBlank()) {
            return "none";
        }
        String theme = secondaryTheme.isBlank() ? primaryTheme : primaryTheme + "/" + secondaryTheme;
        if (nearbyStructures <= 0) {
            return theme + "(" + samples + ")";
        }
        return String.format(
            Locale.ROOT,
            "%s(%d) avg=%.0fx%.0fx%.0f nearby=%d",
            theme,
            samples,
            averageWidth,
            averageDepth,
            averageHeight,
            nearbyStructures
        );
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
