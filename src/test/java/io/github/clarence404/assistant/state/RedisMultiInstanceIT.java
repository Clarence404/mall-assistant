package io.github.clarence404.assistant.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.aftersales.InMemoryAfterSalesStore;
import io.github.clarence404.assistant.agent.AgentProperties;
import io.github.clarence404.assistant.agent.AgentTool;
import io.github.clarence404.assistant.agent.ConfirmationException;
import io.github.clarence404.assistant.agent.ConfirmationOutcome;
import io.github.clarence404.assistant.agent.ConfirmationService;
import io.github.clarence404.assistant.agent.ConfirmationSnapshot;
import io.github.clarence404.assistant.agent.PendingAction;
import io.github.clarence404.assistant.agent.PendingConfirmation;
import io.github.clarence404.assistant.chat.ChatClientConfig;
import io.github.clarence404.assistant.chat.FakeChatModel;
import io.github.clarence404.assistant.guard.ChatGuard;
import io.github.clarence404.assistant.guard.ChatRejection;
import io.github.clarence404.assistant.guard.GuardProperties;
import io.github.clarence404.assistant.guard.SemaphoreConcurrencyLimiter;
import io.github.clarence404.assistant.mall.MallApiClient;
import io.github.clarence404.assistant.mall.MallApiProperties;
import io.github.clarence404.assistant.mall.StubMallApi;
import io.github.clarence404.assistant.memory.ConversationOwner;
import io.github.clarence404.assistant.memory.MemoryFixtures;
import io.github.clarence404.assistant.ops.ChatMetrics;
import io.github.clarence404.assistant.ops.ManualClock;
import io.github.clarence404.assistant.ops.ModelCall;
import io.github.clarence404.assistant.ops.TurnRecord;
import io.github.clarence404.assistant.ops.UsageLedger;
import io.github.clarence404.assistant.ops.UsageProperties;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 第 10 章：两个「实例」共用一台 Redis，共享真的生效了——这是本章要解决的问题本身。
 *
 * <p>手法：每个实例一套自己的对象（ChatGuard、UsageLedger、ConfirmationService……），各自<b>新建一条 Redis 连接</b>（{@link RedisTestServer#client()}），
 * 它们之间唯一共享的是 Redis。对照组是两个内存实例：同样的操作，限流和预算各算各的。
 *
 * <ul>
 *   <li>对话限流每分钟 3 次：A 问两次、B 问一次之后，B 再问就 429（内存版两边各能问 3 次，合计 6 次）
 *   <li>每日预算：A 上用掉的 Token，B 进门时看得到
 *   <li>并发名额：A、B 各拿一个，满了（上限 2）谁都拿不到
 *   <li>待确认操作：A 上生成的确认，A、B 同时点「确认」，商城只收到一次取消，两边拿到同一个结果；B 上查得到状态
 * </ul>
 */
class RedisMultiInstanceIT {

    static final StubMallApi MALL = StubMallApi.start();
    static RedisTestServer server;

    private ManualClock clock;
    private String caller;

    @BeforeAll
    static void connect() {
        server = RedisTestServer.connect();
    }

    @AfterAll
    static void cleanUp() {
        MALL.close();
        if (server != null) {
            server.close();
        }
    }

    @BeforeEach
    void setUp() {
        MALL.reset();
        clock = ManualClock.standard();
        caller = "ip:10.0.0." + UUID.randomUUID().toString().substring(0, 4);
    }

    /** 一个实例：自己的连接、自己的对象。 */
    private Instance instance(GuardProperties guard, UsageProperties usage) {
        StringRedisTemplate redis = server.client();
        StateFailures failures = StateFailures.of(StateProperties.OnError.REJECT);
        RedisKeys keys = server.keys();
        UsageLedger ledger = new UsageLedger(usage, clock, new RedisUsageStore(redis, keys, failures));
        ChatGuard chatGuard = new ChatGuard(
                guard,
                ledger,
                ChatMetrics.noop(),
                (name, limit, window) -> new RedisRateLimiter(
                        redis, keys.of("rate", name), "rate:" + name, limit, window, clock, failures),
                new RedisConcurrencyLimiter(
                        redis,
                        keys.of("slots", "chat"),
                        guard::maxConcurrent,
                        Duration.ofMinutes(5),
                        Duration.ofMillis(20),
                        clock,
                        failures,
                        new SemaphoreConcurrencyLimiter(guard.maxConcurrent())));
        ChatClient chatClient = new ChatClientConfig().chatClient(ChatClient.builder(new FakeChatModel()));
        MemoryFixtures.Memory memory = MemoryFixtures.memory(chatClient, MALL, MemoryFixtures.properties());
        ConfirmationService confirmations = new ConfirmationService(
                new MallApiClient(new MallApiProperties(MALL.baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(2))),
                new InMemoryAfterSalesStore(),
                memory.memory(),
                memory.owners(),
                AgentProperties.defaults(AgentProperties.Mode.LOOP),
                clock,
                new RedisConfirmationStore(redis, keys, JsonMapper.builder().build(), failures),
                Duration.ofSeconds(5));
        return new Instance(chatGuard, ledger, confirmations, memory);
    }

    private static GuardProperties guard(int perMinute, int maxConcurrent) {
        GuardProperties d = GuardProperties.defaults();
        return new GuardProperties(
                true, perMinute, maxConcurrent, Duration.ofMillis(200), d.circuit(), d.injection(), d.degrade());
    }

    @Test
    void twoInstancesShareTheChatRateLimit() {
        Instance a = instance(guard(3, 8), UsageProperties.of(0));
        Instance b = instance(guard(3, 8), UsageProperties.of(0));

        a.guard().admit(caller).close();
        a.guard().admit(caller).close();
        b.guard().admit(caller).close();

        assertThatThrownBy(() -> b.guard().admit(caller)).isInstanceOfSatisfying(ChatRejection.class, e -> {
            assertThat(e.reason()).isEqualTo(ChatRejection.Reason.RATE_LIMITED);
            assertThat(e.retryAfter()).isPositive();
        });
        assertThatThrownBy(() -> a.guard().admit(caller)).isInstanceOf(ChatRejection.class);
    }

    /** 对照组：两个内存实例，每个都能问 3 次，合计 6 次——这就是第 9 章「多实例时每分钟 20 次变成 60 次」。 */
    @Test
    void memoryInstancesDoNotShare() {
        ChatGuard a =
                new ChatGuard(guard(3, 8), new UsageLedger(UsageProperties.of(0), clock), ChatMetrics.noop(), clock);
        ChatGuard b =
                new ChatGuard(guard(3, 8), new UsageLedger(UsageProperties.of(0), clock), ChatMetrics.noop(), clock);

        for (int i = 0; i < 3; i++) {
            a.admit(caller).close();
            b.admit(caller).close();
        }

        assertThatThrownBy(() -> a.admit(caller)).isInstanceOf(ChatRejection.class);
    }

    @Test
    void twoInstancesShareTheDailyBudget() {
        Instance a = instance(guard(20, 8), UsageProperties.of(1000));
        Instance b = instance(guard(20, 8), UsageProperties.of(1000));
        TurnRecord turn = new TurnRecord(caller, null);
        turn.intent("POLICY");
        turn.add(new ModelCall("rag", null, 900, 150, false, 10));

        a.ledger().commit(turn);

        assertThat(b.ledger().usedToday(caller)).isEqualTo(1050);
        assertThatThrownBy(() -> b.guard().admit(caller))
                .isInstanceOfSatisfying(
                        ChatRejection.class,
                        e -> assertThat(e.reason()).isEqualTo(ChatRejection.Reason.TOKEN_BUDGET_EXCEEDED));
        assertThat(b.ledger().summary(10).topCallers())
                .singleElement()
                .satisfies(r -> assertThat(r.key()).isEqualTo(caller));
    }

    @Test
    void twoInstancesShareTheConcurrencySlots() {
        // 名额集合的键是 slots:chat，这个测试类独占前缀，所以不和别的测试抢
        Instance a = instance(guard(20, 2), UsageProperties.of(0));
        Instance b = instance(guard(20, 2), UsageProperties.of(0));

        ChatGuard.Permit first = a.guard().admit(caller + "-1");
        ChatGuard.Permit second = b.guard().admit(caller + "-2");

        assertThatThrownBy(() -> a.guard().admit(caller + "-3"))
                .isInstanceOfSatisfying(
                        ChatRejection.class, e -> assertThat(e.reason()).isEqualTo(ChatRejection.Reason.TOO_BUSY));
        first.close();
        b.guard().admit(caller + "-4").close();
        second.close();
    }

    /** A 上生成的确认，A、B 两个实例同时点「确认」：商城只收到一次取消，两边拿到同一个结果。 */
    @Test
    void confirmationFromInstanceAIsExecutedOnceWhenBothInstancesAreClicked() throws Exception {
        Instance a = instance(guard(20, 8), UsageProperties.of(0));
        Instance b = instance(guard(20, 8), UsageProperties.of(0));
        ConversationOwner owner = a.memory().owners().resolve(StubMallApi.TOKEN);
        PendingConfirmation pending = a.confirmations()
                .createForMcp(
                        new PendingAction(
                                AgentTool.CANCEL_ORDER,
                                StubMallApi.PENDING_PAYMENT_ORDER_NO,
                                Map.of(),
                                "取消订单 " + StubMallApi.PENDING_PAYMENT_ORDER_NO,
                                "取消后订单关闭，不能恢复"),
                        owner);
        assertThat(b.confirmations().snapshot(pending.id(), owner).state())
                .as("B 实例看得到 A 生成的确认")
                .isEqualTo(ConfirmationSnapshot.State.PENDING);
        MALL.delay(Duration.ofMillis(300)); // 让两次点击一定撞在一起
        CountDownLatch go = new CountDownLatch(1);
        CompletableFuture<ConfirmationOutcome> onA = CompletableFuture.supplyAsync(() -> click(go, a, pending.id()));
        CompletableFuture<ConfirmationOutcome> onB = CompletableFuture.supplyAsync(() -> click(go, b, pending.id()));
        go.countDown();

        ConfirmationOutcome outcome = onA.get();
        assertThat(onB.get()).as("后到的那个等先到的结果").isEqualTo(outcome);
        assertThat(outcome.status()).isEqualTo(ConfirmationOutcome.Status.EXECUTED);
        assertThat(MALL.writes()).filteredOn(r -> r.path().endsWith("/cancel")).hasSize(1);
        // 之后在哪个实例上重复点都是同一个结果；改主意（点取消）是 409
        assertThat(b.confirmations()
                        .decide(pending.id(), ConfirmationOutcome.Decision.APPROVE, null, StubMallApi.TOKEN))
                .isEqualTo(outcome);
        assertThat(b.confirmations().snapshot(pending.id(), owner).state())
                .isEqualTo(ConfirmationSnapshot.State.EXECUTED);
        assertThatThrownBy(() -> a.confirmations()
                        .decide(pending.id(), ConfirmationOutcome.Decision.REJECT, null, StubMallApi.TOKEN))
                .isInstanceOfSatisfying(
                        ConfirmationException.class,
                        e -> assertThat(e.failure())
                                .isEqualTo(ConfirmationException.Failure.CONFIRMATION_ALREADY_DECIDED));
        assertThat(MALL.writes()).filteredOn(r -> r.path().endsWith("/cancel")).hasSize(1);
    }

    private static ConfirmationOutcome click(CountDownLatch go, Instance at, String id) {
        try {
            go.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return at.confirmations().decide(id, ConfirmationOutcome.Decision.APPROVE, null, StubMallApi.TOKEN);
    }

    /** 一个实例里本章关心的几样东西。 */
    private record Instance(
            ChatGuard guard, UsageLedger ledger, ConfirmationService confirmations, MemoryFixtures.Memory memory) {}
}
