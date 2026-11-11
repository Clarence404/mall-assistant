package io.github.clarence404.assistant.state;

import io.github.clarence404.assistant.agent.ConfirmationStore;
import io.github.clarence404.assistant.agent.MemoryConfirmationStore;
import io.github.clarence404.assistant.guard.ConcurrencyLimiter;
import io.github.clarence404.assistant.guard.RateLimiter;
import io.github.clarence404.assistant.guard.SemaphoreConcurrencyLimiter;
import io.github.clarence404.assistant.guard.SlidingWindowLimiter;
import io.github.clarence404.assistant.memory.ConversationLocks;
import io.github.clarence404.assistant.memory.MemoryConversationLocks;
import io.github.clarence404.assistant.memory.MemoryOwnerCache;
import io.github.clarence404.assistant.memory.OwnerCache;
import io.github.clarence404.assistant.ops.MemoryUsageStore;
import io.github.clarence404.assistant.ops.UsageStore;
import java.time.Duration;

/**
 * 第 10 章：共享状态的契约测试，内存实现（前几章的数据结构挪出来以后，行为没变）。不需要 Docker，也不需要 Redis。
 */
class MemoryStateContractTest extends SharedStateContract {

    @Override
    protected RateLimiter rateLimiter(String name, int limit, Duration window) {
        return new SlidingWindowLimiter(() -> limit, window, clock);
    }

    @Override
    protected ConcurrencyLimiter slots(String name, int max) {
        return new SemaphoreConcurrencyLimiter(max);
    }

    @Override
    protected UsageStore usageStore() {
        return new MemoryUsageStore(500);
    }

    @Override
    protected ConfirmationStore confirmationStore() {
        return new MemoryConfirmationStore();
    }

    @Override
    protected ConversationLocks locks() {
        return new MemoryConversationLocks();
    }

    @Override
    protected OwnerCache ownerCache() {
        return new MemoryOwnerCache(clock);
    }
}
