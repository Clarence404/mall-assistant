package io.github.clarence404.assistant.guard;

import java.time.Duration;

/**
 * 「每个 key 在一个窗口里最多几次」的限流器（第 10 章从 {@link SlidingWindowLimiter} 抽出接口）。
 *
 * <p>所在层：上线保护。两个实现：{@link SlidingWindowLimiter}（进程内存，第 8、9 章的做法，默认）和 {@code state.RedisRateLimiter}
 * （Redis 有序集合 + Lua 脚本，多个实例合计）。用的人是 {@link ChatGuard}（对话接口每分钟 20 次）和 {@code mcp.McpRateLimiter}
 * （MCP 工具每分钟 30 次），它们只认这个接口，由 {@link RateLimiterFactory} 按 {@code assistant.state.store} 造出具体的实现。
 */
public interface RateLimiter {

    /**
     * 这个 key 现在能不能再来一次；能的话记下这一次（被拒的不记，不占名额）。
     *
     * @param key 调用方，如 {@code user:10001}、{@code ip:127.0.0.1}
     * @return 放行时为 true
     * @throws io.github.clarence404.assistant.state.StateUnavailableException Redis 实现出错、而策略是 reject 时
     */
    boolean tryAcquire(String key);

    /**
     * 被拒之后多久能再来（填 {@code Retry-After}）。
     *
     * @param key 调用方
     * @return 等多久；不知道时为 0
     */
    Duration retryAfter(String key);

    /** 清掉已经空了的 key（进程内存的实现要定期清；Redis 的键自己会过期，什么都不用做）。 */
    default void evictIdle() {}
}
