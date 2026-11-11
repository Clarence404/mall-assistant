package io.github.clarence404.assistant.guard;

import java.time.Duration;
import java.util.function.IntSupplier;

/**
 * 造限流器的工厂：同一套代码，按 {@code assistant.state.store} 造出内存版或 Redis 版（第 10 章引入）。
 *
 * <p>所在层：上线保护的装配。{@code state.MemoryStateConfig} 提供内存版（{@link SlidingWindowLimiter}），{@code state.RedisStateConfig}
 * 提供 Redis 版；{@link ChatGuard} 和 {@code mcp.McpRateLimiter} 在构造时各要一个。
 *
 * <p>为什么是工厂而不是直接注入一个 {@link RateLimiter}：两个用户的上限、窗口不一样，而且 Redis 版要给每个用户一个自己的键前缀
 * （{@code rate:chat:…}、{@code rate:mcp:…}），不然 MCP 的调用会占掉对话的名额。
 */
@FunctionalInterface
public interface RateLimiterFactory {

    /**
     * 造一个限流器。
     *
     * @param name 用途，进 Redis 的键名，如 {@code chat}、{@code mcp}
     * @param limit 窗口内最多几次（每次判断时读一次，配置改了立刻生效）
     * @param window 窗口长度
     * @return 限流器
     */
    RateLimiter create(String name, IntSupplier limit, Duration window);
}
