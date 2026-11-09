package io.github.clarence404.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.aftersales.AfterSalesApplication;
import io.github.clarence404.assistant.aftersales.InMemoryAfterSalesStore;
import io.github.clarence404.assistant.memory.ConversationOwner;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 第 7 章 TODO ch07-4：写操作的提议关——模型提出取消、改地址、提交售后时，代码逐项核对，通过了才生成待确认操作。
 *
 * <p>思路：模型给的订单号、状态判断、新地址都可能不对（抄错、编造、被资料带偏、自作主张「润色」），所以每一项都由代码核对：订单必须在这次的查询结果里出现过、
 * 状态必须允许、新地址必须是用户自己说过的、售后不重复提交。纯逻辑，不需要模型和商城：手工造一个 {@link AgentRun}，往里「记」查到的订单。
 */
class WriteActionGuardTest {

    private static final String NO = "21182516707889029130785";
    private static final String USER_MESSAGE = "订单 " + NO + " 地址填错了，改成 杭州市西湖区文三路 100 号";

    private InMemoryAfterSalesStore afterSales;
    private WriteActionGuard guard;

    @BeforeEach
    void setUp() {
        afterSales = new InMemoryAfterSalesStore();
        guard = new WriteActionGuard(afterSales);
    }

    private static AgentRun run(String message, List<Message> history, ConversationOwner owner, String token) {
        return new AgentRun(new AgentTask(message, history, token, owner, null), e -> {});
    }

    private static AgentRun loggedIn(String message) {
        return run(message, List.of(), AgentFixtures.DEMO_USER, "token");
    }

    private static void observe(AgentRun run, String status) {
        run.observe(new AgentRun.ObservedOrder(NO, status, status + "（说明）", "青柚 · 红心柚 2 只装 ×1", "39.90"));
    }

    private static AgentAction action(String tool, String input) {
        return AgentAction.parse(new AgentDecision(null, tool, NO, input)).action();
    }

    /** 全部通过：生成待确认操作，说明里有订单的商品和金额，影响说清楚「不能恢复」。 */
    @Test
    void validCancelBecomesPendingAction() {
        AgentRun run = loggedIn("订单 " + NO + " 买错了帮我取消");
        observe(run, "PENDING_PAYMENT");

        WriteActionGuard.Proposal proposal = guard.check(run, action("cancelOrder", null));

        assertThat(proposal.accepted()).isTrue();
        assertThat(proposal.action().tool()).isEqualTo(AgentTool.CANCEL_ORDER);
        assertThat(proposal.action().summary()).contains(NO, "青柚 · 红心柚 2 只装 ×1", "39.90 元");
        assertThat(proposal.action().impact()).contains("不能恢复");
    }

    /** 没登录：办不了。 */
    @Test
    void anonymousIsRejected() {
        AgentRun run = run("取消订单 " + NO, List.of(), ConversationOwner.ANONYMOUS, null);
        observe(run, "PENDING_PAYMENT");

        assertThat(guard.check(run, action("cancelOrder", null)).rejection()).contains("登录");
    }

    /** 订单号没在这次的查询结果里出现过（模型抄错、编造、或被资料带偏）：先去查。 */
    @Test
    void unobservedOrderIsRejected() {
        AgentRun run = loggedIn("取消订单 " + NO);

        assertThat(guard.check(run, action("cancelOrder", null)).rejection()).contains("queryOrder");
    }

    /** 状态不允许：待发货不能取消，原因里写当前状态和这笔能办什么。 */
    @Test
    void statusMustAllowTheAction() {
        AgentRun run = loggedIn("取消订单 " + NO);
        observe(run, "PENDING_SHIPMENT");

        String rejection = guard.check(run, action("cancelOrder", null)).rejection();

        assertThat(rejection).contains("PENDING_SHIPMENT（说明）", "不能取消订单", "修改收货地址", "提交售后申请");
    }

