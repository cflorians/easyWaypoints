package com.easywp.client;

/**
 * Row ordering offered by {@link WaypointListScreen}, cycled with the button next to the search box.
 *
 * <p>{@link #CREATED} is the default and deliberately reorders nothing: waypoints are appended to
 * the list as they are created, and the JSON is written and read back in that same order, so the
 * insertion order already is the creation order. That keeps the list looking exactly as it did
 * before sorting existed unless the player asks for something else.
 */
public enum WaypointSortMode {
    CREATED("sort.created"),
    NAME("sort.name"),
    DISTANCE("sort.distance");

    private final String translationKey;

    WaypointSortMode(String translationKey) {
        this.translationKey = translationKey;
    }

    public String getTranslationKey() {
        return translationKey;
    }

    public WaypointSortMode next() {
        WaypointSortMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /** Resolves a persisted name, falling back to the default rather than throwing on a hand edit. */
    public static WaypointSortMode fromName(String name) {
        if (name != null) {
            for (WaypointSortMode mode : values()) {
                if (mode.name().equalsIgnoreCase(name)) {
                    return mode;
                }
            }
        }
        return CREATED;
    }
}
