package io.github.clarence404.assistant.agent;

import static io.github.clarence404.assistant.agent.AgentFixtures.act;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.chat.FakeChatModel;
import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.mall.StubMallApi;
import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.ResourceAccessException;

/**
 * 第 7 章 TODO ch07-2（及 ch07-3、ch07-4 在循环里的效果）：方案 B 的 Agent 循环——想 → 选工具 → 执行 → 看结果，以及每一道刹车。
 *
 * <p>手法：{@link FakeChatModel#willAct} 预设模型每一步的原样输出（一个「剧本」），跑真实的循环、真实的工具代码，商城是 {@link StubMallApi}。
 * 断言走了哪几步、每步是什么类型、为什么停、商城收到了什么请求——尤其是：<b>写操作在循环里从来不执行</b>（商城收不到 cancel / address 请求）。
 */
class AgentLoopTest {

    private static final StubMallApi MALL = StubMallApi.start();
    private static final String TOKEN = StubMallApi.TOKEN;
    private static final String PENDING = StubMallApi.PENDING_PAYMENT_ORDER_NO;
    private static final String SHIPPED = StubMallApi.SHIPPED_ORDER_NO;

    private FakeChatModel model;
    private AgentFixtures.Agent agent;
    private List<AgentEvent> events;

    @BeforeEach
    void setUp() {
        MALL.reset();
        model = new FakeChatModel();
        agent = AgentFixtures.agent(model, MALL);
        events = new ArrayList<>();
    }

    @AfterAll
    static void stopMall() {
        MALL.close();
    }

    private AgentResult run(String message) {
        return agent.run(message, TOKEN, null, events);
    }

    private static List<AgentStep.Kind> kinds(AgentResult result) {
        return result.trace().steps().stream().map(AgentStep::kind).toList();
    }

    /** 典型的一次：查订单（只读，直接执行）→ 提出取消（写操作，只生成待确认）→ 停下等确认。商城没收到取消请求。 */
    @Test
    void readThenProposeStopsForConfirmation() {
        model.willAct(act("queryOrder", "orderNo", PENDING), act("cancelOrder", "orderNo", PENDING));

        AgentResult result = run("订单 " + PENDING + " 买错了，帮我取消");

        assertThat(kinds(result)).containsExactly(AgentStep.Kind.READ, AgentStep.Kind.PROPOSE);
        assertThat(result.trace().stopReason()).isEqualTo(AgentTrace.StopReason.AWAITING_CONFIRMATION);
        assertThat(result.confirmation().action()).isEqualTo("cancelOrder");
        assertThat(result.confirmation().orderNo()).isEqualTo(PENDING);
        assertThat(result.reply()).contains("点「确认」后才会办理").contains(PENDING);
        assertThat(MALL.writes()).as("写操作在循环里不执行").isEmpty();
        assertThat(MALL.orderStatus(PENDING)).isEqualTo("PENDING_PAYMENT");
        assertThat(events)
                .extracting(e -> e.getClass().getSimpleName())
                .containsExactly("StepTaken", "StepTaken", "ConfirmationRequested");
    }

    /** 第二步的提示词里有第一步的办事记录：动作、参数、查询结果（含代码算好的 allowedActions）；令牌从不出现在任何一步里。 */
    @Test
    void observationsAreFedBackButTokenIsNot() {
        model.willAct(act("queryOrder", "orderNo", PENDING), act("finish", "answer", "这笔可以取消。"));

        run("订单 " + PENDING + " 能取消吗");

        List<Prompt> prompts = model.agentPrompts();
        assertThat(prompts).hasSize(2);
        assertThat(prompts.getFirst().getUserMessage().getText()).contains(AgentPlanner.EMPTY_RECORD, "第 1 步，最多 6 步");
        String second = prompts.get(1).getUserMessage().getText();
        assertThat(second)
                .contains("第 1 步：queryOrder {\"orderNo\":\"" + PENDING + "\"}")
                .contains("\"statusCode\":\"PENDING_PAYMENT\"")
                .contains("\"allowedActions\":[\"cancelOrder\",\"changeAddress\"]")
                .contains("138****0000")
                .contains("第 2 步");
        for (Prompt prompt : model.prompts()) {
            assertThat(FakeChatModel.allText(prompt)).doesNotContain(TOKEN);
        }
    }

