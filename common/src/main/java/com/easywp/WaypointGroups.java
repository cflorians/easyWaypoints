package com.easywp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The rules behind waypoint groups, kept free of Minecraft so they can be tested: what a group name
 * is, which groups a world has, which waypoints a list filter keeps and which a hidden group hides.
 *
 * <p>A group is a plain label, and a waypoint can carry several. Groups are not stored on their
 * own: the groups a world has are the union of its waypoints' groups, so one whose last waypoint
 * is gone simply stops existing. Names are compared exactly, except where the player types one:
 * {@link #resolve} reuses an existing group whose name differs only in case, so typing "minas"
 * joins "Minas" instead of starting a second group beside it.
 */
public final class WaypointGroups {

    /** The longest group name. Two of the longest still fit side by side as chips in the form. */
    public static final int MAX_NAME_LENGTH = 16;

    /** How many groups one waypoint can carry: two rows of chips in the form. */
    public static final int MAX_PER_WAYPOINT = 4;

    /** What the list's group filter keeps. */
    public enum Filter {
        /** Every waypoint. */
        ALL,
        /** Waypoints in no group. */
        NONE,
        /** Waypoints in one group, named alongside. */
        GROUP
    }

    private WaypointGroups() {
    }

    /** {@code raw} trimmed, with runs of whitespace collapsed to one space and cut to
     *  {@link #MAX_NAME_LENGTH}; {@code null} if nothing is left. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String collapsed = raw.trim().replaceAll("\\s+", " ");
        if (collapsed.length() > MAX_NAME_LENGTH) {
            collapsed = collapsed.substring(0, MAX_NAME_LENGTH).trim();
        }
        return collapsed.isEmpty() ? null : collapsed;
    }

    /** The group a typed name means: an existing one that matches ignoring case, else the typed
     *  name normalized. {@code null} for a blank name. */
    public static String resolve(Collection<String> existing, String typed) {
        String name = normalize(typed);
        if (name == null) return null;
        for (String group : existing) {
            if (group.equalsIgnoreCase(name)) {
                return group;
            }
        }
        return name;
    }

    /** Every group the given waypoints carry, once each, sorted ignoring case. */
    public static List<String> union(Iterable<? extends Collection<String>> groupsOfEachWaypoint) {
        Set<String> all = new LinkedHashSet<>();
        for (Collection<String> groups : groupsOfEachWaypoint) {
            if (groups != null) {
                all.addAll(groups);
            }
        }
        List<String> sorted = new ArrayList<>(all);
        sorted.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(String::compareTo));
        return sorted;
    }

    /** Whether a waypoint with these groups is kept by the filter. */
    public static boolean matches(Collection<String> groups, Filter filter, String group) {
        return switch (filter) {
            case ALL -> true;
            case NONE -> groups == null || groups.isEmpty();
            case GROUP -> groups != null && groups.contains(group);
        };
    }

    /** Whether any of these groups is hidden - one hidden group is enough to hide the waypoint. */
    public static boolean anyHidden(Collection<String> groups, Set<String> hidden) {
        if (groups == null || hidden.isEmpty()) return false;
        for (String group : groups) {
            if (hidden.contains(group)) return true;
        }
        return false;
    }

    /** The hidden groups that still exist; the rest are dropped rather than kept for a group
     *  that may never come back. */
    public static Set<String> prune(Set<String> hidden, Collection<String> existing) {
        Set<String> kept = new LinkedHashSet<>(hidden);
        kept.retainAll(existing);
        return kept;
    }

    /** Whether {@code name} can be added to a waypoint already in {@code assigned}: there is room,
     *  and it is not one of them already (ignoring case). */
    public static boolean canAdd(Collection<String> assigned, String name) {
        if (name == null || assigned.size() >= MAX_PER_WAYPOINT) return false;
        for (String group : assigned) {
            if (group.equalsIgnoreCase(name)) return false;
        }
        return true;
    }

    /** The existing groups to offer while a name is typed: those containing the text (ignoring
     *  case) that the waypoint does not have yet, in {@code existing}'s order. */
    public static List<String> suggestions(Collection<String> existing, Collection<String> assigned, String typed) {
        String needle = typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String group : existing) {
            if (!canAddIgnoringRoom(assigned, group)) continue;
            if (group.toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(group);
            }
        }
        return out;
    }

    private static boolean canAddIgnoringRoom(Collection<String> assigned, String name) {
        for (String group : assigned) {
            if (group.equalsIgnoreCase(name)) return false;
        }
        return true;
    }
}
