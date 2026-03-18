package com.bladelow.builder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Planner-friendly representation of a reusable town structure.
 *
 * This wraps raw placements with plot size, entrance, road-facing orientation,
 * and tags so the town planner can score it directly.
 */
public record TownBlueprint(
    String name,
    String category,
    List<Placement> placements,
    int plotWidth,
    int plotDepth,
    int priority,
    int entranceX,
    int entranceZ,
    String roadSide,
    int rotationTurns,
    List<String> themeTags,
    List<String> tags
) {
    public TownBlueprint {
        category = normalize(category);
        roadSide = normalizeSide(roadSide);
        rotationTurns = Math.floorMod(rotationTurns, 4);
        placements = placements == null ? List.of() : List.copyOf(placements);
        themeTags = themeTags == null ? List.of() : List.copyOf(themeTags);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public int width() {
        if (placements.isEmpty()) {
            return 0;
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        for (Placement placement : placements) {
            minX = Math.min(minX, placement.x());
            maxX = Math.max(maxX, placement.x());
        }
        return maxX - minX + 1;
    }

    public int height() {
        if (placements.isEmpty()) {
            return 0;
        }
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Placement placement : placements) {
            minY = Math.min(minY, placement.y());
            maxY = Math.max(maxY, placement.y());
        }
        return maxY - minY + 1;
    }

    public int depth() {
        if (placements.isEmpty()) {
            return 0;
        }
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Placement placement : placements) {
            minZ = Math.min(minZ, placement.z());
            maxZ = Math.max(maxZ, placement.z());
        }
        return maxZ - minZ + 1;
    }

    public int entranceOffsetX() {
        return clamp(entranceX, 0, Math.max(0, plotWidth - 1));
    }

    public int entranceOffsetZ() {
        return clamp(entranceZ, 0, Math.max(0, plotDepth - 1));
    }

    public TownBlueprint orientedForRoadSide(String targetSide) {
        String normalizedTarget = normalizeSide(targetSide);
        if (normalizedTarget.isBlank()) {
            return this;
        }
        // Rotate placements and entrance offsets together so the structure still
        // lines up with the requested road side after orientation changes.
        String sourceSide = roadSide.isBlank() ? "north" : roadSide;
        int turns = Math.floorMod(sideIndex(normalizedTarget) - sideIndex(sourceSide), 4);
        if (turns == 0 && normalizedTarget.equals(sourceSide)) {
            return this;
        }

        List<Placement> rotatedPlacements = placements;
        int width = plotWidth;
        int depth = plotDepth;
        int entryX = entranceOffsetX();
        int entryZ = entranceOffsetZ();
        for (int i = 0; i < turns; i++) {
            rotatedPlacements = rotateClockwise(rotatedPlacements, width, depth);
            int[] rotatedEntrance = rotateClockwise(entryX, entryZ, width, depth);
            entryX = rotatedEntrance[0];
            entryZ = rotatedEntrance[1];
            int previousWidth = width;
            width = depth;
            depth = previousWidth;
        }
        return new TownBlueprint(
            name,
            category,
            rotatedPlacements,
            width,
            depth,
            priority,
            entryX,
            entryZ,
            normalizedTarget,
            Math.floorMod(rotationTurns + turns, 4),
            themeTags,
            tags
        );
    }

    public boolean hasAnyTag(String... candidates) {
        if (candidates == null || candidates.length == 0) {
            return false;
        }
        for (String candidate : candidates) {
            String normalized = normalize(candidate);
            if (normalized.isBlank()) {
                continue;
            }
            if (tags.contains(normalized) || themeTags.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static List<Placement> rotateClockwise(List<Placement> source, int width, int depth) {
        List<Placement> rotated = new ArrayList<>(source.size());
        for (Placement placement : source) {
            int[] point = rotateClockwise(placement.x(), placement.z(), width, depth);
            rotated.add(new Placement(point[0], placement.y(), point[1], placement.blockId()));
        }
        return List.copyOf(rotated);
    }

    private static int[] rotateClockwise(int x, int z, int width, int depth) {
        return new int[]{depth - 1 - z, x};
    }

    private static int sideIndex(String side) {
        return switch (normalizeSide(side)) {
            case "north" -> 0;
            case "east" -> 1;
            case "south" -> 2;
            case "west" -> 3;
            default -> 0;
        };
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static String normalize(String input) {
        if (input == null) {
            return "";
        }
        return input.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeSide(String input) {
        String normalized = normalize(input);
        return switch (normalized) {
            case "north", "south", "east", "west" -> normalized;
            default -> "";
        };
    }

    public record Placement(int x, int y, int z, String blockId) {
    }
}
