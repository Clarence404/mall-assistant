package io.github.clarence404.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.aftersales.AfterSalesApplication;
import io.github.clarence404.assistant.agent.AgentTool;
import io.github.clarence404.assistant.agent.ConfirmationException;
import io.github.clarence404.assistant.agent.ConfirmationOutcome;
import io.github.clarence404.assistant.mall.StubMallApi;
import io.github.clarence404.assistant.memory.ConversationOwner;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 第 8 章（ch08-4、ch08-5、ch08-6）：MCP 工具背后的业务。
 *
 * <ul>
 *   <li>查订单 / 列订单：手机号打码、能提出的操作写成 MCP 工具名、别人的订单查不到、订单号不对不发请求
 *   <li>查知识库：资料片段带来源文件、资料只是数据的说明、空问题
 *   <li>提出写操作：<b>只登记、不执行</b>（商城一个写请求都没收到）；服务端自己查订单（别人的、状态不允许的都拦下）；原因里没有只对自家 Agent 有意义的
 *       「finish」；用户在页面上点确认（{@code ConfirmationService#decide}，会话 ID 为空）之后才执行
 * </ul>
 */
class McpMallServiceTest {

    private static final StubMallApi MALL = StubMallApi.start();
    private static final McpCaller DEMO = new McpCaller(ConversationOwner.user("10001"), StubMallApi.TOKEN);
    private static final McpCaller NEW_USER =
            new McpCaller(ConversationOwner.user("10002"), StubMallApi.NEW_USER_TOKEN);
    private static final String PENDING = StubMallApi.PENDING_PAYMENT_ORDER_NO;
    private static final String SHIPPED = StubMallApi.SHIPPED_ORDER_NO;
    private static final String TO_SHIP = StubMallApi.ORDER_NO;

    private McpFixtures.Mcp mcp;

    @AfterAll
    static void stop() {
        MALL.close();
    }

    @BeforeEach
    void setUp() {
        MALL.reset();
        // 确认、售后申请单、次数窗口在每个测试里重新来过
        mcp = McpFixtures.mcp(MALL);
    }

    // ---------- ch08-4：查订单、列订单 ----------

    @Test
    void queryOrderMasksPhoneAndListsProposals() {
        McpViews.Order order = mcp.service().queryOrder(DEMO, PENDING);

        assertThat(order.found()).isTrue();
        assertThat(order.statusCode()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.status()).startsWith("待支付");
        assertThat(order.receiver()).isEqualTo("张三 138****0000 杭州市西湖区");
        assertThat(order.allowedProposals()).containsExactly("proposeCancelOrder", "proposeChangeAddress");
        assertThat(order.note()).contains("allowedProposals", "确认");
        assertThat(MALL.requests().getFirst().authorization()).isEqualTo("Bearer " + StubMallApi.TOKEN);
    }

    @Test
    void shippedOrderCanOnlyProposeAfterSales() {
        McpViews.Order order = mcp.service().queryOrder(DEMO, SHIPPED);

        assertThat(order.allowedProposals()).containsExactly("proposeAfterSales");
    }

    @Test
    void othersOrderIsNotFound() {
        McpViews.Order order = mcp.service().queryOrder(DEMO, StubMallApi.OTHER_USERS_ORDER_NO);

        assertThat(order.found()).isFalse();
        assertThat(order.reason()).contains("不是当前令牌这个账号的订单");
    }

    @Test
    void badOrderNoDoesNotCallMall() {
        McpViews.Order order = mcp.service().queryOrder(DEMO, "12345");

        assertThat(order.found()).isFalse();
        assertThat(order.reason()).isEqualTo(McpMallService.BAD_ORDER_NO);
        assertThat(MALL.requests()).isEmpty();
    }

    @Test
    void orderNoWithSpacesIsAccepted() {
        McpViews.Order order = mcp.service().queryOrder(DEMO, " 2118 2516707889029130785 ");

        assertThat(order.found()).isTrue();
        assertThat(order.orderNo()).isEqualTo(PENDING);
    }

    @Test
    void listMyOrders() {
        McpViews.OrderList list = mcp.service().listMyOrders(DEMO);

        assertThat(list.found()).isTrue();
        assertThat(list.orders())
                .extracting(McpViews.OrderList.Brief::orderNo)
                .containsExactly(TO_SHIP, StubMallApi.COMPLETED_ORDER_NO);
        assertThat(list.orders().getFirst().allowedProposals())
                .containsExactly("proposeChangeAddress", "proposeAfterSales");
        assertThat(MALL.requests().getFirst().query()).isEqualTo("size=" + McpMallService.LIST_SIZE);
    }

    @Test
    void newUserHasNoOrders() {
        McpViews.OrderList list = mcp.service().listMyOrders(NEW_USER);

        assertThat(list.found()).isTrue();
        assertThat(list.orders()).isEmpty();
        assertThat(list.note()).contains("还没有订单");
    }

    @Test
    void mallDownIsAReasonNotAnException() {
        MALL.failWith(503);

        assertThat(mcp.service().queryOrder(DEMO, PENDING).reason()).contains("暂时不可用");
        assertThat(mcp.service().listMyOrders(DEMO).reason()).contains("暂时不可用");
    }

    // ---------- ch08-5：查知识库 ----------

    @Test
    void searchKnowledgeReturnsSnippetsWithSources() {
        McpViews.Knowledge knowledge = mcp.service().searchKnowledge("七天无理由退货要什么条件");

        assertThat(knowledge.found()).isTrue();
        assertThat(knowledge.snippets()).isNotEmpty();
        McpViews.Knowledge.Snippet first = knowledge.snippets().getFirst();
        assertThat(first.index()).isEqualTo(1);
        assertThat(first.source()).isEqualTo("returns.md");
        assertThat(first.headingPath()).contains("退换货");
        assertThat(first.text()).doesNotStartWith(first.headingPath()).hasSizeLessThanOrEqualTo(301);
        assertThat(knowledge.note()).isEqualTo(McpMallService.KNOWLEDGE_NOTE);
    }

    @Test
    void blankQueryIsExplained() {
        McpViews.Knowledge knowledge = mcp.service().searchKnowledge("  ");

        assertThat(knowledge.found()).isFalse();
        assertThat(knowledge.reason()).contains("query 不能为空");
    }

    @Test
    void nothingFoundSaysDoNotMakeUpRules() {
        McpViews.Knowledge knowledge = mcp.service().searchKnowledge("火星殖民计划的发射窗口");

        assertThat(knowledge.found()).isFalse();
        assertThat(knowledge.reason()).contains("不要凭常识编规则");
    }

    // ---------- ch08-6：提出写操作 ----------

    @Test
    void proposeCancelOnlyRegistersAndPageButtonExecutes() {
        McpViews.Proposal proposal = mcp.service().propose(DEMO, AgentTool.CANCEL_ORDER, PENDING, null);

        assertThat(proposal.proposed()).isTrue();
        assertThat(proposal.executed()).isFalse();
        assertThat(proposal.confirmationId()).matches("[0-9a-f-]{36}");
        assertThat(proposal.confirmUrl()).isEqualTo("http://localhost:8088/?confirm=" + proposal.confirmationId());
        assertThat(proposal.summary()).startsWith("取消订单 " + PENDING);
        assertThat(proposal.note()).contains("不能代替用户确认");
        // 只提议：商城只收到一次查订单，没有写请求
        assertThat(MALL.writes()).isEmpty();
        assertThat(MALL.orderStatus(PENDING)).isEqualTo("PENDING_PAYMENT");

        // 用户在页面上点确认（MCP 的确认没有会话，会话 ID 为空）
        ConfirmationOutcome outcome = mcp.confirmations()
                .decide(proposal.confirmationId(), ConfirmationOutcome.Decision.APPROVE, null, StubMallApi.TOKEN);

        assertThat(outcome.status()).isEqualTo(ConfirmationOutcome.Status.EXECUTED);
        assertThat(MALL.orderStatus(PENDING)).isEqualTo("CANCELLED");
    }

    @Test
    void serverQueriesTheOrderItselfSoOthersOrderIsRejected() {
        McpViews.Proposal proposal =
                mcp.service().propose(DEMO, AgentTool.CANCEL_ORDER, StubMallApi.OTHER_USERS_ORDER_NO, null);

        assertThat(proposal.proposed()).isFalse();
        assertThat(proposal.reason()).contains("没有找到订单");
        assertThat(proposal.confirmationId()).isNull();
    }

    @Test
    void statusNotAllowedIsRejectedInMcpWords() {
        McpViews.Proposal proposal = mcp.service().propose(DEMO, AgentTool.CANCEL_ORDER, SHIPPED, null);

        assertThat(proposal.proposed()).isFalse();
        assertThat(proposal.reason())
                .contains("不能取消订单", "proposeAfterSales")
                .doesNotContain("finish", "applyAfterSales）");
    }

    @Test
    void changeAddressPutsTheExactAddressOnTheCard() {
        McpViews.Proposal proposal = mcp.service().propose(DEMO, AgentTool.CHANGE_ADDRESS, PENDING, "杭州市西湖区文三路 100 号");

        assertThat(proposal.proposed()).isTrue();
        assertThat(proposal.summary()).isEqualTo("把订单 " + PENDING + " 的收货地址改为：杭州市西湖区文三路 100 号");
        assertThat(MALL.writes()).isEmpty();
    }

    @Test
    void changeAddressNeedsAddressAndAReasonableLength() {
        McpViews.Proposal missing = mcp.service().propose(DEMO, AgentTool.CHANGE_ADDRESS, PENDING, " ");
        McpViews.Proposal tooShort = mcp.service().propose(DEMO, AgentTool.CHANGE_ADDRESS, PENDING, "同上");

        assertThat(missing.proposed()).isFalse();
        assertThat(missing.reason()).contains("需要 address");
        assertThat(tooShort.proposed()).isFalse();
        assertThat(tooShort.reason()).contains("太短").doesNotContain("finish");
    }

    @Test
    void afterSalesTypeIsDecidedByStatusAndNotDuplicated() {
        McpViews.Proposal first = mcp.service().propose(DEMO, AgentTool.APPLY_AFTER_SALES, TO_SHIP, "不想要了");
        mcp.confirmations()
                .decide(first.confirmationId(), ConfirmationOutcome.Decision.APPROVE, null, StubMallApi.TOKEN);
        McpViews.Proposal again = mcp.service().propose(DEMO, AgentTool.APPLY_AFTER_SALES, TO_SHIP, "还是不想要");

        assertThat(first.summary()).contains("仅退款", "不想要了");
        assertThat(mcp.afterSales().findOpen("user:10001", TO_SHIP))
                .get()
                .extracting(AfterSalesApplication::type)
                .isEqualTo(AfterSalesApplication.Type.REFUND_ONLY);
        assertThat(again.proposed()).isFalse();
        assertThat(again.reason()).contains("已经有待审核的售后申请").doesNotContain("finish");
    }

    @Test
    void afterSalesNeedsReasonAndOrderNoFormat() {
        assertThat(mcp.service()
                        .propose(DEMO, AgentTool.APPLY_AFTER_SALES, TO_SHIP, null)
                        .reason())
                .contains("需要 reason");
        assertThat(mcp.service()
                        .propose(DEMO, AgentTool.CANCEL_ORDER, "abc", null)
                        .reason())
                .contains("19 位或 23 位");
        assertThat(MALL.requests()).isEmpty();
    }

    @Test
    void getConfirmationFollowsTheButton() {
        McpViews.Proposal proposal = mcp.service().propose(DEMO, AgentTool.CANCEL_ORDER, PENDING, null);
        String id = proposal.confirmationId();

        assertThat(mcp.service().confirmation(DEMO, id).state()).isEqualTo("PENDING");
        mcp.confirmations().decide(id, ConfirmationOutcome.Decision.REJECT, null, StubMallApi.TOKEN);
        McpViews.ConfirmationState rejected = mcp.service().confirmation(DEMO, id);

        assertThat(rejected.state()).isEqualTo("REJECTED");
        assertThat(rejected.message()).contains("没有执行");
        assertThat(MALL.orderStatus(PENDING)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void getConfirmationOfSomeoneElseOrGarbageIsRejected() {
        String id = mcp.service()
                .propose(DEMO, AgentTool.CANCEL_ORDER, PENDING, null)
                .confirmationId();

        assertThatThrownBy(() -> mcp.service().confirmation(NEW_USER, id))
                .isInstanceOf(McpRejection.class)
                .hasMessageContaining("找不到");
        assertThatThrownBy(() -> mcp.service().confirmation(DEMO, "not-a-uuid"))
                .isInstanceOf(McpRejection.class)
                .hasMessageContaining("格式不对");
    }

    @Test
    void expiredProposalCannotBeApproved() {
        String id = mcp.service()
                .propose(DEMO, AgentTool.CANCEL_ORDER, PENDING, null)
                .confirmationId();
        mcp.memory().clock().advance(Duration.ofMinutes(6));

        assertThat(mcp.service().confirmation(DEMO, id).state()).isEqualTo("EXPIRED");
        assertThatThrownBy(() ->
                        mcp.confirmations().decide(id, ConfirmationOutcome.Decision.APPROVE, null, StubMallApi.TOKEN))
                .isInstanceOf(ConfirmationException.class)
                .extracting(e -> ((ConfirmationException) e).failure())
                .isEqualTo(ConfirmationException.Failure.CONFIRMATION_EXPIRED);
        assertThat(MALL.writes()).isEmpty();
    }

    @Test
    void forMcpRewordsAgentHints() {
        assertThat(McpMallService.forMcp("新地址「同上」太短，不像一个完整的地址：用 finish 问用户要完整的新地址"))
                .isEqualTo("新地址「同上」太短，不像一个完整的地址");
        assertThat(McpMallService.forMcp("这笔订单可以办：取消订单（cancelOrder）、修改收货地址（changeAddress）"))
                .isEqualTo("这笔订单可以办：取消订单（proposeCancelOrder）、修改收货地址（proposeChangeAddress）");
        assertThat(McpMallService.forMcp(null)).isNull();
    }

    @Test
    void proposalsUseMcpToolNames() {
        assertThat(McpMallService.proposals("PENDING_PAYMENT"))
                .isEqualTo(List.of("proposeCancelOrder", "proposeChangeAddress"));
        assertThat(McpMallService.proposals("CANCELLED")).isEmpty();
    }
}
