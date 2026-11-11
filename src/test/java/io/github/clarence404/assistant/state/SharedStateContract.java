package io.github.clarence404.assistant.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.agent.AgentTool;
import io.github.clarence404.assistant.agent.ConfirmationOutcome;
import io.github.clarence404.assistant.agent.ConfirmationStore;
import io.github.clarence404.assistant.agent.PendingAction;
import io.github.clarence404.assistant.agent.PendingConfirmation;
import io.github.clarence404.assistant.agent.StoredConfirmation;
import io.github.clarence404.assistant.guard.ConcurrencyLimiter;
import io.github.clarence404.assistant.guard.RateLimiter;
import io.github.clarence404.assistant.memory.ConversationLocks;
import io.github.clarence404.assistant.memory.ConversationOwner;
import io.github.clarence404.assistant.memory.OwnerCache;
import io.github.clarence404.assistant.ops.ManualClock;
import io.github.clarence404.assistant.ops.UsageLedger;
import io.github.clarence404.assistant.ops.UsageStore;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 第 10 章：共享状态的契约测试——内存实现和 Redis 实现跑<b>同一套</b>测试（{@link MemoryStateContractTest}、{@link RedisStateContractIT}）。
 *
 * <p>为什么这么写：抽接口的承诺是「换实现，行为不变」。同一套测试两种实现都过，才说明业务类（ChatGuard、ConfirmationService……）
 * 换到 Redis 上不会出现「本地好好的、上了多实例就不对」的差别。子类只负责造出被测对象；每个测试用随机的 key，Redis 版跑在共享的库里也互不影响。
 *
 * <p>只有内存版做不到的（租期到了自动收回、键会过期、两个客户端之间共享）写在 {@link RedisStateContractIT} 和 {@link RedisMultiInstanceIT} 里。
 */
abstract class SharedStateContract {

    protected ManualClock clock;

    /** 每个测试一个随机后缀：Redis 版的键互不干扰。 */
    protected String run;

    /** 每个测试记账用的「那一天」也各不相同：Token 账按天分键，同一天的合计会把别的测试也加进去。 */
    protected LocalDate day;

    @BeforeEach
    void freshRun() {
        clock = ManualClock.standard();
        run = UUID.randomUUID().toString().substring(0, 8);
        day = LocalDate.of(2000, 1, 1).plusDays(Math.floorMod(run.hashCode(), 30000));
    }

    protected abstract RateLimiter rateLimiter(String name, int limit, Duration window);

    protected abstract ConcurrencyLimiter slots(String name, int max);

    protected abstract UsageStore usageStore();

    protected abstract ConfirmationStore confirmationStore();

    protected abstract ConversationLocks locks();

    protected abstract OwnerCache ownerCache();

    // ———— 限流：滑动窗口 ————

    @Test
    void rateLimiterAllowsUpToTheLimitThenRejects() {
        RateLimiter limiter = rateLimiter("chat-" + run, 3, Duration.ofMinutes(1));

        assertThat(limiter.tryAcquire("user:1")).isTrue();
        clock.advance(Duration.ofSeconds(10));
        assertThat(limiter.tryAcquire("user:1")).isTrue();
        assertThat(limiter.tryAcquire("user:1")).isTrue();
        assertThat(limiter.tryAcquire("user:1")).isFalse();

        // 窗口里最早那次在 50 秒后滑出，向上取整再加 1 秒
        assertThat(limiter.retryAfter("user:1")).isBetween(Duration.ofSeconds(50), Duration.ofSeconds(51));
    }

    @Test
    void rateLimiterWindowSlides() {
        RateLimiter limiter = rateLimiter("chat-" + run, 2, Duration.ofMinutes(1));
        limiter.tryAcquire("user:1");
        clock.advance(Duration.ofSeconds(30));
        limiter.tryAcquire("user:1");
        assertThat(limiter.tryAcquire("user:1")).isFalse();

        clock.advance(Duration.ofSeconds(30)); // 第一次正好滑出窗口（等于「现在 - 窗口」也算窗口外）
        assertThat(limiter.tryAcquire("user:1")).isTrue();
        assertThat(limiter.tryAcquire("user:1")).isFalse();
    }

