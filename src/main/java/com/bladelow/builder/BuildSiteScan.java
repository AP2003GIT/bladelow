package com.bladelow.builder;

import java.util.List;
import java.util.Locale;

/**
 * Immutable result of a site scan.
 *
 * This is the stable contract between world analysis, ML logging, and planner
 * scoring, so callers do not need to know anything about how the scan was
 * produced internally.
 */
public record BuildSiteScan(
    SiteStyleProfile styleProfile,
    List<NearbyStructure> nearbyStructures,
    int terrainMinY,
    int terrainMaxY,
    int terrainAverageY
) {
    public static final BuildSiteScan EMPTY = new BuildSiteScan(SiteStyleProfile.NONE, List.of(), 0, 0, 0);

    public String summary() {
        if (nearbyStructures == null || nearbyStructures.isEmpty()) {
            return styleProfile == null ? "none" : styleProfile.summary();
        }
        return String.format(
            Locale.ROOT,
            "%s terrain=%d..%d nearby=%d",
            styleProfile == null ? "none" : styleProfile.summary(),
            terrainMinY,
            terrainMaxY,
            nearbyStructures.size()
        );
    }

    public record NearbyStructure(
        int minX,
        int maxX,
        int minZ,
        int maxZ,
        int minY,
        int maxY,
        int columns
    ) {
        public int width() {
            return maxX - minX + 1;
        }

        public int depth() {
            return maxZ - minZ + 1;
        }

        public int height() {
            return maxY - minY + 1;
        }

        public int area() {
            return width() * depth();
        }

        public double aspectRatio() {
            int shortSide = Math.max(1, Math.min(width(), depth()));
            int longSide = Math.max(width(), depth());
            return longSide / (double) shortSide;
        }
    }
}
