package io.github.clarence404.assistant.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.agent.AgentTool;
import io.github.clarence404.assistant.agent.PendingAction;
import io.github.clarence404.assistant.agent.PendingConfirmation;
import io.github.clarence404.assistant.agent.StoredConfirmation;
import io.github.clarence404.assistant.guard.ChatGuard;
import io.github.clarence404.assistant.guard.ChatRejection;
import io.github.clarence404.assistant.guard.ConcurrencyLimiter;
import io.github.clarence404.assistant.guard.GuardProperties;
import io.github.clarence404.assistant.guard.SemaphoreConcurrencyLimiter;
import io.github.clarence404.assistant.memory.ConversationLocks;
import io.github.clarence404.assistant.memory.ConversationOwner;
import io.github.clarence404.assistant.ops.ChatMetrics;
import io.github.clarence404.assistant.ops.ManualClock;
import io.github.clarence404.assistant.ops.UsageLedger;
import io.github.clarence404.assistant.ops.UsageProperties;
import io.github.clarence404.assistant.ops.UsageStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 第 10 章 TODO ch10-6：Redis 挂了的时候，每样状态按事先定好的策略处理（不需要 Docker：连一个没人监听的端口，就是「Redis 挂了」）。
 *
 * <ul>
 *   <li>放行（allow）：限流放行、预算当作没用、并发名额退回本实例的信号量、会话标记跳过、记账只记指标
 *   <li>拒绝（reject）：限流、预算、名额、会话标记都抛 {@link StateUnavailableException}，对话接口 503 STATE_UNAVAILABLE
 *   <li>不管哪种策略：待确认操作一律拒绝；归属缓存当作没缓存；每次出错都记进 {@code assistant.state.errors}
 *   <li>冷却：出错一次之后几秒内不再碰 Redis，直接按出错处理（不让每个请求都等一次连接超时）
 * </ul>
 */
class RedisDownTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private final ManualClock clock = ManualClock.standard();
    private final RedisKeys keys = new RedisKeys("down-test:");

    @BeforeAll
    static void connectToNothing() {
        // 1 号端口没人监听：连接立刻被拒（Windows 上也只要一两秒以内）
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1),
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(300))
                        .build());
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    private static StateFailures allow() {
        return StateFailures.of(StateProperties.OnError.ALLOW);
    }

    private static StateFailures reject() {
        return StateFailures.of(StateProperties.OnError.REJECT);
    }

    private static double errors(StateFailures failures, String component) {
        Counter counter = failures.registry()
                .find("assistant.state.errors")
                .tag("component", component)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void allowPolicyLetsRateLimitAndBudgetThrough() {
        StateFailures failures = allow();

        boolean allowed = new RedisRateLimiter(
                        redis, keys.of("rate", "chat"), "rate:chat", () -> 1, Duration.ofMinutes(1), clock, failures)
                .tryAcquire("user:1");
        long used = new RedisUsageStore(redis, keys, failures).tokens(LocalDate.of(2026, 11, 5), "user:1");

        assertThat(allowed).isTrue();
        assertThat(used).isZero();
        assertThat(errors(failures, "rate:chat")).isEqualTo(1);
        assertThat(errors(failures, "usage")).isEqualTo(1);
    }

    @Test
    void rejectPolicyRefusesRateLimitAndBudget() {
        StateFailures failures = reject();
        RedisRateLimiter limiter = new RedisRateLimiter(
                redis, keys.of("rate", "chat"), "rate:chat", () -> 1, Duration.ofMinutes(1), clock, failures);

        assertThatThrownBy(() -> limiter.tryAcquire("user:1"))
                .isInstanceOfSatisfying(
                        StateUnavailableException.class,
                        e -> assertThat(e.component()).isEqualTo("rate:chat"));
        assertThatThrownBy(() -> new RedisUsageStore(redis, keys, failures).tokens(LocalDate.of(2026, 11, 5), "user:1"))
                .isInstanceOf(StateUnavailableException.class);
    }

    @Test
    void slotsFallBackToTheLocalSemaphoreWhenAllowed() throws Exception {
        RedisConcurrencyLimiter limiter = slots(allow(), 1);

        ConcurrencyLimiter.Slot first = limiter.tryAcquire(Duration.ofMillis(50));
        assertThat(first).as("退回本地信号量，拿得到").isNotNull();
        assertThat(limiter.tryAcquire(Duration.ofMillis(50)))
                .as("本地信号量也有上限，不是不限并发")
                .isNull();
        first.release();
    }

    @Test
    void slotsAreRefusedWhenRejecting() {
        RedisConcurrencyLimiter limiter = slots(reject(), 1);

        assertThatThrownBy(() -> limiter.tryAcquire(Duration.ofMillis(50)))
                .isInstanceOf(StateUnavailableException.class);
    }

    @Test
    void conversationLockIsSkippedWhenAllowedAndRefusedWhenRejecting() {
        ConversationLocks allowing = new RedisConversationLocks(redis, keys, Duration.ofMinutes(5), allow());
        ConversationLocks rejecting = new RedisConversationLocks(redis, keys, Duration.ofMinutes(5), reject());

        String token = allowing.tryLock("conv");
        assertThat(token).isEqualTo(ConversationLocks.SKIPPED);
        allowing.unlock("conv", token); // 什么都不做，也不抛
        assertThatThrownBy(() -> rejecting.tryLock("conv")).isInstanceOf(StateUnavailableException.class);
    }

    /** 写操作没得选：不管什么策略，存不进、读不到都抛。 */
    @Test
    void confirmationsAlwaysFailClosed() {
        RedisConfirmationStore store =
                new RedisConfirmationStore(redis, keys, JsonMapper.builder().build(), allow());

        assertThatThrownBy(() -> store.save(confirmation(), Duration.ofMinutes(65)))
                .isInstanceOf(StateUnavailableException.class);
        assertThatThrownBy(() -> store.find("any")).isInstanceOf(StateUnavailableException.class);
    }

    /** 记账出错只记指标，不影响这一轮；归属缓存当作没缓存。 */
    @Test
    void ledgerAndOwnerCacheJustCarryOn() {
        StateFailures failures = reject();
        RedisUsageStore usage = new RedisUsageStore(redis, keys, failures);
        RedisOwnerCache cache = new RedisOwnerCache(redis, keys, failures);

        assertThat(usage.add(
                        LocalDate.of(2026, 11, 5),
                        new UsageStore.Entry("user:1", "POLICY", null, new UsageLedger.Tally(1, 10, 5, 0))))
                .isZero();
        cache.put("fp", ConversationOwner.user("10001"), Duration.ofMinutes(5));
        assertThat(cache.get("fp")).isEmpty();
        assertThat(errors(failures, "usage")).isEqualTo(1);
        assertThat(errors(failures, "owner-cache")).isEqualTo(2);
    }

    /** 对话接口的门：拒绝策略下三关任何一关碰到 Redis 出错，都是 503 STATE_UNAVAILABLE。 */
    @Test
    void chatGuardTurnsRejectionIntoStateUnavailable() {
        StateFailures failures = reject();
        UsageLedger ledger =
                new UsageLedger(UsageProperties.of(1000), clock, new RedisUsageStore(redis, keys, failures));
        ChatGuard guard = new ChatGuard(
                GuardProperties.defaults(),
                ledger,
                ChatMetrics.noop(),
                (name, limit, window) -> new RedisRateLimiter(
                        redis, keys.of("rate", name), "rate:" + name, limit, window, clock, failures),
                slots(failures, 8));

        assertThatThrownBy(() -> guard.admit("user:1")).isInstanceOfSatisfying(ChatRejection.class, e -> {
            assertThat(e.reason()).isEqualTo(ChatRejection.Reason.STATE_UNAVAILABLE);
            assertThat(e.reason().status().value()).isEqualTo(503);
            assertThat(e.retryAfter()).isPositive();
        });
    }

    /** 放行策略下同一个门全部放行（名额退回本地）。 */
    @Test
    void chatGuardLetsEveryoneInWhenAllowing() {
        StateFailures failures = allow();
        UsageLedger ledger =
                new UsageLedger(UsageProperties.of(1000), clock, new RedisUsageStore(redis, keys, failures));
        ChatGuard guard = new ChatGuard(
                GuardProperties.defaults(),
                ledger,
                ChatMetrics.noop(),
                (name, limit, window) -> new RedisRateLimiter(
                        redis, keys.of("rate", name), "rate:" + name, limit, window, clock, failures),
                slots(failures, 8));

        for (int i = 0; i < 30; i++) {
            guard.admit("user:1").close(); // 每分钟 20 次的上限也不起作用了：这就是「放行」的代价
        }
        assertThat(guard.inflight()).isZero();
    }

    /** 冷却：出错一次之后，冷却期内直接按出错处理，不再等连接超时；也只打一行日志。 */
    @Test
    void cooldownSkipsRedisAfterAnError() {
        StateFailures failures =
                new StateFailures(StateProperties.OnError.ALLOW, Duration.ofSeconds(30), new SimpleMeterRegistry());
        RedisRateLimiter limiter = new RedisRateLimiter(
                redis, keys.of("rate", "chat"), "rate:chat", () -> 1, Duration.ofMinutes(1), clock, failures);
        limiter.tryAcquire("user:1"); // 真的去连，失败，开始冷却

        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.tryAcquire("user:1")).isTrue();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("冷却期里不碰 Redis，20 次加起来也很快").isLessThan(200);
        assertThat(errors(failures, "rate:chat")).isEqualTo(21);
        assertThatThrownBy(failures::ensureAvailable).isInstanceOf(StateFailures.CoolingDown.class);
    }

    @Test
    void allowOrThrowFollowsThePolicy() {
        RuntimeException boom = new QueryTimeoutException("Redis 超时");

        assertThat(allow().allowOrThrow("usage", boom)).isTrue();
        assertThatThrownBy(() -> reject().allowOrThrow("usage", boom))
                .isInstanceOfSatisfying(StateUnavailableException.class, e -> {
                    assertThat(e.component()).isEqualTo("usage");
                    assertThat(e.getCause()).isSameAs(boom);
                });
        StateFailures counted = allow();
        counted.allowOrThrow("rate:mcp", boom);
        assertThat(errors(counted, "rate:mcp")).isEqualTo(1);
    }

    private RedisConcurrencyLimiter slots(StateFailures failures, int max) {
        return new RedisConcurrencyLimiter(
                redis,
                keys.of("slots", "chat"),
                () -> max,
                Duration.ofMinutes(5),
                Duration.ofMillis(20),
                clock,
                failures,
                new SemaphoreConcurrencyLimiter(max));
    }

    private static StoredConfirmation confirmation() {
        PendingAction action = new PendingAction(AgentTool.CANCEL_ORDER, "211800000001", Map.of(), "取消订单", "关闭");
        Instant expiresAt = Instant.parse("2026-11-05T10:05:00Z");
        PendingConfirmation view = new PendingConfirmation(
                "c-1", "cancelOrder", "取消订单", "211800000001", Map.of(), "取消订单", "关闭", expiresAt, 300);
        return new StoredConfirmation(
                "c-1", action, "user:10001", null, expiresAt, view, StoredConfirmation.Origin.MCP, null);
    }
}
