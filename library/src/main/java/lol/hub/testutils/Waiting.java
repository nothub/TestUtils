package lol.hub.testutils;

import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Polling checks for state that settles over game ticks. The query runs at least once, then about
 * every 50 ms (one tick). A query or predicate that throws ends the wait at once.
 */
public final class Waiting {

    private static final int POLL_MILLIS = 50;

    private Waiting() {
    }

    /** Re-runs the query until its value satisfies {@code holds}, and returns that value. Fails with the last value after {@code timeout}. */
    public static <T> T waitUntil(Duration timeout, Supplier<T> query, Predicate<T> holds) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            T value = query.get();
            if (holds.test(value)) return value;
            if (System.nanoTime() - deadline >= 0) {
                throw new AssertionError("condition not met within " + timeout.toMillis() + " ms, last value: " + value);
            }
            pauseUntil(deadline);
        }
    }

    /**
     * Re-runs the query for the whole {@code window} and fails as soon as a value breaks {@code holds}.
     * Returns only after the window has passed, which is how a control case shows that nothing happened.
     */
    public static <T> void holdsFor(Duration window, Supplier<T> query, Predicate<T> holds) {
        long start = System.nanoTime();
        long deadline = start + window.toNanos();
        while (true) {
            T value = query.get();
            if (!holds.test(value)) {
                throw new AssertionError("condition broke after " + Duration.ofNanos(System.nanoTime() - start).toMillis()
                        + " ms of " + window.toMillis() + " ms, value: " + value);
            }
            if (System.nanoTime() - deadline >= 0) return;
            pauseUntil(deadline);
        }
    }

    static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void pauseUntil(long deadline) {
        pause(Duration.ofNanos(Math.min(deadline - System.nanoTime(), POLL_MILLIS * 1_000_000L)));
    }
}
