package com.easywp.client;

public enum WaypointDisplayMode {
    WORLD_MARKERS("hud.mode.world"),
    LOCATOR_BAR("hud.mode.locator_bar"),
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
        return values[(this.ordinal() + 1) % values.length];
    }
}
