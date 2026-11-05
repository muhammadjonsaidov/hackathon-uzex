package uz.uzex.agent.web;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Allows a set number of attempts per key in each time window. Counts live in this process. */
public class RateLimiter {

    private record Window(long startedAt, int count) {}

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final int limit;
    private final long windowMs;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(int limit, Duration window) {
        this(limit, window, Clock.systemUTC());
    }

    RateLimiter(int limit, Duration window, Clock clock) {
        this.limit = limit;
        this.windowMs = window.toMillis();
        this.clock = clock;
    }

    public boolean allow(String key) {
        long now = clock.millis();
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.values().removeIf(w -> now - w.startedAt() >= windowMs);
        }
        Window updated = windows.compute(key, (k, current) ->
                current == null || now - current.startedAt() >= windowMs
                        ? new Window(now, 1)
                        : new Window(current.startedAt(), current.count() + 1));
        return updated.count() <= limit;
    }
}
