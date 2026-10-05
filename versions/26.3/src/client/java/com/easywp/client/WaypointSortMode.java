package com.easywp.client;

/**
 * Row ordering offered by {@link WaypointListScreen}, cycled with the button next to the search box.
 *
 * <p>{@link #CREATED} is the default and orders by {@code Waypoint.getCreatedAtMillis()}, with a
 * stable sort. Waypoints from before 1.4.0 never had their creation time recorded and all read 0,
 * so they keep their insertion order among themselves and come first - where they already were,
 * since this mode used to reorder nothing at all and the insertion order was the creation order.
 * What the timestamp adds is a waypoint imported from another world: it sorts by when it was
 * originally created there, not by when it was imported.
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
