package lol.hub.testutils;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static lol.hub.testutils.Waiting.holdsFor;
import static lol.hub.testutils.Waiting.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaitingTest {

    private static long millisSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    @Test
    void waitUntilReturnsTheValueThatSatisfiedTheCondition() {
        AtomicInteger calls = new AtomicInteger();
        int value = waitUntil(Duration.ofSeconds(10), calls::incrementAndGet, v -> v >= 3);
        assertEquals(3, value);
        assertEquals(3, calls.get());
    }

    @Test
    void waitUntilChecksOnceEvenWithoutTime() {
        assertEquals("ready", waitUntil(Duration.ZERO, () -> "ready", "ready"::equals));
    }

    @Test
    void waitUntilFailsWithTheLastValueAfterTheTimeout() {
        AtomicInteger calls = new AtomicInteger();
        long start = System.nanoTime();
        AssertionError e = assertThrows(AssertionError.class, () -> waitUntil(Duration.ofMillis(300), calls::incrementAndGet, v -> v < 0));
        assertTrue(millisSince(start) >= 300, "gave up before the timeout");
        assertTrue(calls.get() > 1, "did not poll");
        assertTrue(e.getMessage().endsWith("last value: " + calls.get()), e.getMessage());
    }

    @Test
    void holdsForPassesAStableConditionOnlyAfterTheWholeWindow() {
        AtomicInteger calls = new AtomicInteger();
        long start = System.nanoTime();
        holdsFor(Duration.ofMillis(300), () -> calls.incrementAndGet() > 0, Boolean::booleanValue);
        assertTrue(millisSince(start) >= 300, "returned before the window ended");
        assertTrue(calls.get() > 1, "did not poll");
    }

    @Test
    void holdsForFailsAtOnceWithTheValueThatBrokeIt() {
        AtomicInteger calls = new AtomicInteger();
        long start = System.nanoTime();
        AssertionError e = assertThrows(AssertionError.class, () -> holdsFor(Duration.ofSeconds(30), calls::incrementAndGet, v -> v < 3));
        assertTrue(millisSince(start) < 5_000, "kept observing after the condition broke");
        assertEquals(3, calls.get());
        assertTrue(e.getMessage().endsWith("value: 3"), e.getMessage());
    }

    @Test
    void holdsForChecksOnceEvenWithoutTime() {
        assertThrows(AssertionError.class, () -> holdsFor(Duration.ZERO, () -> 1, v -> v == 2));
        holdsFor(Duration.ZERO, () -> 1, v -> v == 1);
    }

    @Test
    void aQueryThatThrowsPropagatesAtOnce() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> waitUntil(Duration.ofSeconds(30), () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("bad query");
        }, v -> true));
        assertThrows(IllegalStateException.class, () -> holdsFor(Duration.ofSeconds(30), () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("bad query");
        }, v -> true));
        assertEquals(2, calls.get());
    }
}
