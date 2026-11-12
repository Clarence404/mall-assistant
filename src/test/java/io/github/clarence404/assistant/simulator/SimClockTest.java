package io.github.clarence404.assistant.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SimClockTest {

    /** 可以手动拨动的时钟。 */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-12T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void realTimeWhenNotAccelerated() {
        MutableClock real = new MutableClock();
        SimClock clock = new SimClock(real, 1);
        real.now = real.now.plus(Duration.ofMinutes(90));
        assertThat(clock.now()).isEqualTo(LocalDateTime.of(2026, 11, 12, 1, 30));
    }

    @Test
    void acceleratedClockRunsADayInAnHour() {
        MutableClock real = new MutableClock();
        SimClock clock = new SimClock(real, 24);
        real.now = real.now.plus(Duration.ofMinutes(30));
        assertThat(clock.now()).isEqualTo(LocalDateTime.of(2026, 11, 12, 12, 0));
    }

    @Test
    void nonPositiveAccelerationMeansRealTime() {
        assertThat(new SimClock(new MutableClock(), 0).acceleration()).isEqualTo(1);
    }
}
