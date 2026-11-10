package io.github.clarence404.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.memory.MemoryFixtures;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * 第 8 章（ch08-3）：每个调用方每分钟最多调几次（滑动窗口）。
 */
class McpRateLimiterTest {

    /** 可以拨动的时钟。 */
    private static final class TestClock extends Clock {
        private Instant now = MemoryFixtures.NOW;

        void advance(Duration duration) {
            now = now.plus(duration);
        }

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

    private static McpProperties perMinute(int calls) {
        return new McpProperties(
                "http://localhost:8088/",
                2000,
                calls,
                List.of(),
                Duration.ofMinutes(10),
                McpProperties.Client.defaults());
    }

    @Test
    void allowsUpToTheLimitThenRejects() {
        McpRateLimiter limiter = new McpRateLimiter(perMinute(3), new TestClock());

        assertThat(limiter.tryAcquire("user:10001")).isTrue();
        assertThat(limiter.tryAcquire("user:10001")).isTrue();
        assertThat(limiter.tryAcquire("user:10001")).isTrue();
        assertThat(limiter.tryAcquire("user:10001")).isFalse();
    }

    @Test
    void windowSlides() {
        TestClock clock = new TestClock();
        McpRateLimiter limiter = new McpRateLimiter(perMinute(2), clock);

        limiter.tryAcquire("k");
        clock.advance(Duration.ofSeconds(30));
        limiter.tryAcquire("k");
        assertThat(limiter.tryAcquire("k")).isFalse();

        // 第一次调用满一分钟后滑出窗口，空出一个名额；第二次的还在窗口里
        clock.advance(Duration.ofSeconds(30));
        assertThat(limiter.tryAcquire("k")).isTrue();
        assertThat(limiter.tryAcquire("k")).isFalse();
    }

    @Test
    void rejectedCallsDoNotTakeAQuota() {
        TestClock clock = new TestClock();
        McpRateLimiter limiter = new McpRateLimiter(perMinute(1), clock);

        limiter.tryAcquire("k");
        for (int i = 0; i < 10; i++) {
            clock.advance(Duration.ofSeconds(5));
            limiter.tryAcquire("k");
        }

        // 被拒的 10 次不算数：第一次调用之后满一分钟就又能调了
        clock.advance(Duration.ofSeconds(10));
        assertThat(limiter.tryAcquire("k")).isTrue();
    }

    @Test
    void keysAreIndependent() {
        McpRateLimiter limiter = new McpRateLimiter(perMinute(1), new TestClock());

        assertThat(limiter.tryAcquire("user:10001")).isTrue();
        assertThat(limiter.tryAcquire("user:10002")).isTrue();
        assertThat(limiter.tryAcquire("anonymous")).isTrue();
        assertThat(limiter.tryAcquire("user:10001")).isFalse();
    }

    @Test
    void concurrentCallsNeverExceedTheLimit() throws Exception {
        McpRateLimiter limiter = new McpRateLimiter(perMinute(30), new TestClock());
        List<Callable<Boolean>> calls = IntStream.range(0, 200)
                .mapToObj(i -> (Callable<Boolean>) () -> limiter.tryAcquire("user:10001"))
                .toList();

        long allowed;
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            allowed = pool.invokeAll(calls).stream().filter(f -> get(f)).count();
        }

        assertThat(allowed).isEqualTo(30);
    }

    private static boolean get(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
