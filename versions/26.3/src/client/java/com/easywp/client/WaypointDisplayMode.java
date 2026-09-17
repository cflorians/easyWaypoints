package com.easywp.client;

/**
 * Display modes for waypoints in-game.
 */
public enum WaypointDisplayMode {
    WORLD_MARKERS("hud.mode.world"),
    DISABLED("hud.mode.disabled");

    private final String translationKey;

    WaypointDisplayMode(String translationKey) {
        this.translationKey = translationKey;
    }

    public String getTranslationKey() {
        return translationKey;
    }

    public WaypointDisplayMode next() {
        WaypointDisplayMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
