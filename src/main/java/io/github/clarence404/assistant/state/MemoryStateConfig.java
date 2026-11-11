package io.github.clarence404.assistant.state;

import io.github.clarence404.assistant.agent.ConfirmationStore;
import io.github.clarence404.assistant.agent.MemoryConfirmationStore;
import io.github.clarence404.assistant.guard.ConcurrencyLimiter;
import io.github.clarence404.assistant.guard.GuardProperties;
import io.github.clarence404.assistant.guard.RateLimiterFactory;
import io.github.clarence404.assistant.guard.SemaphoreConcurrencyLimiter;
import io.github.clarence404.assistant.guard.SlidingWindowLimiter;
import io.github.clarence404.assistant.memory.ConversationLocks;
import io.github.clarence404.assistant.memory.MemoryConversationLocks;
import io.github.clarence404.assistant.memory.MemoryOwnerCache;
import io.github.clarence404.assistant.memory.OwnerCache;
import io.github.clarence404.assistant.ops.MemoryUsageStore;
import io.github.clarence404.assistant.ops.UsageProperties;
import io.github.clarence404.assistant.ops.UsageStore;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 共享状态的内存实现（默认，{@code assistant.state.store} 不写或写 memory）：前九章的做法，零依赖，只在一个进程里有效（第 10 章引入）。
 *
 * <p>所在层：共享状态的装配。这里每个 Bean 都是前几章写在业务类里的那份数据结构，原样挪出来、实现了接口；和 {@link RedisStateConfig} 二选一，
 * 业务类（{@code ChatGuard}、{@code McpRateLimiter}、{@code UsageLedger}、{@code ConfirmationService}、{@code ConversationMemory}、
 * {@code ConversationOwners}）只认接口，不知道自己用的是哪一种。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "assistant.state.store", havingValue = "memory", matchIfMissing = true)
public class MemoryStateConfig {

    /**
     * 限流器：进程内的滑动窗口（第 8、9 章）。
     *
     * @param clock 时钟
     * @return 工厂
     */
    @Bean
    public RateLimiterFactory memoryRateLimiterFactory(Clock clock) {
        return (name, limit, window) -> new SlidingWindowLimiter(limit, window, clock);
    }

    /**
     * 并发名额：一个公平的信号量（第 9 章）。
     *
     * @param guard {@code assistant.guard.*}（上限）
     * @return 并发名额
     */
    @Bean
    public ConcurrencyLimiter memoryConcurrencyLimiter(GuardProperties guard) {
        return new SemaphoreConcurrencyLimiter(guard.maxConcurrent());
    }

    /**
     * Token 总账：三张表（第 9 章）。
     *
     * @param usage {@code assistant.usage.*}（会话条数上限）
     * @return 账
     */
    @Bean
    public UsageStore memoryUsageStore(UsageProperties usage) {
        return new MemoryUsageStore(usage.maxConversations());
    }

    /**
     * 待确认操作：一个 Map（第 7 章）。
     *
     * @return 存储
     */
    @Bean
    public ConfirmationStore memoryConfirmationStore() {
        return new MemoryConfirmationStore();
    }

    /**
     * 「正在回答」标记：一个集合（第 6 章）。
     *
     * @return 标记
     */
    @Bean
    public ConversationLocks memoryConversationLocks() {
        return new MemoryConversationLocks();
    }

    /**
     * 「令牌是谁」的缓存（第 6 章）。
     *
     * @param clock 时钟
     * @return 缓存
     */
    @Bean
    public OwnerCache memoryOwnerCache(Clock clock) {
        return new MemoryOwnerCache(clock);
    }
}
