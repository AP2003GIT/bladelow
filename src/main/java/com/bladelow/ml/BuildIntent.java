package com.bladelow.ml;

import com.bladelow.builder.TownBlueprint;

import java.util.List;
import java.util.Locale;

/**
 * Soft prediction for the kind of building that best fits a lot.
 *
 * It does not place blocks on its own. Instead it biases the deterministic
 * planner toward blueprints whose archetype, scale, material palette, and
 * detail level look compatible with the selected site.
 */
public record BuildIntent(
    String primaryArchetype,
    String sizeClass,
    int floors,
    String roofFamily,
    String paletteProfile,
    String detailDensity,
    String primaryTheme,
    String secondaryTheme,
    double confidence,
    int matchedExamples
) {
    public static final BuildIntent NONE = new BuildIntent(
        "",
        "",
        0,
        "",
        "",
        "",
        "",
        "",
        0.0,
        0
    );

    public double score(TownBlueprint blueprint) {
        if (blueprint == null || confidence <= 0.0) {
            return 0.0;
        }

        double score = 0.0;
        if (!primaryArchetype.isBlank() && blueprintMatchesArchetype(blueprint, primaryArchetype)) {
            score += 12.0 * confidence;
        }
        if (!sizeClass.isBlank() && sizeClass.equals(sizeClassFor(blueprint))) {
            score += 5.0 * confidence;
        }
        if (floors > 0) {
            int diff = Math.abs(floors - floorsFor(blueprint));
            score += Math.max(0.0, 4.0 - (diff * 1.5)) * confidence;
        }
        if (!detailDensity.isBlank() && detailDensity.equals(detailDensityFor(blueprint))) {
            score += 3.0 * confidence;
        }
        if (!primaryTheme.isBlank() && blueprint.hasAnyTag(primaryTheme)) {
            score += 4.0 * confidence;
        }
        if (!secondaryTheme.isBlank() && blueprint.hasAnyTag(secondaryTheme)) {
            score += 2.0 * confidence;
        }
        score += paletteMatchScore(blueprint) * 4.5 * confidence;
        return score;
    }

    public String summary() {
        if (confidence <= 0.0 || primaryArchetype.isBlank()) {
            return "none";
        }
        String extras = sizeClass.isBlank() ? "" : " " + sizeClass;
        if (floors > 0) {
            extras += " " + floors + "f";
        }
        if (!paletteProfile.isBlank()) {
            extras += " " + paletteProfile;
        }
        return String.format(
            Locale.ROOT,
            "%s%s conf=%.2f mem=%d",
            primaryArchetype,
            extras,
            confidence,
            matchedExamples
        );
    }

    public static String archetypeFor(TownBlueprint blueprint) {
        if (blueprint == null) {
            return "";
        }
        if (blueprint.hasAnyTag("civic", "hall", "keep", "plaza", "church", "tower")) {
            return "civic";
        }
        if (blueprint.hasAnyTag("market", "stall", "shop", "inn", "tavern")) {
            return "market";
        }
        if (blueprint.hasAnyTag("smithy", "workshop", "utility", "storage", "farm")) {
            return "workshop";
        }
        if (blueprint.hasAnyTag("house", "residential")) {
            return "residential";
        }
        return "mixed";
    }

    public static String sizeClassFor(TownBlueprint blueprint) {
        if (blueprint == null) {
            return "";
        }
        int area = Math.max(1, blueprint.plotWidth() * blueprint.plotDepth());
        if (area <= 49) {
            return "small";
        }
        if (area <= 120) {
            return "medium";
        }
        return "large";
    }

    public static int floorsFor(TownBlueprint blueprint) {
        if (blueprint == null) {
            return 0;
        }
        int height = Math.max(0, blueprint.height());
        if (height <= 5) {
            return 1;
        }
        if (height <= 9) {
            return 2;
        }
        return 3;
    }

    public static String roofFamilyFor(TownBlueprint blueprint) {
        if (blueprint == null) {
            return "";
        }
        int height = Math.max(0, blueprint.height());
        int footprint = Math.max(1, Math.max(blueprint.plotWidth(), blueprint.plotDepth()));
        double steepness = height / (double) footprint;
        if (steepness >= 0.5) {
            return "steep";
        }
        if (steepness >= 0.28) {
            return "gable";
        }
        return "low";
    }

    public static String paletteProfileFor(TownBlueprint blueprint) {
        if (blueprint == null || blueprint.placements().isEmpty()) {
            return "";
        }
        int stone = 0;
        int wood = 0;
        int market = 0;
        int plaster = 0;
        for (TownBlueprint.Placement placement : blueprint.placements()) {
            String block = placement.blockId().toLowerCase(Locale.ROOT);
            if (block.contains("glass")) {
                continue;
            }
            if (isStone(block)) {
                stone++;
            } else if (isWood(block)) {
                wood++;
            } else if (block.contains("wool") || block.contains("terracotta") || block.contains("banner") || block.contains("copper")) {
                market++;
            } else if (block.contains("quartz") || block.contains("calcite") || block.contains("white_concrete")) {
                plaster++;
            }
        }
        List<String> ordered = List.of(
            dominantPart("stone", stone),
            dominantPart("oak", wood),
            dominantPart("market", market),
            dominantPart("plaster", plaster)
        ).stream().filter(part -> !part.isBlank()).toList();
        if (ordered.isEmpty()) {
            return "";
        }
        if (ordered.size() == 1) {
            return ordered.get(0);
        }
        return ordered.get(0) + "_" + ordered.get(1);
    }

    public static String detailDensityFor(TownBlueprint blueprint) {
        if (blueprint == null || blueprint.placements().isEmpty()) {
            return "";
        }
        int detail = 0;
        for (TownBlueprint.Placement placement : blueprint.placements()) {
            String block = placement.blockId().toLowerCase(Locale.ROOT);
            if (block.contains("stairs")
                || block.contains("slab")
                || block.contains("wall")
                || block.contains("fence")
                || block.contains("trapdoor")
                || block.contains("lantern")
                || block.contains("chain")) {
                detail++;
            }
        }
        double ratio = detail / (double) Math.max(1, blueprint.placements().size());
        if (ratio >= 0.18) {
            return "high";
        }
        if (ratio >= 0.08) {
            return "medium";
        }
        return "low";
    }

    private static boolean blueprintMatchesArchetype(TownBlueprint blueprint, String archetype) {
        return archetype.equals(archetypeFor(blueprint));
    }

    private double paletteMatchScore(TownBlueprint blueprint) {
        if (paletteProfile.isBlank()) {
            return 0.0;
        }
        String actual = paletteProfileFor(blueprint);
        if (actual.isBlank()) {
            return 0.0;
        }
        if (actual.equals(paletteProfile)) {
            return 1.0;
        }
        String[] expectedParts = paletteProfile.split("_");
        double hits = 0.0;
        for (String part : expectedParts) {
            if (!part.isBlank() && actual.contains(part)) {
                hits += 1.0;
            }
        }
        return hits / Math.max(1.0, expectedParts.length);
    }

    private static String dominantPart(String name, int count) {
        return count > 0 ? name : "";
    }

    private static boolean isStone(String block) {
        return block.contains("stone")
            || block.contains("cobbl")
            || block.contains("brick")
            || block.contains("andesite")
            || block.contains("diorite")
            || block.contains("granite")
            || block.contains("deepslate")
            || block.contains("tuff")
            || block.contains("blackstone")
            || block.contains("basalt");
    }

    private static boolean isWood(String block) {
        return block.contains("oak")
            || block.contains("spruce")
            || block.contains("birch")
            || block.contains("dark_oak")
            || block.contains("acacia")
            || block.contains("mangrove")
            || block.contains("cherry")
            || block.contains("jungle")
            || block.contains("bamboo")
            || block.contains("crimson")
            || block.contains("warped")
            || block.contains("planks")
            || block.contains("log")
            || block.contains("wood")
            || block.contains("stripped_");
    }
}
