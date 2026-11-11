package io.github.clarence404.assistant.state;

import io.github.clarence404.assistant.guard.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 滑动窗口限流的 Redis 实现：几个实例合计（第 10 章引入）。
 *
 * <p>所在层：共享状态。{@code assistant.state.store=redis} 时由 {@link RedisStateConfig} 的限流器工厂造出来，给 {@code guard.ChatGuard}
 * （键 {@code rate:chat:…}）和 {@code mcp.McpRateLimiter}（键 {@code rate:mcp:…}）用。
 *
 * <p>算法和内存版 {@code guard.SlidingWindowLimiter} 一样：每个调用方一个有序集合（成员是每一次调用，分数是时间），先删掉窗口外的、再数、
 * 不到上限就记下这一次。三步写在一段 Lua 脚本里（{@code redis/sliding-window.lua}），在 Redis 里原子执行。
 *
 * <p><b>为什么不用更简单的 {@code INCR} + {@code EXPIRE}</b>（固定窗口：每个整分钟一个计数器）：一条 INCR 本身是原子的，也够用，但它是「固定窗口」，
 * 59 秒问 20 次、61 秒再问 20 次，两秒内放行 40 次——第 8 章选滑动窗口就是为了避免这个。代价是每个调用方要存最多「上限」个成员，客服这点量不算什么。
 *
 * <p><b>时间用谁的</b>：脚本里的「现在」由应用传进去（注入的 {@link Clock}），测试可以拨时钟。代价是实例之间的时钟要同步（NTP），
 * 偏差多少，窗口就歪多少；不想依赖实例的时钟，可以在脚本里用 Redis 的 {@code TIME} 命令取时间。
 */
public final class RedisRateLimiter implements RateLimiter {

    private static final RedisScript<Long> SCRIPT = RedisKeys.script("sliding-window.lua", Long.class);

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final String component;
    private final IntSupplier limit;
    private final Duration window;
    private final Clock clock;
    private final StateFailures failures;

    /**
     * 创建。
     *
     * @param redis Redis 客户端
     * @param keyPrefix 这个限流器的键前缀，如 {@code mall-assistant:rate:chat}，后面接调用方
     * @param component 出错时记在指标里的名字，如 {@code rate:chat}
     * @param limit 窗口内最多几次
     * @param window 窗口长度
     * @param clock 时钟
     * @param failures 出错时怎么办
     */
    public RedisRateLimiter(
            StringRedisTemplate redis,
            String keyPrefix,
            String component,
            IntSupplier limit,
            Duration window,
            Clock clock,
            StateFailures failures) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;
        this.component = component;
        this.limit = limit;
        this.window = window;
        this.clock = clock;
        this.failures = failures;
    }

    /**
     * 这个调用方现在能不能再来一次（ch10-1 的 Java 一侧；算法在 {@code redis/sliding-window.lua}）。
     *
     * <p>执行脚本：KEYS 是这个调用方的键，ARGV 依次是现在（毫秒）、窗口（毫秒）、上限、这一次的成员名（现在 + 随机串）；返回 1 放行。
     * Redis 出错（含冷却期）按 {@link StateFailures#allowOrThrow} 处理：放行策略返回 true，拒绝策略抛异常。
     */
    @Override
    public boolean tryAcquire(String key) {
        long now = clock.millis();
        try {
            failures.ensureAvailable();
            Long allowed = redis.execute(
                    SCRIPT,
                    List.of(keyPrefix + ":" + key),
                    String.valueOf(now),
                    String.valueOf(window.toMillis()),
                    String.valueOf(limit.getAsInt()),
                    now + "-" + UUID.randomUUID());
            return allowed != null && allowed == 1L;
        } catch (DataAccessException e) {
            return failures.allowOrThrow(component, e);
        }
    }

    /**
     * 被拒之后多久能再来：窗口里最早那一次滑出窗口的时间（至少 1 秒），和内存版的算法一样。只是建议值，不需要原子；出错时给 1 秒。
     */
    @Override
    public Duration retryAfter(String key) {
        try {
            failures.ensureAvailable();
            Set<ZSetOperations.TypedTuple<String>> oldest =
                    redis.opsForZSet().rangeWithScores(keyPrefix + ":" + key, 0, 0);
            if (oldest == null || oldest.isEmpty()) {
                return Duration.ZERO;
            }
            Double score = oldest.iterator().next().getScore();
            long waitMs = (score == null ? 0 : score.longValue()) + window.toMillis() - clock.millis();
            return waitMs < 0
                    ? Duration.ZERO
                    : Duration.ofMillis(waitMs).plusSeconds(1).withNanos(0);
        } catch (DataAccessException e) {
            failures.record(component, e);
            return Duration.ofSeconds(1);
        }
    }
}
