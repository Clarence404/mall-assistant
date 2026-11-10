package io.github.clarence404.assistant.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.ops.ChatMetrics;
import io.github.clarence404.assistant.ops.ManualClock;
import io.github.clarence404.assistant.ops.ModelCall;
import io.github.clarence404.assistant.ops.TurnRecord;
import io.github.clarence404.assistant.ops.UsageLedger;
import io.github.clarence404.assistant.ops.UsageProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 第 9 章 TODO ch09-6：对话接口的三关——每日预算、每分钟次数、并发上限。
 */
class ChatGuardTest {

    private final ManualClock clock = ManualClock.standard();

    @Test
    void rateLimitPerCallerWithRetryAfter() {
        ChatGuard guard = guard(properties(true, 3, 8), 0);

        for (int i = 0; i < 3; i++) {
            guard.admit("user:10001").close();
        }

        assertThatThrownBy(() -> guard.admit("user:10001")).isInstanceOfSatisfying(ChatRejection.class, e -> {
            assertThat(e.reason()).isEqualTo(ChatRejection.Reason.RATE_LIMITED);
            assertThat(e.retryAfter()).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(61));
        });
        // 别人不受影响；一分钟后自己也恢复
        guard.admit("ip:10.0.0.8").close();
        clock.advance(Duration.ofSeconds(61));
        guard.admit("user:10001").close();
    }

    @Test
    void dailyBudgetIsCheckedFirstEvenWhenGuardIsOff() {
        UsageLedger ledger = new UsageLedger(UsageProperties.of(500), clock);
        TurnRecord spent = new TurnRecord("user:10001", null);
        spent.add(new ModelCall("rag", null, 450, 60, false, 10));
        ledger.commit(spent);
        ChatGuard guard = new ChatGuard(properties(false, 3, 8), ledger, ChatMetrics.noop(), clock);

        assertThatThrownBy(() -> guard.admit("user:10001")).isInstanceOfSatisfying(ChatRejection.class, e -> {
            assertThat(e.reason()).isEqualTo(ChatRejection.Reason.TOKEN_BUDGET_EXCEEDED);
            assertThat(e.retryAfter()).isEqualTo(Duration.ofHours(6)); // 上海时间 18:00 → 零点
        });
        // 别人还有额度
        assertThat(guard.admit("user:10002")).isSameAs(ChatGuard.Permit.NONE);
    }

    @Test
    void disabledGuardDoesNotLimit() {
        ChatGuard guard = guard(properties(false, 1, 1), 0);

        for (int i = 0; i < 10; i++) {
            assertThat(guard.admit("ip:127.0.0.1")).isSameAs(ChatGuard.Permit.NONE);
        }
    }

    @Test
    void concurrencyLimitRejectsWhenFullAndPermitReleasesOnce() {
        ChatGuard guard = guard(properties(true, 100, 2), 0);

        ChatGuard.Permit first = guard.admit("a");
        ChatGuard.Permit second = guard.admit("b");
        assertThat(guard.inflight()).isEqualTo(2);
        assertThatThrownBy(() -> guard.admit("c")).isInstanceOfSatisfying(ChatRejection.class, e -> {
            assertThat(e.reason()).isEqualTo(ChatRejection.Reason.TOO_BUSY);
            assertThat(e.retryAfter()).isEqualTo(ChatGuard.BUSY_RETRY_AFTER);
        });

        first.close();
        first.close(); // 关两次只还一个名额
        assertThat(guard.inflight()).isEqualTo(1);
        ChatGuard.Permit third = guard.admit("c");
        assertThatThrownBy(() -> guard.admit("d")).isInstanceOf(ChatRejection.class);
        second.close();
        third.close();
        assertThat(guard.inflight()).isZero();
    }

    /** 50 个人同时来、并发上限 5：同一时刻最多 5 个在里面。 */
    @Test
    void neverMoreThanMaxConcurrentInside() throws Exception {
        ChatGuard guard = new ChatGuard(
                new GuardProperties(
                        true,
                        1000,
                        5,
                        Duration.ofSeconds(5),
                        new GuardProperties.Circuit(true, 5, Duration.ofSeconds(30)),
                        new GuardProperties.Injection(true, List.of()),
                        new GuardProperties.Degrade(true, 200)),
                new UsageLedger(UsageProperties.of(0), clock),
                ChatMetrics.noop(),
                clock);
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                String caller = "ip:10.0.0." + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try (ChatGuard.Permit permit = guard.admit(caller)) {
                        peak.accumulateAndGet(inside.incrementAndGet(), Math::max);
                        Thread.sleep(5);
                        inside.decrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }
        assertThat(peak.get()).isBetween(1, 5);
        assertThat(guard.inflight()).isZero();
    }

    private ChatGuard guard(GuardProperties properties, long budget) {
        return new ChatGuard(properties, new UsageLedger(UsageProperties.of(budget), clock), ChatMetrics.noop(), clock);
    }

    /** 等空位只等 50 毫秒，测试不用干等两秒。 */
    static GuardProperties properties(boolean enabled, int perMinute, int maxConcurrent) {
        return new GuardProperties(
                enabled,
                perMinute,
                maxConcurrent,
                Duration.ofMillis(50),
                new GuardProperties.Circuit(true, 5, Duration.ofSeconds(30)),
                new GuardProperties.Injection(true, List.of()),
                new GuardProperties.Degrade(true, 200));
    }
}
