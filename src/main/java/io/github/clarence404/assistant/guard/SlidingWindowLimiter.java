package io.github.clarence404.assistant.guard;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * 滑动窗口计数：每个 key 在最近一个窗口里最多放行几次（第 9 章从第 8 章的 {@code McpRateLimiter} 抽出来；第 10 章起是 {@link RateLimiter} 的内存实现）。
 *
 * <p>所在层：上线保护的工具类。第 8 章的 {@code McpRateLimiter}（MCP 工具调用，每分钟 30 次）和第 9 章的 {@link ChatGuard}（对话接口，每分钟 20 次）
 * 都用它；算法和第 8 章 ch08-3 写的完全一样，只是上限和窗口变成了参数。
 *
 * <p>为什么是滑动窗口而不是固定窗口（每个整分钟清零）：固定窗口在「59 秒问 20 次、61 秒再问 20 次」时两秒内放行了 40 次。滑动窗口记下每次的时间，
 * 永远只看「现在往前一个窗口」。代价是每个 key 要存最多「上限」个时间戳，对客服这点量不算什么。只在一个进程里有效：部署多个实例时，要么接受「每个实例各算各的」，
 * 要么放进 Redis——第 10 章的 {@code state.RedisRateLimiter} 用有序集合的 ZREMRANGEBYSCORE / ZCARD / ZADD 三条命令做同一个算法，包在一段 Lua 脚本里原子执行。
 */
public final class SlidingWindowLimiter implements RateLimiter {

    private final IntSupplier limit;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> calls = new ConcurrentHashMap<>();

    /**
     * 创建。
     *
     * @param limit 窗口内最多几次（每次判断时读一次，配置改了立刻生效）
     * @param window 窗口长度
     * @param clock 时钟（测试里拨动它）
     */
    public SlidingWindowLimiter(IntSupplier limit, Duration window, Clock clock) {
        this.limit = limit;
        this.window = window;
        this.clock = clock;
    }

    /**
     * 这个 key 现在能不能再来一次；能的话记下这一次。
     *
     * <p>先扔掉窗口外的（时间早于或等于「现在 - 窗口」），剩下的少于上限才记下现在、放行；被拒的不记（不占名额）。同一个 key 的并发调用对队列加锁串行判断，
     * 不同 key 互不影响。
     *
     * @param key 调用方，如 {@code user:10001}、{@code ip:127.0.0.1}
     * @return 放行时为 true
     */
    @Override
    public boolean tryAcquire(String key) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(window);
        Deque<Instant> deque = calls.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && !deque.peekFirst().isAfter(cutoff)) {
                deque.pollFirst();
            }
            if (deque.size() >= limit.getAsInt()) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }

    /**
     * 被拒之后多久能再来：窗口里最早那一次滑出窗口的时间（至少 1 秒），用来填 {@code Retry-After}。
     *
     * @param key 调用方
     * @return 等多久；窗口里没有记录时为 0
     */
    @Override
    public Duration retryAfter(String key) {
        Deque<Instant> deque = calls.get(key);
        if (deque == null) {
            return Duration.ZERO;
        }
        synchronized (deque) {
            Instant oldest = deque.peekFirst();
            if (oldest == null) {
                return Duration.ZERO;
            }
            Duration wait = Duration.between(clock.instant(), oldest.plus(window));
            return wait.isNegative() ? Duration.ZERO : wait.plusSeconds(1).withNanos(0);
        }
    }

    /** 清掉已经空了的 key（定期调用，免得只来过一次的 IP 一直占着内存）。 */
    @Override
    public void evictIdle() {
        Instant cutoff = clock.instant().minus(window);
        calls.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                Instant last = e.getValue().peekLast();
                return last == null || !last.isAfter(cutoff);
            }
        });
    }
}