    /** finish：回答就是模型给的 answer。 */
    @Test
    void finishEndsWithModelAnswer() {
        model.willAct(act("finish", "answer", "请问是哪一笔订单？"));

        AgentResult result = run("我要退款");

        assertThat(kinds(result)).containsExactly(AgentStep.Kind.FINISH);
        assertThat(result.trace().stopReason()).isEqualTo(AgentTrace.StopReason.FINISHED);
        assertThat(result.reply()).isEqualTo("请问是哪一笔订单？");
        assertThat(result.confirmation()).isNull();
    }

    /** 模型自称「已为您取消」：这一轮根本没执行任何写操作，换成固定话术。 */
    @Test
    void falseClaimOfExecutionIsReplaced() {
        model.willAct(act("finish", "answer", "已为您取消订单，请放心。"));

        AgentResult result = run("帮我取消订单");

        assertThat(result.reply()).isEqualTo(AgentReplies.NOTHING_EXECUTED);
    }

    /** 步数上限：6 个不同的只读调用之后还没 finish，停下，原因 STEP_LIMIT；模型只被问了 6 次。 */
    @Test
    void stopsAtStepLimit() {
        model.willAct(
                act("listMyOrders"),
                act("queryOrder", "orderNo", StubMallApi.ORDER_NO),
                act("queryOrder", "orderNo", StubMallApi.COMPLETED_ORDER_NO),
                act("queryPayment", "orderNo", StubMallApi.ORDER_NO),
                act("queryPayment", "orderNo", StubMallApi.COMPLETED_ORDER_NO),
                act("searchPolicy", "query", "退款多久到账"),
                act("finish", "answer", "不该走到这里"));

        AgentResult result = run("帮我看看能退哪一单");

        assertThat(result.trace().steps()).hasSize(6).allMatch(s -> s.kind() == AgentStep.Kind.READ);
        assertThat(result.trace().stopReason()).isEqualTo(AgentTrace.StopReason.STEP_LIMIT);
        assertThat(result.reply()).isEqualTo(AgentReplies.stepLimit(6));
        assertThat(model.agentPrompts()).hasSize(6);
    }

    /**
     * 同一个调用的次数上限：一直查同一笔订单——前 2 次执行，第 3 次起不执行、提醒它别重复，被拦下 2 次就停（REPEATED）。商城只收到 2 次查询。
     */
    @Test
    void repeatedCallsAreStopped() {
        model.willAct(act("queryOrder", "orderNo", PENDING));

        AgentResult result = run("订单 " + PENDING + " 怎么样了");

        assertThat(kinds(result))
                .containsExactly(
                        AgentStep.Kind.READ, AgentStep.Kind.READ, AgentStep.Kind.REJECTED, AgentStep.Kind.REJECTED);
        assertThat(result.trace().steps().get(2).observation()).contains("不要再重复");
        assertThat(result.trace().stopReason()).isEqualTo(AgentTrace.StopReason.REPEATED);
        assertThat(MALL.requests().stream()
                        .filter(r -> r.path().equals("/api/orders/" + PENDING))
                        .count())
                .isEqualTo(2);
    }

    /** 输出看不懂（重试后还是看不懂）、工具不存在：记一步 REJECTED，原因交还给模型，下一步改对了照常办。 */
    @Test
    void invalidStepsAreReportedBackToModel() {
        model.willAct("我觉得应该先查一下", "还是不是 JSON", act("refund", "orderNo", PENDING), act("finish", "answer", "好的。"));

        AgentResult result = run("我要退款");

        assertThat(kinds(result))
                .containsExactly(AgentStep.Kind.REJECTED, AgentStep.Kind.REJECTED, AgentStep.Kind.FINISH);
        assertThat(result.trace().steps().getFirst().observation()).contains("看不懂");
        assertThat(result.trace().steps().get(1).observation()).contains("refund", "applyAfterSales");
        assertThat(model.agentPrompts().getLast().getUserMessage().getText()).contains("没有叫「refund」的工具");
    }

    /** 没查过就提写操作：拦下，提醒先查；查了之后再提，生成待确认。 */
    @Test
    void writeNeedsTheOrderToBeQueriedFirst() {
        model.willAct(
                act("cancelOrder", "orderNo", PENDING),
                act("queryOrder", "orderNo", PENDING),
                act("cancelOrder", "orderNo", PENDING));

        AgentResult result = run("取消订单 " + PENDING);

        assertThat(kinds(result)).containsExactly(AgentStep.Kind.REJECTED, AgentStep.Kind.READ, AgentStep.Kind.PROPOSE);
        assertThat(result.trace().steps().getFirst().observation()).contains("还没有在这次的查询结果里出现过");
        assertThat(MALL.writes()).isEmpty();
    }

