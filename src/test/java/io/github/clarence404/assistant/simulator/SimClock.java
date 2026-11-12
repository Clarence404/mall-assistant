package io.github.clarence404.assistant.simulator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 模拟时钟：决定「现在是一天中的几点」，用来查流量曲线。
 *
 * <p>acceleration = 1 时就是真实时间；设成 24 时，真实过 1 小时、模拟时间过 24 小时，演示时一小时就能看完一天的起伏。只影响曲线，不影响思考时间等真实的等待。
 */
public final class SimClock {

    private final Clock clock;
    private final double acceleration;
    private final Instant realStart;
    private final LocalDateTime simStart;

    /**
     * 创建，以当前真实时刻为起点。
     *
     * @param clock 真实时钟（测试里传固定时钟）
     * @param acceleration 加速倍数，≤ 0 时按 1 处理
     */
    public SimClock(Clock clock, double acceleration) {
        this.clock = clock;
        this.acceleration = acceleration > 0 ? acceleration : 1;
        this.realStart = clock.instant();
        this.simStart = LocalDateTime.ofInstant(realStart, clock.getZone());
    }

    /**
     * 当前的模拟时刻。
     *
     * @return 起点 + 真实流逝时间 × 加速倍数
     */
    public LocalDateTime now() {
        long realMillis = Duration.between(realStart, clock.instant()).toMillis();
        return simStart.plus(Duration.ofMillis((long) (realMillis * acceleration)));
    }

    /**
     * 加速倍数。
     *
     * @return 倍数
     */
    public double acceleration() {
        return acceleration;
    }
}