    @Test
    void rateLimiterKeysAndNamesAreIndependent() {
        RateLimiter chat = rateLimiter("chat-" + run, 1, Duration.ofMinutes(1));
        RateLimiter mcp = rateLimiter("mcp-" + run, 1, Duration.ofMinutes(1));

        assertThat(chat.tryAcquire("user:1")).isTrue();
        assertThat(chat.tryAcquire("user:2")).isTrue();
        assertThat(mcp.tryAcquire("user:1")).isTrue();
        assertThat(chat.tryAcquire("user:1")).isFalse();
        assertThat(chat.retryAfter("user:never")).isZero();
    }

    @Test
    void rejectedCallsDoNotTakeASlot() {
        RateLimiter limiter = rateLimiter("chat-" + run, 1, Duration.ofMinutes(1));
        limiter.tryAcquire("user:1");
        clock.advance(Duration.ofSeconds(40));
        assertThat(limiter.tryAcquire("user:1")).isFalse(); // 被拒的这次不记

        clock.advance(Duration.ofSeconds(20)); // 第一次滑出；如果被拒的那次也记了，这里还会被拒
        assertThat(limiter.tryAcquire("user:1")).isTrue();
    }

    @Test
    void concurrentCallersNeverExceedTheLimit() throws Exception {
        RateLimiter limiter = rateLimiter("chat-" + run, 5, Duration.ofMinutes(1));

        List<Boolean> results = concurrently(20, () -> limiter.tryAcquire("user:1"));

        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(5);
    }

    // ———— 并发名额 ————

    @Test
    void slotsAreLimitedAndReleased() throws Exception {
        ConcurrencyLimiter limiter = slots("slots-" + run, 2);

        ConcurrencyLimiter.Slot a = limiter.tryAcquire(Duration.ofMillis(50));
        ConcurrencyLimiter.Slot b = limiter.tryAcquire(Duration.ofMillis(50));
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        assertThat(limiter.tryAcquire(Duration.ofMillis(100))).isNull();

        a.release();
        assertThat(limiter.tryAcquire(Duration.ofMillis(50))).isNotNull();
    }

