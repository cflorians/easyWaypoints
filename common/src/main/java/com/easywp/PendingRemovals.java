package com.easywp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Deletions that wait a few seconds before they become final, each on its own clock, and can be
 * undone - or made final at once - until then.
 *
 * <p>Pure, so it lives in {@code common/} with its own tests; the client keeps one for the
 * deletions made from the waypoint list. The items stay where they are meanwhile: this only keeps
 * the countdowns, and hands back the ones that ran out ({@link #takeExpired}) for the caller to
 * really remove. An item counts as pending until it is taken, cancelled or removed - not merely
 * until its time is up - so it never flickers back between running out and being taken.
 *
 * <p>Items are told apart with {@code equals}; the client's waypoints keep {@code Object}'s, so
 * two waypoints with the same contents are still two items.
 */
public final class PendingRemovals<T> {

    private final long lifetimeMillis;
    private final LongSupplier clock;
    /** Each pending item and when it becomes final, in the order they were marked. */
    private final Map<T, Long> deadlines = new LinkedHashMap<>();

    /**
     * @param lifetimeMillis how long each deletion can be undone, counted from its own mark
     * @param clock          the time source, in milliseconds - injectable for tests
     */
    public PendingRemovals(long lifetimeMillis, LongSupplier clock) {
        this.lifetimeMillis = lifetimeMillis;
        this.clock = clock;
    }

    /** Starts {@code item}'s countdown (again, if it was already pending). */
    public void mark(T item) {
        deadlines.remove(item);
        deadlines.put(item, clock.getAsLong() + lifetimeMillis);
    }

    /** Whether {@code item} is waiting to be removed. */
    public boolean isPending(T item) {
        return deadlines.containsKey(item);
    }

    /** Stops waiting for {@code item}, whether it is undone or removed right away.
     *  @return whether it was pending */
    public boolean cancel(T item) {
        return deadlines.remove(item) != null;
    }

    /** Milliseconds until {@code item} becomes final: 0 once its time is up, -1 if not pending. */
    public long remainingMillis(T item) {
        Long deadline = deadlines.get(item);
        return deadline == null ? -1 : Math.max(0, deadline - clock.getAsLong());
    }

    /** The items whose time is up, in the order they were marked, no longer pending. */
    public List<T> takeExpired() {
        long now = clock.getAsLong();
        List<T> expired = new ArrayList<>();
        Iterator<Map.Entry<T, Long>> it = deadlines.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<T, Long> entry = it.next();
            if (entry.getValue() <= now) {
                expired.add(entry.getKey());
                it.remove();
            }
        }
        return expired;
    }

    /** Every pending item, time up or not, no longer pending - for making them all final at once. */
    public List<T> takeAll() {
        List<T> all = new ArrayList<>(deadlines.keySet());
        deadlines.clear();
        return all;
    }

    /** How many items are pending. */
    public int size() {
        return deadlines.size();
    }
}