    /** 已发货的订单想取消：按状态拦下，告诉模型这笔只能提交售后申请；它改提售后后生成待确认（退货退款）。 */
    @Test
    void shippedOrderCanOnlyApplyAfterSales() {
        model.willAct(
                act("queryOrder", "orderNo", SHIPPED),
                act("cancelOrder", "orderNo", SHIPPED),
                act("applyAfterSales", "orderNo", SHIPPED, "reason", "东西坏了"));

        AgentResult result = run("订单 " + SHIPPED + " 东西坏了要退款");

        assertThat(kinds(result)).containsExactly(AgentStep.Kind.READ, AgentStep.Kind.REJECTED, AgentStep.Kind.PROPOSE);
        assertThat(result.trace().steps().get(1).observation()).contains("已发货", "不能取消订单", "提交售后申请");
        assertThat(result.confirmation().action()).isEqualTo("applyAfterSales");
        assertThat(result.confirmation().params()).containsEntry("type", "RETURN_REFUND");
        assertThat(agent.afterSales().findByOwner("user:10001")).as("提议不等于提交").isEmpty();
    }

    /** 超过总时长：下一步开始前停下（TIMEOUT）。时钟是可以拨动的（{@code MemoryFixtures.MutableClock}）：模型每想一步，就让它「花掉」70 秒。 */
    @Test
    void stopsWhenTimeIsUp() {
        model.willAct(act("queryOrder", "orderNo", PENDING), act("finish", "answer", "不该走到这里"));
        AgentResult result = agent.service()
                .run(
                        "订单 " + PENDING + " 怎么样了",
                        List.of(),
                        TOKEN,
                        null,
                        e -> agent.memory().clock().advance(Duration.ofSeconds(70)),
                        () -> false);

        assertThat(kinds(result)).containsExactly(AgentStep.Kind.READ, AgentStep.Kind.FINISH);
        assertThat(result.trace().stopReason()).isEqualTo(AgentTrace.StopReason.FINISHED);

        model.willAct(
                act("queryOrder", "orderNo", PENDING),
                act("queryPayment", "orderNo", PENDING),
                act("finish", "answer", "不该走到这里"));
        AgentResult slow = agent.service()
                .run(
                        "订单 " + PENDING + " 怎么样了",
                        List.of(),
                        TOKEN,
                        null,
                        e -> agent.memory().clock().advance(Duration.ofSeconds(70)),
                        () -> false);

        assertThat(kinds(slow))
                .as("两步共 140 秒，超过 120 秒，第三步开始前停下")
                .containsExactly(AgentStep.Kind.READ, AgentStep.Kind.READ);
        assertThat(slow.trace().stopReason()).isEqualTo(AgentTrace.StopReason.TIMEOUT);
        assertThat(slow.reply()).isEqualTo(AgentReplies.timeout(120));
    }

    /** 页面关了：一步都不走。 */
    @Test
    void stopsWhenCancelled() {
        AgentResult result = agent.service().run("帮我取消订单", List.of(), TOKEN, null, events::add, () -> true);

        assertThat(result.trace().stopReason()).isEqualTo(AgentTrace.StopReason.CANCELLED);
        assertThat(model.agentPrompts()).isEmpty();
    }

    /** 某一步模型挂了：整次办事失败（ModelCallException），什么都没执行。 */
    @Test
    void modelFailureFailsTheWholeRun() {
        model.willFailAct(new ResourceAccessException("模型连不上", new ConnectException("Connection refused")));

        assertThatThrownBy(() -> run("帮我取消订单")).isInstanceOf(ModelCallException.class);
        assertThat(MALL.writes()).isEmpty();
    }

    /** 令牌无效（商城说 401）：确认不了是谁在办，不办，回固定话术。 */
    @Test
    void invalidTokenIsNotServed() {
        AgentResult result = agent.run("帮我取消订单", "forged-token", null, events);

        assertThat(result.trace().stopReason()).isEqualTo(AgentTrace.StopReason.LOGIN_EXPIRED);
        assertThat(result.reply()).isEqualTo(AgentReplies.loginExpired());
        assertThat(model.agentPrompts()).isEmpty();
    }
}