    @Test
    void waitingCallerGetsAReleasedSlot() throws Exception {
        ConcurrencyLimiter limiter = slots("slots-" + run, 1);
        ConcurrencyLimiter.Slot held = limiter.tryAcquire(Duration.ofMillis(50));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ConcurrencyLimiter.Slot> waiting = pool.submit(() -> limiter.tryAcquire(Duration.ofSeconds(3)));
            Thread.sleep(150);
            held.release();
            assertThat(waiting.get()).isNotNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentAcquirersNeverExceedTheMax() throws Exception {
        ConcurrencyLimiter limiter = slots("slots-" + run, 3);

        List<ConcurrencyLimiter.Slot> got = concurrently(12, () -> limiter.tryAcquire(Duration.ofMillis(100)));

        assertThat(got).filteredOn(s -> s != null).hasSize(3);
    }

    // ———— Token 总账 ————

    @Test
    void usageStoreAggregatesByCallerIntentAndConversation() {
        UsageStore store = usageStore();
        String alice = "user:a-" + run;
        String bob = "ip:b-" + run;

        assertThat(store.add(day, entry(alice, "POLICY", alice + "/c1", 300, 50, false)))
                .isEqualTo(350);
        assertThat(store.add(day, entry(alice, "POLICY", alice + "/c1", 200, 40, true)))
                .isEqualTo(590);
        store.add(day, entry(bob, "CHITCHAT", null, 100, 10, false));

        assertThat(store.tokens(day, alice)).isEqualTo(590);
        assertThat(store.tokens(day, bob)).isEqualTo(110);
        assertThat(store.tokens(day, "user:nobody-" + run)).isZero();

        UsageStore.Totals totals = store.summary(day, 10);
        assertThat(totals.total()).isEqualTo(new UsageLedger.Tally(3, 600, 100, 1));
        assertThat(totals.byIntent()).extracting(UsageLedger.Row::key).containsExactly("POLICY", "CHITCHAT");
        assertThat(totals.topCallers().getFirst())
                .isEqualTo(new UsageLedger.Row(alice, new UsageLedger.Tally(2, 500, 90, 1)));
        assertThat(totals.topConversations()).singleElement().satisfies(r -> {
            assertThat(r.key()).isEqualTo(alice + "/c1");
            assertThat(r.usage().turns()).isEqualTo(2);
        });
    }

    @Test
    void usageStoreKeepsDaysApart() {
        UsageStore store = usageStore();
        String alice = "user:a-" + run;
        LocalDate today = day;

        store.add(today, entry(alice, "POLICY", null, 300, 50, false));

        assertThat(store.tokens(today.plusDays(1), alice)).isZero();
        assertThat(store.summary(today.plusDays(1), 10).total()).isEqualTo(UsageLedger.Tally.ZERO);
    }

    @Test
    void concurrentAddsAreNotLost() throws Exception {
        UsageStore store = usageStore();
        String alice = "user:a-" + run;

        concurrently(20, () -> store.add(day, entry(alice, "POLICY", null, 10, 5, false)));

        assertThat(store.tokens(day, alice)).isEqualTo(300);
        assertThat(store.summary(day, 100).topCallers())
                .filteredOn(r -> r.key().equals(alice))
                .singleElement()
                .satisfies(r -> assertThat(r.usage().turns()).isEqualTo(20));
    }

    // ———— 待确认操作 ————

    @Test
    void confirmationRoundTrips() {
        ConfirmationStore store = confirmationStore();
        StoredConfirmation saved = confirmation("user:a-" + run, "c1", StoredConfirmation.Origin.CHAT);

        store.save(saved, Duration.ofMinutes(65));

        assertThat(store.find(saved.id())).contains(saved);
        assertThat(store.find("no-such-" + run)).isEmpty();
    }

    @Test
    void onlyTheFirstClaimWins() {
        ConfirmationStore store = confirmationStore();
        StoredConfirmation saved = confirmation("user:a-" + run, null, StoredConfirmation.Origin.MCP);
        store.save(saved, Duration.ofMinutes(65));

        assertThat(store.claim(saved.id(), ConfirmationOutcome.Decision.APPROVE, Duration.ofMinutes(65)))
                .isEmpty();
        assertThat(store.claim(saved.id(), ConfirmationOutcome.Decision.APPROVE, Duration.ofMinutes(65)))
                .contains(ConfirmationOutcome.Decision.APPROVE);
        assertThat(store.claim(saved.id(), ConfirmationOutcome.Decision.REJECT, Duration.ofMinutes(65)))
                .as("后来的看到的是先来的决定")
                .contains(ConfirmationOutcome.Decision.APPROVE);
    }

    @Test
    void concurrentClaimsHaveExactlyOneWinner() throws Exception {
        ConfirmationStore store = confirmationStore();
        StoredConfirmation saved = confirmation("user:a-" + run, null, StoredConfirmation.Origin.MCP);
        store.save(saved, Duration.ofMinutes(65));

        List<Optional<ConfirmationOutcome.Decision>> claims = concurrently(
                10, () -> store.claim(saved.id(), ConfirmationOutcome.Decision.APPROVE, Duration.ofMinutes(65)));

        assertThat(claims).filteredOn(Optional::isEmpty).hasSize(1);
    }

    @Test
    void completedOutcomeIsVisible() {
        ConfirmationStore store = confirmationStore();
        StoredConfirmation saved = confirmation("user:a-" + run, "c1", StoredConfirmation.Origin.CHAT);
        store.save(saved, Duration.ofMinutes(65));
        ConfirmationOutcome outcome = new ConfirmationOutcome(
                saved.id(),
                ConfirmationOutcome.Decision.APPROVE,
                ConfirmationOutcome.Status.EXECUTED,
                "已取消订单 211800000001。",
                null,
                Instant.parse("2026-11-05T10:01:00Z"));

        store.complete(saved.id(), outcome, Duration.ofMinutes(65));

        assertThat(store.find(saved.id()).map(StoredConfirmation::outcome)).contains(outcome);
    }

    @Test
    void findByOwnerListsOnlyThatOwner() {
        ConfirmationStore store = confirmationStore();
        String alice = "user:a-" + run;
        StoredConfirmation first = confirmation(alice, "c1", StoredConfirmation.Origin.CHAT);
        StoredConfirmation second = confirmation(alice, null, StoredConfirmation.Origin.MCP);
        StoredConfirmation others = confirmation("user:b-" + run, "c1", StoredConfirmation.Origin.CHAT);
        store.save(first, Duration.ofMinutes(65));
        store.save(second, Duration.ofMinutes(65));
        store.save(others, Duration.ofMinutes(65));

        assertThat(store.findByOwner(alice))
                .extracting(StoredConfirmation::id)
                .containsExactlyInAnyOrder(first.id(), second.id());
    }

    // ———— 「正在回答」标记 ————

    @Test
    void conversationLockIsExclusiveAndOnlyItsOwnerCanUnlock() {
        ConversationLocks locks = locks();
        String key = "conv-" + run;

        String token = locks.tryLock(key);
        assertThat(token).isNotNull();
        assertThat(locks.tryLock(key)).isNull();
        assertThat(locks.isLocked(key)).isTrue();

        locks.unlock(key, "someone-else");
        assertThat(locks.isLocked(key)).as("别人的令牌放不开").isTrue();

        locks.unlock(key, token);
        assertThat(locks.isLocked(key)).isFalse();
        assertThat(locks.tryLock(key)).isNotNull();
    }

    @Test
    void concurrentLockersHaveExactlyOneWinner() throws Exception {
        ConversationLocks locks = locks();
        String key = "conv-" + run;

        List<String> tokens = concurrently(10, () -> locks.tryLock(key));

        assertThat(tokens).filteredOn(t -> t != null).hasSize(1);
    }

    // ———— 归属缓存 ————

    @Test
    void ownerCacheRemembersUsers() {
        OwnerCache cache = ownerCache();
        String fingerprint = "fp-" + run;

        assertThat(cache.get(fingerprint)).isEmpty();
        cache.put(fingerprint, ConversationOwner.user("10001"), Duration.ofMinutes(5));

        assertThat(cache.get(fingerprint)).contains(ConversationOwner.user("10001"));
    }

    // ———— 工具 ————

    protected static UsageStore.Entry entry(
            String caller, String intent, String conversation, long input, long output, boolean estimated) {
        return new UsageStore.Entry(
                caller, intent, conversation, new UsageLedger.Tally(1, input, output, estimated ? 1 : 0));
    }

    protected StoredConfirmation confirmation(String owner, String conversationId, StoredConfirmation.Origin origin) {
        String id = UUID.randomUUID().toString();
        PendingAction action = new PendingAction(
                AgentTool.CHANGE_ADDRESS,
                "211800000001",
                Map.of("address", "杭州市西湖区文三路 100 号"),
                "把订单 211800000001 的收货地址改为杭州市西湖区文三路 100 号",
                "改好后按新地址发货");
        Instant expiresAt = clock.instant().plus(Duration.ofMinutes(5));
        PendingConfirmation view = new PendingConfirmation(
                id,
                action.tool().toolName(),
                action.tool().title(),
                action.orderNo(),
                action.params(),
                action.summary(),
                action.impact(),
                expiresAt,
                300);
        return new StoredConfirmation(id, action, owner, conversationId, expiresAt, view, origin, null);
    }

    /** n 个线程同时（等同一个发令枪）执行，收集结果。 */
    protected static <T> List<T> concurrently(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
