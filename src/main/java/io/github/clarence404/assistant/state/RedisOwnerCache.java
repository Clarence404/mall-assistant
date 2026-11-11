package io.github.clarence404.assistant.state;

import io.github.clarence404.assistant.memory.ConversationOwner;
import io.github.clarence404.assistant.memory.OwnerCache;
import java.time.Duration;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 「这个商城令牌是谁」缓存的 Redis 实现：{@code owner:令牌指纹 → 用户 ID}，{@code SET PX} 带过期（第 10 章引入）。
 *
 * <p>所在层：共享状态。{@code assistant.state.store=redis} 时注入 {@code memory.ConversationOwners}。键里是令牌的 SHA-256 指纹，不是令牌本身——
 * 拿到 Redis 读权限的人也拿不到能用的令牌。
 *
 * <p>出错一律当作「没缓存」：多问一次商城，结果一样。这是共享状态里最「无所谓」的一样，见 {@code memory.OwnerCache} 的说明。
 */
public final class RedisOwnerCache implements OwnerCache {

    private static final String COMPONENT = "owner-cache";

    private final StringRedisTemplate redis;
    private final RedisKeys keys;
    private final StateFailures failures;

    /**
     * 创建。
     *
     * @param redis Redis 客户端
     * @param keys 键前缀
     * @param failures 出错时记一下
     */
    public RedisOwnerCache(StringRedisTemplate redis, RedisKeys keys, StateFailures failures) {
        this.redis = redis;
        this.keys = keys;
        this.failures = failures;
    }

    @Override
    public Optional<ConversationOwner> get(String fingerprint) {
        try {
            failures.ensureAvailable();
            String userId = redis.opsForValue().get(keys.of("owner", fingerprint));
            return userId == null ? Optional.empty() : Optional.of(ConversationOwner.user(userId));
        } catch (DataAccessException e) {
            failures.record(COMPONENT, e);
            return Optional.empty();
        }
    }

    @Override
    public void put(String fingerprint, ConversationOwner owner, Duration ttl) {
        if (owner.userId() == null) {
            return;
        }
        try {
            failures.ensureAvailable();
            redis.opsForValue().set(keys.of("owner", fingerprint), owner.userId(), ttl);
        } catch (DataAccessException e) {
            failures.record(COMPONENT, e);
        }
    }
}
