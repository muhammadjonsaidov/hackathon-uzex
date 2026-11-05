package uz.uzex.agent.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private static final Instant START = Instant.parse("2026-11-05T00:00:00Z");

    private static Clock at(Duration offset) {
        return Clock.fixed(START.plus(offset), ZoneOffset.UTC);
    }

    @Test
    void allowsUpToTheLimit() {
        RateLimiter limiter = new RateLimiter(2, Duration.ofMinutes(1), at(Duration.ZERO));
        assertThat(limiter.allow("a")).isTrue();
        assertThat(limiter.allow("a")).isTrue();
        assertThat(limiter.allow("a")).isFalse();
    }

    @Test
    void keepsKeysApart() {
        RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1), at(Duration.ZERO));
        assertThat(limiter.allow("a")).isTrue();
        assertThat(limiter.allow("b")).isTrue();
        assertThat(limiter.allow("a")).isFalse();
    }

    @Test
    void startsAgainAfterTheWindow() {
        MutableClock clock = new MutableClock();
        RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1), clock);
        assertThat(limiter.allow("a")).isTrue();
        assertThat(limiter.allow("a")).isFalse();
        clock.now = START.plus(Duration.ofMinutes(1));
        assertThat(limiter.allow("a")).isTrue();
    }

    private static final class MutableClock extends Clock {
        Instant now = START;

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
