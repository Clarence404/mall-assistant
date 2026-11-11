package io.github.clarence404.assistant.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.agent.ConfirmationOutcome;
import io.github.clarence404.assistant.agent.ConfirmationStore;
import io.github.clarence404.assistant.agent.StoredConfirmation;
import io.github.clarence404.assistant.guard.ConcurrencyLimiter;
import io.github.clarence404.assistant.guard.RateLimiter;
import io.github.clarence404.assistant.guard.SemaphoreConcurrencyLimiter;
import io.github.clarence404.assistant.memory.ConversationLocks;
import io.github.clarence404.assistant.memory.ConversationOwner;
import io.github.clarence404.assistant.memory.OwnerCache;
import io.github.clarence404.assistant.ops.UsageStore;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 第 10 章 TODO ch10-1～ch10-5 的验收测试：Redis 实现跑和内存实现同一套契约（{@link SharedStateContract}），外加只有 Redis 才有的：
 * 租期到了名额自动收回、锁过期以后旧令牌删不掉新锁、每个键都带前缀而且都会过期、缓存按 TTL 过期。
 *
 * <p>Redis 从哪来见 {@link RedisTestServer}（Testcontainers 或 {@code REDIS_IT_URL}）。
 */
class RedisStateContractIT extends SharedStateContract {

    static RedisTestServer server;
    static StringRedisTemplate redis;
    static StateFailures failures;

    @BeforeAll
    static void connect() {
        server = RedisTestServer.connect();
        redis = server.client();
        failures = StateFailures.of(StateProperties.OnError.REJECT); // 契约测试里 Redis 是好的，出错就该抛出来，不能悄悄放行
    }

    @AfterAll
    static void cleanUp() {
        if (server != null) {
            server.close();
        }
    }

    @Override
    protected RateLimiter rateLimiter(String name, int limit, Duration window) {
        return new RedisRateLimiter(
                redis, server.keys().of("rate", name), "rate:" + name, () -> limit, window, clock, failures);
    }

    @Override
    protected ConcurrencyLimiter slots(String name, int max) {
        return new RedisConcurrencyLimiter(
                redis,
                server.keys().of("slots", name),
                () -> max,
                Duration.ofMinutes(5),
                Duration.ofMillis(20),
                clock,
                failures,
                new SemaphoreConcurrencyLimiter(max));
    }

    @Override
    protected UsageStore usageStore() {
        return new RedisUsageStore(redis, server.keys(), failures);
    }

    @Override
    protected ConfirmationStore confirmationStore() {
        return new RedisConfirmationStore(
                redis, server.keys(), JsonMapper.builder().build(), failures);
    }

    @Override
    protected ConversationLocks locks() {
        return locks(Duration.ofMinutes(5));
    }

    private ConversationLocks locks(Duration lease) {
        return new RedisConversationLocks(redis, server.keys(), lease, failures);
    }

    @Override
    protected OwnerCache ownerCache() {
        return new RedisOwnerCache(redis, server.keys(), failures);
    }

    /** 名额的租期到了（拿着它的实例挂了），下一个来拿的时候收回。 */
    @Test
    void expiredSlotLeaseIsReclaimed() throws Exception {
        ConcurrencyLimiter limiter = slots("lease-" + run, 1);
        assertThat(limiter.tryAcquire(Duration.ofMillis(50))).isNotNull(); // 拿了不还

        assertThat(limiter.tryAcquire(Duration.ofMillis(50))).isNull();
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertThat(limiter.tryAcquire(Duration.ofMillis(50))).isNotNull();
    }

    /** 锁过了租期自己放开；旧的令牌删不掉新的锁（比较再删除）。 */
    @Test
    void expiredLockFreesItselfAndOldTokenCannotDeleteTheNewOne() throws Exception {
        ConversationLocks locks = locks(Duration.ofMillis(300));
        String key = "conv-" + run;
        String old = locks.tryLock(key);
        Thread.sleep(600);

        String fresh = locks.tryLock(key);
        assertThat(fresh).as("租期到了，别人能占").isNotNull();
        locks.unlock(key, old);

        assertThat(locks.isLocked(key)).as("旧令牌删不掉新锁").isTrue();
        locks.unlock(key, fresh);
        assertThat(locks.isLocked(key)).isFalse();
    }

    /** 归属缓存按 TTL 过期（Redis 的 PX）。 */
    @Test
    void ownerCacheExpires() throws Exception {
        OwnerCache cache = ownerCache();
        cache.put("fp-" + run, ConversationOwner.user("10001"), Duration.ofMillis(300));
        assertThat(cache.get("fp-" + run)).isPresent();

        Thread.sleep(600);

        assertThat(cache.get("fp-" + run)).isEmpty();
    }

    /** 每样状态都写一点，然后看 Redis 里的键：都带前缀、都有过期时间（不会有永远留在 Redis 里的垃圾）。 */
    @Test
    void everyKeyHasThePrefixAndExpires() throws Exception {
        rateLimiter("ttl-" + run, 5, Duration.ofMinutes(1)).tryAcquire("user:1");
        slots("ttl-" + run, 2).tryAcquire(Duration.ofMillis(50));
        usageStore().add(LocalDate.of(2026, 11, 5), entry("user:ttl-" + run, "POLICY", "user:ttl/c1", 10, 5, false));
        ConfirmationStore confirmations = confirmationStore();
        StoredConfirmation saved = confirmation("user:ttl-" + run, "c1", StoredConfirmation.Origin.CHAT);
        confirmations.save(saved, Duration.ofMinutes(65));
        confirmations.claim(saved.id(), ConfirmationOutcome.Decision.REJECT, Duration.ofMinutes(65));
        locks().tryLock("ttl-" + run);
        ownerCache().put("ttl-" + run, ConversationOwner.user("1"), Duration.ofMinutes(5));

        Set<String> keys = server.keysWritten();

        assertThat(keys)
                .isNotEmpty()
                .allSatisfy(k -> assertThat(k).startsWith(server.keys().prefix()));
        assertThat(keys)
                .allSatisfy(
                        k -> assertThat(redis.getExpire(k)).as("%s 要有过期时间", k).isPositive());
    }
}
