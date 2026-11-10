package io.github.clarence404.assistant.ops;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 第 9 章测试用的时钟：手动拨（滑动窗口、熔断时长、零点清零都靠它测，不用真的等）。和第 6 章的 {@code MemoryFixtures.MutableClock} 一样，
 * 只是放在这里、构造器公开，别的包的测试也能用。
 */
public final class ManualClock extends Clock {

    private volatile Instant now;

    /**
     * 创建。
     *
     * @param now 起始时刻
     */
    public ManualClock(Instant now) {
        this.now = now;
    }

    /** 2026-11-05 10:00（UTC），即上海时间 18:00。 */
    public static ManualClock standard() {
        return new ManualClock(Instant.parse("2026-11-05T10:00:00Z"));
    }

    /**
     * 往后拨。
     *
     * @param duration 拨多久
     */
    public void advance(Duration duration) {
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
