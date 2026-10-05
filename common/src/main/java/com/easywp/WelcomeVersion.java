package com.easywp;

/**
 * When the welcome message is due, and which version's news it shows.
 *
 * <p>It is shown once per version, compared in full - so every alpha and beta counts as new, and a
 * 1.3.x player sees it once on updating. The news, though, belongs to the release the build is
 * heading for: {@code 1.4.0-alpha.7} and {@code 1.4.0} both show 1.4.0's, so its lang keys are
 * written once per release, not per build.
 */
public final class WelcomeVersion {

    private WelcomeVersion() {
    }

    /** Whether the message is due: never shown, or last shown for another version. */
    public static boolean isNew(String lastSeen, String current) {
        return current != null && !current.equals(lastSeen);
    }

    /** The release a version string belongs to: everything before the first {@code -} or {@code +}
     *  ({@code 1.4.0-alpha.7+26.3} gives {@code 1.4.0}). */
    public static String release(String version) {
        if (version == null) return "";
        int end = version.length();
        int dash = version.indexOf('-');
        int plus = version.indexOf('+');
        if (dash >= 0) end = Math.min(end, dash);
        if (plus >= 0) end = Math.min(end, plus);
        return version.substring(0, end);
    }
}
