package io.github.clarence404.assistant.memory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 归属缓存的内存实现：指纹 → （归属，过期时间）（第 6 章的做法，第 10 章挪到这里）。只存指纹，不存令牌原文。
 *
 * <p>所在层：记忆。过期判断用注入的时钟，测试里拨动它。
 */
public final class MemoryOwnerCache implements OwnerCache {

    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * 创建。
     *
     * @param clock 时钟
     */
    public MemoryOwnerCache(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<ConversationOwner> get(String fingerprint) {
        Cached cached = cache.get(fingerprint);
        return cached != null && cached.expiresAt().isAfter(clock.instant())
                ? Optional.of(cached.owner())
                : Optional.empty();
    }

    @Override
    public void put(String fingerprint, ConversationOwner owner, Duration ttl) {
        cache.put(fingerprint, new Cached(owner, clock.instant().plus(ttl)));
    }

    @Override
    public void evictExpired() {
        Instant now = clock.instant();
        cache.values().removeIf(c -> !c.expiresAt().isAfter(now));
    }

    /** 缓存项：归属与过期时间。 */
    private record Cached(ConversationOwner owner, Instant expiresAt) {}
}
