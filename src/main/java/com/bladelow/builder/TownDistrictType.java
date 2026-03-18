package com.bladelow.builder;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Canonical district ids shared by zoning, HUD brushes, and planning.
 */
public enum TownDistrictType {
    RESIDENTIAL("residential", "Residential"),
    MARKET("market", "Market"),
    WORKSHOP("workshop", "Workshop"),
    CIVIC("civic", "Civic"),
    MIXED("mixed", "Mixed");

    private static final Map<String, TownDistrictType> BY_ID = buildIndex();

    private final String id;
    private final String label;

    TownDistrictType(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public static TownDistrictType fromId(String raw) {
        if (raw == null) {
            return null;
        }
        return BY_ID.get(raw.trim().toLowerCase(Locale.ROOT));
    }

    public static String normalize(String raw) {
        TownDistrictType type = fromId(raw);
        return type == null ? "" : type.id;
    }

    public static String idsCsv() {
        return Arrays.stream(values())
            .map(TownDistrictType::id)
            .reduce((left, right) -> left + "|" + right)
            .orElse("");
    }

    private static Map<String, TownDistrictType> buildIndex() {
        LinkedHashMap<String, TownDistrictType> types = new LinkedHashMap<>();
        for (TownDistrictType type : values()) {
            types.put(type.id, type);
        }
        return Map.copyOf(types);
    }
}
