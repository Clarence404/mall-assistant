package io.github.clarence404.assistant.agent;

import static io.github.clarence404.assistant.agent.AgentFixtures.act;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.aftersales.AfterSalesApplication;
import io.github.clarence404.assistant.chat.FakeChatModel;
import io.github.clarence404.assistant.mall.StubMallApi;
import io.github.clarence404.assistant.memory.MemoryFixtures;
import io.github.clarence404.assistant.memory.MemoryMessages;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 第 7 章 TODO ch07-5 / ch07-6：人工确认——用户点了「确认」才执行，而且只执行一次。
 *
 * <p>手法：先用剧本让 Agent 提出一个写操作（拿到确认 ID），再像页面点按钮一样调 {@link ConfirmationService#decide}，看商城收到了什么、订单变成了什么、
 * 售后申请单有没有、记忆里写了什么。时钟可以拨动（{@link MemoryFixtures.MutableClock}）测过期；别的用户、别的会话、匿名都拿不到这个确认。
 */
class ConfirmationServiceTest {

    private static final StubMallApi MALL = StubMallApi.start();
    private static final String TOKEN = StubMallApi.TOKEN;
    private static final String PENDING = StubMallApi.PENDING_PAYMENT_ORDER_NO;
    private static final String CONVERSATION = MemoryFixtures.CONVERSATION;
    private static final ConfirmationOutcome.Decision APPROVE = ConfirmationOutcome.Decision.APPROVE;
    private static final ConfirmationOutcome.Decision REJECT = ConfirmationOutcome.Decision.REJECT;

    private FakeChatModel model;
    private AgentFixtures.Agent agent;

    @BeforeEach
    void setUp() {
        MALL.reset();
        model = new FakeChatModel();
        agent = AgentFixtures.agent(model, MALL);
    }

    @AfterAll
    static void stopMall() {
        MALL.close();
    }

    /** 让 Agent 按剧本提出一个写操作，返回待确认操作。 */
    private PendingConfirmation propose(String... script) {
        model.willAct(script);
        AgentResult result = agent.run("帮我办一下，新地址是杭州市西湖区文三路 100 号", TOKEN, CONVERSATION, new ArrayList<>());
        assertThat(result.confirmation()).as("剧本应当以提出写操作结束").isNotNull();
        return result.confirmation();
    }

    private PendingConfirmation proposeCancel() {
        return propose(act("queryOrder", "orderNo", PENDING), act("cancelOrder", "orderNo", PENDING));
    }

    private ConfirmationOutcome decide(String id, ConfirmationOutcome.Decision decision) {
        return agent.confirmations().decide(id, decision, CONVERSATION, TOKEN);
    }

    private static long cancelRequests() {
        return MALL.writes().stream().filter(r -> r.path().endsWith("/cancel")).count();
    }

    /** 点确认：用户自己的令牌调商城取消，订单变成已取消，结果是代码写的固定话术。 */
    @Test
    void approveExecutesTheAction() {
        PendingConfirmation pending = proposeCancel();

        ConfirmationOutcome outcome = decide(pending.id(), APPROVE);

        assertThat(outcome.status()).isEqualTo(ConfirmationOutcome.Status.EXECUTED);
        assertThat(outcome.message()).contains("已取消订单 " + PENDING, "已取消");
        assertThat(MALL.orderStatus(PENDING)).isEqualTo("CANCELLED");
        assertThat(MALL.writes()).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("POST");
            assertThat(r.authorization()).isEqualTo("Bearer " + TOKEN);
        });
    }

    /** 点取消：什么都不执行。 */
    @Test
    void rejectDoesNothing() {
        PendingConfirmation pending = proposeCancel();

        ConfirmationOutcome outcome = decide(pending.id(), REJECT);

        assertThat(outcome.status()).isEqualTo(ConfirmationOutcome.Status.REJECTED);
        assertThat(outcome.message()).contains("没有执行");
        assertThat(MALL.writes()).isEmpty();
        assertThat(MALL.orderStatus(PENDING)).isEqualTo("PENDING_PAYMENT");
    }

    /** 过期（默认 5 分钟）：410，不执行；再点还是 410。 */
    @Test
    void expiredConfirmationIsNotExecuted() {
        PendingConfirmation pending = proposeCancel();
        agent.memory().clock().advance(Duration.ofMinutes(5));

        assertThatThrownBy(() -> decide(pending.id(), APPROVE))
                .isInstanceOfSatisfying(
                        ConfirmationException.class,
                        e -> assertThat(e.failure()).isEqualTo(ConfirmationException.Failure.CONFIRMATION_EXPIRED));
        assertThatThrownBy(() -> decide(pending.id(), APPROVE)).isInstanceOf(ConfirmationException.class);
        assertThat(MALL.writes()).isEmpty();
    }

    /** 幂等：同一个确认点两次「确认」，第二次返回第一次的结果（同一个对象），商城只收到一次取消。 */
    @Test
    void repeatedApprovalReturnsSameOutcome() {
        PendingConfirmation pending = proposeCancel();

        ConfirmationOutcome first = decide(pending.id(), APPROVE);
        ConfirmationOutcome second = decide(pending.id(), APPROVE);

        assertThat(second).isSameAs(first);
        assertThat(cancelRequests()).isEqualTo(1);
    }

    /** 并发：两个请求同时点确认，也只执行一次，两个都拿到同一个结果。 */
    @Test
    void concurrentApprovalsExecuteOnce() throws Exception {
        PendingConfirmation pending = proposeCancel();
        MALL.delay(Duration.ofMillis(200));
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<ConfirmationOutcome>> clicks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            clicks.add(CompletableFuture.supplyAsync(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return decide(pending.id(), APPROVE);
            }));
        }
        go.countDown();

        ConfirmationOutcome a = clicks.get(0).get();
        ConfirmationOutcome b = clicks.get(1).get();

        assertThat(a).isSameAs(b);
        assertThat(cancelRequests()).isEqualTo(1);
    }

    /** 点过确认再点取消（或反过来）：409，结果不变。 */
    @Test
    void conflictingDecisionIsRejected() {
        PendingConfirmation pending = proposeCancel();
        decide(pending.id(), APPROVE);

        assertThatThrownBy(() -> decide(pending.id(), REJECT))
                .isInstanceOfSatisfying(
                        ConfirmationException.class,
                        e -> assertThat(e.failure())
                                .isEqualTo(ConfirmationException.Failure.CONFIRMATION_ALREADY_DECIDED));
    }

    /** 别人的确认 ID：别的用户、匿名、别的会话、不存在的 ID 都是 404，什么都不执行。 */
    @Test
    void othersCannotConfirm() {
        PendingConfirmation pending = proposeCancel();
        ConfirmationService confirmations = agent.confirmations();

        for (Runnable attempt : List.<Runnable>of(
                () -> confirmations.decide(pending.id(), APPROVE, CONVERSATION, StubMallApi.NEW_USER_TOKEN),
                () -> confirmations.decide(pending.id(), APPROVE, CONVERSATION, null),
                () -> confirmations.decide(pending.id(), APPROVE, MemoryFixtures.OTHER_CONVERSATION, TOKEN),
                () -> confirmations.decide(pending.id(), APPROVE, null, TOKEN),
                () -> confirmations.decide("00000000-0000-4000-8000-000000000000", APPROVE, CONVERSATION, TOKEN))) {
            assertThatThrownBy(attempt::run)
                    .isInstanceOfSatisfying(
                            ConfirmationException.class,
                            e -> assertThat(e.failure())
                                    .isEqualTo(ConfirmationException.Failure.CONFIRMATION_NOT_FOUND));
        }
        assertThat(MALL.writes()).isEmpty();
    }

    /** 参数在生成时就定死了：待确认操作里的参数改不了（「请求里塞别的参数也没用」见 AgentApiTest）。 */
    @Test
    void paramsAreImmutable() {
        PendingConfirmation pending = propose(
                act("queryOrder", "orderNo", PENDING),
                act("changeAddress", "orderNo", PENDING, "address", "杭州市西湖区文三路 100 号"));

        assertThatThrownBy(() -> pending.params().put("address", "别的地址"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** 改地址：只传要改的项，商城那边改掉；结果里写着新地址。 */
    @Test
    void changeAddressSendsOnlyChangedFields() {
        model.willAct(
                act("queryOrder", "orderNo", PENDING),
                act("changeAddress", "orderNo", PENDING, "address", "杭州市西湖区文三路 100 号"));
        AgentResult result = agent.service()
                .run(
                        "订单 " + PENDING + " 地址填错了，改成杭州市西湖区文三路 100 号",
                        List.of(),
                        TOKEN,
                        CONVERSATION,
                        e -> {},
                        () -> false);

        ConfirmationOutcome outcome = decide(result.confirmation().id(), APPROVE);

        assertThat(outcome.status()).isEqualTo(ConfirmationOutcome.Status.EXECUTED);
        assertThat(MALL.orderAddress(PENDING)).isEqualTo("杭州市西湖区文三路 100 号");
        assertThat(MALL.writes()).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("PATCH");
            assertThat(r.body()).contains("\"detail\":\"杭州市西湖区文三路 100 号\"").doesNotContain("receiver", "phone");
            assertThat(r.body()).isEqualTo("{\"detail\":\"杭州市西湖区文三路 100 号\"}");
        });
    }

    /** 售后：执行前再查一次订单，记一张待人工审核的申请单；再点一次不会多出一张。 */
    @Test
    void afterSalesCreatesOneApplication() {
        String shipped = StubMallApi.SHIPPED_ORDER_NO;
        PendingConfirmation pending = propose(
                act("queryOrder", "orderNo", shipped), act("applyAfterSales", "orderNo", shipped, "reason", "东西坏了"));

        ConfirmationOutcome outcome = decide(pending.id(), APPROVE);
        decide(pending.id(), APPROVE);

        assertThat(outcome.status()).isEqualTo(ConfirmationOutcome.Status.EXECUTED);
        assertThat(outcome.afterSalesId()).startsWith("AS");
        assertThat(outcome.message()).contains(outcome.afterSalesId(), "退货退款", "待人工审核");
        assertThat(agent.afterSales().findByOwner("user:10001")).singleElement().satisfies(a -> {
            assertThat(a.orderNo()).isEqualTo(shipped);
            assertThat(a.type()).isEqualTo(AfterSalesApplication.Type.RETURN_REFUND);
            assertThat(a.status()).isEqualTo(AfterSalesApplication.Status.PENDING_REVIEW);
            assertThat(a.reason()).isEqualTo("东西坏了");
            assertThat(a.confirmationId()).isEqualTo(pending.id());
        });
        assertThat(MALL.writes()).as("售后申请不调商城的写接口").isEmpty();
    }

    /** 商城拒绝（409）：结果 FAILED，照抄商城的原因；也是最终结果，再点返回同一个。 */
    @Test
    void mallConflictIsReportedAsFailure() {
        PendingConfirmation first = proposeCancel();
        PendingConfirmation second = proposeCancel();
        decide(first.id(), APPROVE);

        ConfirmationOutcome outcome = decide(second.id(), APPROVE);

        assertThat(outcome.status()).isEqualTo(ConfirmationOutcome.Status.FAILED);
        assertThat(outcome.message()).contains("商城没有执行", "已取消，不能取消");
        assertThat(decide(second.id(), APPROVE)).isSameAs(outcome);
    }

    /** 商城不可用：FAILED，提醒去「我的订单」看一下（超时的那次可能已经执行了），不重试。 */
    @Test
    void mallUnavailableIsReportedAsFailure() {
        PendingConfirmation pending = proposeCancel();
        MALL.failWith(503);

        ConfirmationOutcome outcome = decide(pending.id(), APPROVE);

        assertThat(outcome.status()).isEqualTo(ConfirmationOutcome.Status.FAILED);
        assertThat(outcome.message()).contains("暂时不可用", "我的订单");
        assertThat(cancelRequests()).isEqualTo(1);
    }

    /** 确认之后结果写回会话记忆：一轮「（点了确认）说明」→「结果」，下一轮 Agent 就知道刚才办过什么。 */
    @Test
    void outcomeIsWrittenBackToMemory() {
        PendingConfirmation pending = proposeCancel();

        ConfirmationOutcome outcome = decide(pending.id(), APPROVE);

        assertThat(agent.memory().stored(AgentFixtures.DEMO_USER, CONVERSATION))
                .extracting(MemoryMessages::text)
                .containsExactly("（点了确认）" + pending.summary(), outcome.message());
    }

    /** 还没处理的待确认操作能查到（聊天里说「确认」时提醒去点按钮用），处理过、过期的不算。 */
    @Test
    void pendingForListsOnlyUndecided() {
        PendingConfirmation pending = proposeCancel();
        ConfirmationService confirmations = agent.confirmations();

        assertThat(confirmations.pendingFor(AgentFixtures.DEMO_USER, CONVERSATION))
                .hasValueSatisfying(p -> assertThat(p.id()).isEqualTo(pending.id()));
        assertThat(confirmations.pendingFor(AgentFixtures.DEMO_USER, null)).isEmpty();

        decide(pending.id(), REJECT);
        assertThat(confirmations.pendingFor(AgentFixtures.DEMO_USER, CONVERSATION))
                .isEmpty();
    }
}
