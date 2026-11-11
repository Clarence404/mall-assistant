package io.github.clarence404.assistant.mcp;

import io.github.clarence404.assistant.guard.RateLimiter;
import io.github.clarence404.assistant.guard.RateLimiterFactory;
import io.github.clarence404.assistant.guard.SlidingWindowLimiter;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * MCP 工具调用的次数上限：同一个调用方每分钟最多调 {@code assistant.mcp.calls-per-minute} 次（第 8 章引入）。
 *
 * <p>所在层：MCP 的安全。上游是 {@link McpGate#enter}，每次工具调用前问一次「还能不能调」。
 *
 * <p><b>为什么要限次数</b>：第 7 章的 Agent 是我们自己的循环，步数、重复次数、总时长三道刹车都在我们手里；MCP 工具交给的是<b>别人的模型</b>，
 * 它怎么循环我们管不着——小模型可能反复调同一个工具停不下来，提示注入可能让它「把所有订单都查一遍」。服务端能做的就是给每个调用方一个上限，
 * 超了返回「太频繁，稍后再试」，商城不会被一个失控的客户端打满。
 *
 * <p>算法是「滑动窗口」：每个调用方记下最近一分钟里每次调用的时间，先把窗口外的扔掉，剩下的不到上限才放行。比固定窗口（每个整分钟清零）公平：
 * 不会出现「59 秒调 30 次、61 秒再调 30 次」两秒内 60 次的情况。只在一个进程里有效，部署多个实例时要放进 Redis（和第 7 章的确认一样）。
 *
 * <p>第 10 章：限流器由 {@link RateLimiterFactory} 造（{@code assistant.state.store=redis} 时是 Redis 版，键前缀 {@code rate:mcp:}，几个实例合计）。
 */
@Component
public class McpRateLimiter {

    /** 窗口长度。 */
    static final Duration WINDOW = Duration.ofMinutes(1);

    /**
     * 第 9 章：算法抽成了通用的 {@link SlidingWindowLimiter}（对话接口的限流也用它），这里只是填上 MCP 的上限和窗口；
     * 第 10 章：换成接口 {@link RateLimiter}，内存版还是它，Redis 版是 {@code state.RedisRateLimiter}。
     */
    private final RateLimiter limiter;

    /**
     * 创建：进程内存（第 8 章的样子；测试里手工组装时用）。
     *
     * @param properties {@code assistant.mcp.*}（每分钟上限）
     * @param clock 时钟（测试里拨动它）
     */
    public McpRateLimiter(McpProperties properties, Clock clock) {
        this(properties, (name, limit, window) -> new SlidingWindowLimiter(limit, window, clock));
    }

    /**
     * 创建（Spring 注入，第 10 章）。
     *
     * @param properties {@code assistant.mcp.*}（每分钟上限）
     * @param limiters 限流器工厂（内存或 Redis，按 {@code assistant.state.store}）
     */
    @Autowired
    public McpRateLimiter(McpProperties properties, RateLimiterFactory limiters) {
        this.limiter = limiters.create("mcp", properties::callsPerMinute, WINDOW);
    }

    /**
     * 这个调用方现在能不能再调一次；能的话记下这一次（ch08-3）。
     *
     * <p>第 9 章起委托给 {@link SlidingWindowLimiter#tryAcquire}，下面是第 8 章写它时的要求（测试按这个写）：
     *
     * <ul>
     *   <li>每个 key 一个队列，存最近的调用时间；先从队头扔掉不在窗口里的（时间早于或等于「现在 - 1 分钟」）
     *   <li>剩下的少于 {@code calls-per-minute} 就记下现在、返回 true；否则返回 false，<b>不记</b>这一次（被拒的调用不占名额）
     *   <li>同一个 key 的并发调用要串行判断（对队列加锁），不同 key 互不影响
     * </ul>
     *
     * @param key 调用方，如 {@code user:10001}、{@code anonymous}
     * @return 放行时为 true
     * @throws io.github.clarence404.assistant.state.StateUnavailableException Redis 出错、而策略是 reject 时（第 10 章）
     */
    public boolean tryAcquire(String key) {
        return limiter.tryAcquire(key);
    }
}
