package com.easywp.client;

import com.easywp.EasyWp;
import net.fabricmc.loader.api.FabricLoader;

import java.util.List;

/**
 * Facade for the live bridge into Xaero's Minimap - the only class in this mod allowed to reference
 * {@code xaero.*} is {@link XaeroMinimapBridge}, which this class never exposes directly. Every other
 * class talks to Xaero through the static methods here, which never let a failure on Xaero's side -
 * the mod missing, or a binary-incompatible version installed - propagate past this boundary.
 *
 * <p>{@code xaerominimap} is a {@code clientCompileOnly} dependency pinned to one version (see
 * {@code versions/*}{@code /build.gradle}), so this compiles against Xaero's real API instead of
 * reflection - but a player can run an older or newer Xaero release than that, and a mismatched
 * release can be missing a method or field this code calls. That produces
 * {@code NoSuchMethodError}/{@code NoClassDefFoundError}, both {@link LinkageError} subtypes that a
 * plain {@code catch (Exception)} does NOT catch - precisely the failure mode reflection never had.
 * Every public method here therefore catches {@code LinkageError} as well as
 * {@code RuntimeException}, logs the failure exactly once per session (not once per call), and
 * reports unavailable rather than letting the crash reach the caller - a wider catch than an
 * optional reflection-based integration would need, because this path is a real compiled dependency.
 *
 * <p><b>{@link #isModInstalled()} and {@link #isAvailable()} are deliberately different questions.</b>
 * The first only asks whether the player has Xaero at all, and is what decides whether easyWp shows
 * its Import/Export buttons; the second additionally requires a usable bridge, and is what decides
 * whether a given operation goes live or falls back to {@code waypoints.txt}. Keeping them apart is
 * what makes the fallback reachable: {@link #disable} marks the bridge unusable without pretending
 * the mod isn't installed, so a player running an incompatible Xaero release still sees the buttons
 * and still gets the file path - which is the entire point of having a fallback.
 *
 * <p>A {@code null}/false result from any method here can mean either "Xaero isn't installed" or
 * "no active minimap session right now" (main menu, between world loads) - both are ordinary, not
 * failures, and {@link XaeroMinimapBridge} itself never throws for them. Only a genuine
 * incompatibility disables the bridge for the rest of the session. Note that this makes "could not
 * read" and "read nothing" two different results from {@link #readRealWaypoints}, deliberately - see
 * its javadoc.
 *
 * <p>See {@code XaeroSync} for when this is used versus the {@code waypoints.txt} fallback file.
 */
public final class XaeroBridge {
    private static final String MOD_ID = "xaerominimap";

    private static Boolean modLoaded;
    private static XaeroMinimapBridge bridge;
    private static boolean disabled = false;
    private static boolean loggedFailure = false;

    private XaeroBridge() {
    }

    /** One of the player's own real Xaero waypoints, in easyWp's terms. Used in both directions:
     *  read out of Xaero by {@link #readRealWaypoints}, and handed back in by
     *  {@link #addRealWaypoints}. {@code colorOrdinal} indexes the same 21-entry palette as
     *  {@code XaeroWaypointFormat}. */
    public record RealWaypoint(String name, int x, int y, int z, int colorOrdinal) {
    }

    /** Whether Xaero's Minimap is installed at all, regardless of whether the live bridge works -
     *  the gate for showing easyWp's Import/Export buttons, since the {@code waypoints.txt} fallback
     *  can still serve them when the bridge itself is unusable. Cached after the first call. */
    public static boolean isModInstalled() {
        if (modLoaded == null) {
            modLoaded = FabricLoader.getInstance().isModLoaded(MOD_ID);
        }
        return modLoaded;
    }

    /** Whether the live bridge can actually be used right now: Xaero installed, not disabled by a
     *  prior binary-incompatibility failure this session, and the bridge instance constructed.
     *  Cheap to call every time - every check is cached after the first call. */
    public static boolean isAvailable() {
        return isModInstalled() && !disabled && bridgeInstance() != null;
    }

    private static XaeroMinimapBridge bridgeInstance() {
        if (bridge == null && !disabled) {
            try {
                bridge = new XaeroMinimapBridge();
            } catch (LinkageError | RuntimeException e) {
                disable(e);
            }
        }
        return bridge;
    }

    /**
     * The player's own real waypoints in {@code dimension} - whatever they created by hand or
     * accepted from a chat-shared {@code [Add]} button.
     *
     * @return {@code null} if this could not be read at all (Xaero unavailable, or no active minimap
     *         session), as opposed to an empty list meaning Xaero genuinely holds none here. A sync
     *         deletes as well as adds, so the caller must be able to tell "everything here was
     *         deleted" from "I could not ask" - treating the second as the first would make a
     *         main-menu or between-worlds read propagate as a mass deletion.
     */
    public static List<RealWaypoint> readRealWaypoints(String dimension) {
        if (!isAvailable()) {
            return null;
        }
        try {
            return bridge.readRealWaypoints(dimension);
        } catch (LinkageError | RuntimeException e) {
            disable(e);
            return null;
        }
    }

    /** Applies one dimension's worth of a sync to Xaero - removing everything matching
     *  {@code toRemove}, appending {@code toAdd}, and persisting both. @return {@code false} if
     *  unavailable, if there is no active session, or if Xaero's own save failed - all cases in which
     *  the caller should fall back to writing {@code waypoints.txt} itself. */
    public static boolean applyRealWaypoints(String dimension, List<RealWaypoint> toAdd, List<RealWaypoint> toRemove) {
        if (!isAvailable()) {
            return false;
        }
        try {
            return bridge.applyRealWaypoints(dimension, toAdd, toRemove);
        } catch (LinkageError | RuntimeException e) {
            disable(e);
            return false;
        }
    }

    /** Marks the bridge unusable for the rest of the session and logs why, exactly once. Deliberately
     *  does not clear {@link #modLoaded} - see the class javadoc: the mod is still installed, and the
     *  file fallback has to stay reachable. Retrying is pointless, since a version mismatch reproduces
     *  identically on every call. */
    private static void disable(Throwable t) {
        disabled = true;
        bridge = null;
        if (!loggedFailure) {
            loggedFailure = true;
            EasyWp.LOGGER.warn("Disabling the Xaero's Minimap integration for this session - the installed version looks incompatible with the one easyWp was built against", t);
        }
    }
}
