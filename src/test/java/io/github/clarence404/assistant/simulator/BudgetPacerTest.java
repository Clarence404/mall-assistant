package io.github.clarence404.assistant.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class BudgetPacerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static Clock at(int hour, int minute) {
        Instant t = ZonedDateTime.of(2026, 11, 12, hour, minute, 0, 0, ZONE).toInstant();
        return Clock.fixed(t, ZONE);
    }

    @Test
    void spreadsTheDailyBudgetOverTheDay() {
        // 预算 10 万、留一成：到中午 12 点最多用 9 万 × (12/24 + 1/24) = 48750
        BudgetPacer pacer = new BudgetPacer(0.1, ZONE, at(12, 0));
        assertThat(pacer.allowedSoFar(100_000)).isEqualTo(48_750);
        pacer.update("13800000000", 47_000, 100_000);
        assertThat(pacer.allow("13800000000", 1500)).isTrue();
        assertThat(pacer.allow("13800000000", 2000)).isFalse(); // 再问一个 Agent 的事就超了配速线
    }

    @Test
    void earlyMorningOnlyGetsAnHourOfHeadroom() {
        BudgetPacer pacer = new BudgetPacer(0.1, ZONE, at(0, 0));
        assertThat(pacer.allowedSoFar(100_000)).isEqualTo(3750); // 9 万 / 24
        pacer.update("anonymous", 3000, 100_000);
        assertThat(pacer.allow("anonymous", 700)).isTrue();
        assertThat(pacer.allow("anonymous", 1800)).isFalse();
    }

    @Test
    void neverExceedsBudgetMinusReserveAtNight() {
        BudgetPacer pacer = new BudgetPacer(0.1, ZONE, at(23, 59));
        assertThat(pacer.allowedSoFar(100_000)).isEqualTo(90_000);
    }

    @Test
    void unlimitedBudgetAlwaysAllows() {
        BudgetPacer pacer = new BudgetPacer(0.1, ZONE, at(0, 0));
        pacer.update("13800000001", 5_000_000, 0); // 0 = 不限
        assertThat(pacer.allow("13800000001", 8000)).isTrue();
    }

    @Test
    void exhaustedCallerIsSkippedUntilMidnight() {
        Clock[] clock = {at(15, 0)};
        Clock delegating = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZONE;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return clock[0].withZone(zone);
            }

            @Override
            public Instant instant() {
                return clock[0].instant();
            }
        };
        BudgetPacer pacer = new BudgetPacer(0.1, ZONE, delegating);
        pacer.update("13800000002", 10_000, 100_000);
        pacer.exhausted("13800000002"); // 收到 429 TOKEN_BUDGET_EXCEEDED（比如真人在页面上用光了）
        assertThat(pacer.allow("13800000002", 700)).isFalse();

        clock[0] = Clock.fixed(ZonedDateTime.of(2026, 11, 13, 2, 0, 0, 0, ZONE).toInstant(), ZONE); // 第二天
        assertThat(pacer.allow("13800000002", 700)).isTrue();
        assertThat(pacer.snapshot().get("13800000002").used()).isZero();
    }

    @Test
    void localEstimatesCountWhenUsageIsMissing() {
        BudgetPacer pacer = new BudgetPacer(0.1, ZONE, at(12, 0));
        assertThat(pacer.known("anonymous")).isFalse();
        pacer.update("anonymous", 0, 100_000);
        assertThat(pacer.known("anonymous")).isTrue();
        for (int i = 0; i < 27; i++) {
            pacer.add("anonymous", 1800);
        }
        assertThat(pacer.allow("anonymous", 1800)).isFalse(); // 27 × 1800 = 48600，再加 1800 超过 48750
    }
}
