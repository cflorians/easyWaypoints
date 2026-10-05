package com.easywp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingRemovalsTest {

    private long now = 1_000_000L;
    private final PendingRemovals<String> pending = new PendingRemovals<>(7_000, () -> now);

    @Test
    void anItemExpiresWhenItsTimeIsUpAndNotBefore() {
        pending.mark("a");
        now += 6_999;
        assertEquals(List.of(), pending.takeExpired());
        assertTrue(pending.isPending("a"));

        now += 1;
        assertEquals(List.of("a"), pending.takeExpired());
        assertFalse(pending.isPending("a"));
        assertEquals(List.of(), pending.takeExpired());
    }

    @Test
    void eachItemRunsOnItsOwnClock() {
        pending.mark("a");
        now += 5_000;
        pending.mark("b");
        now += 2_000; // "a" is 7 s old, "b" only 2 s
        assertEquals(List.of("a"), pending.takeExpired());
        assertTrue(pending.isPending("b"));
        assertEquals(5_000, pending.remainingMillis("b"));
    }

    @Test
    void anUndoneItemNeverExpires() {
        pending.mark("a");
        assertTrue(pending.cancel("a"));
        now += 60_000;
        assertEquals(List.of(), pending.takeExpired());
        assertFalse(pending.cancel("a"));
        assertEquals(-1, pending.remainingMillis("a"));
    }

    @Test
    void anItemStaysPendingAfterItsTimeUntilItIsTaken() {
        // The list must not show it as a normal row again in the gap before the tick takes it.
        pending.mark("a");
        now += 10_000;
        assertTrue(pending.isPending("a"));
        assertEquals(0, pending.remainingMillis("a"));
    }

    @Test
    void markingAgainRestartsTheCountdown() {
        pending.mark("a");
        now += 5_000;
        pending.mark("a");
        now += 5_000;
        assertEquals(List.of(), pending.takeExpired());
        assertEquals(2_000, pending.remainingMillis("a"));
    }

    @Test
    void expiredItemsComeBackInTheOrderTheyWereMarked() {
        pending.mark("c");
        pending.mark("a");
        pending.mark("b");
        now += 7_000;
        assertEquals(List.of("c", "a", "b"), pending.takeExpired());
    }

    @Test
    void takeAllEndsEveryCountdownAtOnce() {
        pending.mark("a");
        pending.mark("b");
        assertEquals(List.of("a", "b"), pending.takeAll());
        assertEquals(0, pending.size());
        now += 7_000;
        assertEquals(List.of(), pending.takeExpired());
    }
}