    /** 一轮只提一个：已经有待确认操作了，再提就拦下。 */
    @Test
    void onlyOneProposalPerRun() {
        AgentRun run = loggedIn("取消订单 " + NO);
        observe(run, "PENDING_PAYMENT");
        run.propose(new PendingConfirmation("id", "cancelOrder", "取消订单", NO, Map.of(), "取消", "影响", Instant.now(), 300));

        assertThat(guard.check(run, action("cancelOrder", null)).rejection()).contains("已经提出了一个待确认操作");
    }

    /** 改地址：照抄用户原话的通过（忽略空格差别），参数只有新地址。 */
    @Test
    void addressCopiedFromUserPasses() {
        AgentRun run = loggedIn(USER_MESSAGE);
        observe(run, "PENDING_SHIPMENT");

        WriteActionGuard.Proposal proposal = guard.check(run, action("changeAddress", "杭州市西湖区文三路100号"));

        assertThat(proposal.accepted()).isTrue();
        assertThat(proposal.action().params()).containsExactly(Map.entry("address", "杭州市西湖区文三路100号"));
        assertThat(proposal.action().summary()).contains("收货地址改为：杭州市西湖区文三路100号");
    }

    /** 改地址：模型「润色」过的地址（补了省份）、编的地址，用户没说过，拦下；太短的不像地址，也拦下。 */
    @Test
    void addressNotSaidByUserIsRejected() {
        AgentRun run = loggedIn(USER_MESSAGE);
        observe(run, "PENDING_PAYMENT");

        assertThat(guard.check(run, action("changeAddress", "浙江省杭州市西湖区文三路 100 号"))
                        .rejection())
                .contains("用户没有说过");
        assertThat(guard.check(run, action("changeAddress", "上海市徐汇区漕溪北路 1 号")).rejection())
                .contains("用户没有说过");
        assertThat(guard.check(run, action("changeAddress", "文三路")).rejection()).contains("太短");
    }

    /** 改地址：用户在上一轮说过的也算（历史里用户的话），助手说过的不算。 */
    @Test
    void addressFromEarlierUserTurnCounts() {
        List<Message> history =
                List.of(new UserMessage("新地址是 上海市徐汇区漕溪北路 1 号"), new AssistantMessage("要不要改成 北京市朝阳区建国路 1 号"));
        AgentRun run = run("就改订单 " + NO + " 的", history, AgentFixtures.DEMO_USER, "token");
        observe(run, "PENDING_PAYMENT");

        assertThat(guard.check(run, action("changeAddress", "上海市徐汇区漕溪北路 1 号")).accepted())
                .isTrue();
        assertThat(guard.check(run, action("changeAddress", "北京市朝阳区建国路 1 号")).accepted())
                .as("助手自己说过的话不能证明「不是模型编的」")
                .isFalse();
    }

    /** 已发货不能改地址：按状态拦下。 */
    @Test
    void shippedOrderAddressCannotChange() {
        AgentRun run = loggedIn(USER_MESSAGE);
        observe(run, "SHIPPED");

        assertThat(guard.check(run, action("changeAddress", "杭州市西湖区文三路 100 号")).rejection())
                .contains("不能修改收货地址", "提交售后申请");
    }

    /** 售后：类型由代码按状态定（待发货 → 仅退款），同一笔已经有待审核的申请就不重复提交。 */
    @Test
    void afterSalesTypeAndDuplicates() {
        AgentRun run = loggedIn("订单 " + NO + " 不想要了要退款");
        observe(run, "PENDING_SHIPMENT");

        WriteActionGuard.Proposal first = guard.check(run, action("applyAfterSales", "不想要了"));
        assertThat(first.action().params()).containsEntry("type", "REFUND_ONLY").containsEntry("reason", "不想要了");
        assertThat(first.action().summary()).contains("仅退款申请", "不想要了");

        afterSales.create(new AfterSalesApplication(
                "AS1",
                "user:10001",
                NO,
                AfterSalesApplication.Type.REFUND_ONLY,
                "不想要了",
                AfterSalesApplication.Status.PENDING_REVIEW,
                "c1",
                Instant.now()));
        assertThat(guard.check(run, action("applyAfterSales", "不想要了")).rejection())
                .contains("AS1");
    }
}
