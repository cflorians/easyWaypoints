package com.easywp;

/**
 * The distance shown in a waypoint's label: {@code 999m} below a kilometre, {@code 1.2km} from
 * there on, or always metres when kilometres are switched off.
 *
 * <p>Pure text, no {@code net.minecraft.*}, so it lives in {@code common/} with its own tests and
 * is shared by every version - the HUD label today, and the chat output of {@code /easywp list}
 * later.
 *
 * <p>Built by hand rather than with {@code String.format("%.1f")}: that one follows the default
 * locale, and a player on a comma-decimal locale would read {@code 1,2km} in some places and
 * {@code 1.2km} in others. A point, always.
 */
public final class DistanceFormat {
    /** From this many metres on, the distance is shown in kilometres. */
    public static final int KM_FROM_METERS = 1000;

    private DistanceFormat() {
    }

    /**
     * @param meters whole metres, already truncated the way the label always has (a waypoint
     *               999.9 blocks away reads {@code 999m})
     * @param useKm  whether kilometres are on; when off every distance stays in metres
     * @return {@code "999m"}, or {@code "1.2km"} with one decimal, truncated rather than rounded
     *         like the metres are, so the label reads {@code 1.9km} until it is really 2 km away
     */
    public static String format(int meters, boolean useKm) {
        if (useKm && meters >= KM_FROM_METERS) {
            int tenths = meters / 100;
            return (tenths / 10) + "." + (tenths % 10) + "km";
        }
        return meters + "m";
    }

    /**
     * A number that changes exactly when {@link #format}'s text does, so the renderer can keep a
     * label cached until the text it shows would actually change: every metre below a kilometre,
     * only every 100 metres past it. Kilometre steps are negative, so they can never equal a
     * metres value - the same key always means the same text, whichever mode produced it.
     */
    public static int cacheKey(int meters, boolean useKm) {
        if (useKm && meters >= KM_FROM_METERS) {
            return -(meters / 100);
        }
        return meters;
    }
}
