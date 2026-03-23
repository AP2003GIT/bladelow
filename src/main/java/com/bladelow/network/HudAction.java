package com.bladelow.network;

import java.util.Locale;

/**
 * Typed action identifiers for the HUD/client recovery surface.
 *
 * These replace the older root/action command vocabulary so the client and
 * server can agree on a compact, explicit action contract.
 */
public enum HudAction {
    PAUSE_BUILD("pause_build"),
    CONTINUE_BUILD("continue_build"),
    CANCEL_BUILD("cancel_build"),
    STATUS("status"),
    STATUS_DETAIL("status_detail"),

    SELECTION_CLEAR("selection_clear"),
    SELECTION_MARKER_BOX("selection_marker_box"),
    SELECTION_BUILD_HEIGHT("selection_build_height"),

    ZONE_SET("zone_set"),
    ZONE_LIST("zone_list"),
    ZONE_CLEAR("zone_clear"),
    ZONE_AUTO_LAYOUT("zone_auto_layout"),

    BLUEPRINT_LOAD("blueprint_load"),
    BLUEPRINT_BUILD("blueprint_build"),
    TOWN_FILL_SELECTION("town_fill_selection"),
    TOWN_PREVIEW_SELECTION("town_preview_selection"),
    CITY_AUTOPLAY_START("city_autoplay_start"),
    CITY_AUTOPLAY_STATUS("city_autoplay_status"),
    CITY_AUTOPLAY_STOP("city_autoplay_stop"),
    CITY_AUTOPLAY_CONTINUE("city_autoplay_continue"),
    CITY_AUTOPLAY_CANCEL("city_autoplay_cancel"),

    MOVE_SMART_ENABLE("move_smart_enable"),
    MOVE_SMART_DISABLE("move_smart_disable"),
    MOVE_SET_MODE("move_set_mode"),
    MOVE_SET_REACH("move_set_reach"),
    SAFETY_SET_PREVIEW("safety_set_preview"),
    PROFILE_LOAD("profile_load"),
    MODEL_SCAN_INTENT("model_scan_intent"),
    MODEL_SAVE_STYLE_EXAMPLE("model_save_style_example");

    private final String wireId;

    HudAction(String wireId) {
        this.wireId = wireId;
    }

    public String wireId() {
        return wireId;
    }

    public static HudAction fromOrdinal(int ordinal) {
        HudAction[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalArgumentException("Unknown HUD action ordinal: " + ordinal);
        }
        return values[ordinal];
    }

    public static HudAction fromWireId(String wireId) {
        if (wireId == null) {
            throw new IllegalArgumentException("HUD action id is required");
        }
        String normalized = wireId.trim().toLowerCase(Locale.ROOT);
        for (HudAction action : values()) {
            if (action.wireId.equals(normalized)) {
                return action;
            }
        }
        throw new IllegalArgumentException("Unknown HUD action id: " + wireId);
    }
}
