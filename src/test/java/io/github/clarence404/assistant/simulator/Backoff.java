package io.github.clarence404.assistant.simulator;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * 指数退避：连续失败时等待时间翻倍（base、2×base、4×base……封顶 max），再乘一个 0.5～1.0 的随机系数（抖动），成功一次就清零。
 *
 * <p>为什么要退避：智能客服没起、被限流（429）、太忙或模型熔断（503）时，虚拟用户如果照常提问，就成了「狂刷」，既污染监控又给 Ollama 添乱；
 * 抖动是为了让几个虚拟用户不要在同一时刻一起重试。
 *
 * <p>不是线程安全的：每个虚拟用户各有一个。
 */
public final class Backoff {

    private final Duration base;
    private final Duration max;
    private int failures;

    /**
     * 创建。
     *
     * @param base 第一次失败后的等待
     * @param max 最长等待
     */
    public Backoff(Duration base, Duration max) {
        this.base = base;
        this.max = max;
    }

    /**
     * 记一次失败，返回这次应该等多久。
     *
     * @param random 随机源
     * @return 等待时间，介于 0.5 倍和 1 倍的「base × 2^(连续失败次数-1)」之间，不超过 max
     */
    public Duration onFailure(RandomGenerator random) {
        failures++;
        long exp = base.toMillis() << Math.min(failures - 1, 20); // 左移封顶，防止溢出
        long capped = Math.min(exp, max.toMillis());
        long jittered = (long) (capped * (0.5 + random.nextDouble() * 0.5));
        return Duration.ofMillis(Math.max(jittered, 1));
    }

    /** 成功一次：清零。 */
    public void onSuccess() {
        failures = 0;
    }

    /**
     * 连续失败了几次。
     *
     * @return 次数
     */
    public int failures() {
        return failures;
    }
}
