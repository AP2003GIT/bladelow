package com.bladelow.ml;

import java.util.Locale;

/**
 * Planner-facing feature vector for "what kind of building fits here?"
 *
 * This is the first structured prediction target above raw block placement.
 * It describes one candidate lot using compact signals the planner already
 * knows: district, road/gate/center pressure, terrain spread, and nearby style.
 */
public record BuildIntentContext(
    String source,
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
    double learnedConfidence
) {
    public BuildIntentContext {
        source = normalize(source);
        zoneType = normalize(zoneType);
        stylePrimaryTheme = normalize(stylePrimaryTheme);
        styleSecondaryTheme = normalize(styleSecondaryTheme);
        learnedPrimaryTheme = normalize(learnedPrimaryTheme);
        learnedSecondaryTheme = normalize(learnedSecondaryTheme);
        areaWidth = Math.max(0, areaWidth);
        areaDepth = Math.max(0, areaDepth);
        terrainSpan = Math.max(0, terrainSpan);
        nearbyStructureCount = Math.max(0, nearbyStructureCount);
        centerScore = clamp01(centerScore);
        wallScore = clamp01(wallScore);
        gateScore = clamp01(gateScore);
        roadScore = clamp01(roadScore);
        styleAverageWidth = Math.max(0.0, styleAverageWidth);
        styleAverageDepth = Math.max(0.0, styleAverageDepth);
        styleAverageHeight = Math.max(0.0, styleAverageHeight);
        learnedConfidence = clamp01(learnedConfidence);
    }

    public String summary() {
        String zone = zoneType.isBlank() ? "-" : zoneType;
        String theme = learnedPrimaryTheme.isBlank() ? stylePrimaryTheme : learnedPrimaryTheme;
        if (theme.isBlank()) {
            theme = "-";
        }
        return String.format(
            Locale.ROOT,
            "zone=%s road=%.2f gate=%.2f center=%.2f wall=%.2f theme=%s nearby=%d span=%d",
            zone,
            roadScore,
            gateScore,
            centerScore,
            wallScore,
            theme,
            nearbyStructureCount,
            terrainSpan
        );
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase(Locale.ROOT);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
