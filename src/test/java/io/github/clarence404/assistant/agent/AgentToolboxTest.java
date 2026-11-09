package io.github.clarence404.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.chat.ChatClientConfig;
import io.github.clarence404.assistant.chat.FakeChatModel;
import io.github.clarence404.assistant.mall.MallApiClient;
import io.github.clarence404.assistant.mall.MallApiProperties;
import io.github.clarence404.assistant.mall.StubMallApi;
import io.github.clarence404.assistant.memory.ConversationOwner;
import io.github.clarence404.assistant.rag.RagFixtures;
import io.github.clarence404.assistant.tool.MallTools;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 第 7 章 TODO ch07-3：执行只读工具，把结果写成交还给模型的「观察」，并记下查到的订单。
 *
 * <p>思路：只读工具查的都是用户自己的东西、不改数据，所以直接执行；结果要「直接可用」：中文状态、代码算好的 allowedActions、打了码的手机号，
 * 查不到也是结构化的 found=false + 原因。商城是 {@link StubMallApi}，知识库是假向量模型 + 6 篇真实规则文档。
 */
class AgentToolboxTest {

    private static StubMallApi mall;
    private static AgentToolbox toolbox;

    @BeforeAll
    static void start() {
        mall = StubMallApi.start();
        MallApiClient mallApi =
                new MallApiClient(new MallApiProperties(mall.baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(2)));
        ChatClient chatClient = new ChatClientConfig().chatClient(ChatClient.builder(new FakeChatModel()));
        toolbox = new AgentToolbox(
                mallApi,
                new MallTools(mallApi),
                RagFixtures.service(chatClient, RagFixtures.fakeKnowledgeBase(), RagFixtures.properties()),
                AgentProperties.defaults(AgentProperties.Mode.LOOP));
    }

    @AfterAll
    static void stop() {
        mall.close();
    }

    @BeforeEach
    void reset() {
        mall.reset();
    }

    private static AgentRun run(String token) {
        ConversationOwner owner = token == null ? ConversationOwner.ANONYMOUS : AgentFixtures.DEMO_USER;
        return new AgentRun(new AgentTask("帮我看看", List.of(), token, owner, null), e -> {});
    }

    private static AgentAction action(String tool, String orderNo) {
        return AgentAction.parse(AgentDecision.of(null, tool, orderNo)).action();
    }

    /** 查订单：中文状态、状态码、allowedActions、打码的手机号；记进这次办事；结果里没有令牌。 */
    @Test
    void queryOrderObservesTheOrder() {
        AgentRun run = run(StubMallApi.TOKEN);
        String no = StubMallApi.PENDING_PAYMENT_ORDER_NO;

        String result = toolbox.read(run, action("queryOrder", no));

        assertThat(result)
                .contains("\"found\":true", "待支付", "\"statusCode\":\"PENDING_PAYMENT\"")
                .contains("\"allowedActions\":[\"cancelOrder\",\"changeAddress\"]")
                .contains("张三 138****0000 杭州市西湖区")
                .doesNotContain("13800000000")
                .doesNotContain(StubMallApi.TOKEN);
        assertThat(run.observed(no)).isNotNull().satisfies(o -> {
            assertThat(o.status()).isEqualTo("PENDING_PAYMENT");
            assertThat(o.items()).isEqualTo("青柚 · 红心柚 2 只装 ×1");
        });
    }

    /** 没登录：不发请求，直接说请先登录。 */
    @Test
    void notLoggedInSendsNoRequest() {
        String result = toolbox.read(run(null), action("queryOrder", StubMallApi.ORDER_NO));

        assertThat(result).contains("\"found\":false", "登录");
        assertThat(mall.requests()).isEmpty();
    }

    /** 别人的订单：商城 404，结果说找不到；不记进这次办事（写操作就提不了）。 */
    @Test
    void othersOrderIsNotObserved() {
        AgentRun run = run(StubMallApi.TOKEN);

        String result = toolbox.read(run, action("queryOrder", StubMallApi.OTHER_USERS_ORDER_NO));

        assertThat(result).contains("\"found\":false", "没有找到订单");
        assertThat(run.observed(StubMallApi.OTHER_USERS_ORDER_NO)).isNull();
    }

    /** 列订单：每笔都带状态码和 allowedActions，都记进这次办事。 */
    @Test
    void listMyOrdersObservesEveryOrder() {
        AgentRun run = run(StubMallApi.TOKEN);

        String result = toolbox.read(run, action("listMyOrders", null));

        assertThat(result).contains(StubMallApi.ORDER_NO, StubMallApi.COMPLETED_ORDER_NO, "\"allowedActions\"");
        assertThat(run.observed(StubMallApi.ORDER_NO).status()).isEqualTo("PENDING_SHIPMENT");
        assertThat(run.observed(StubMallApi.COMPLETED_ORDER_NO).status()).isEqualTo("COMPLETED");
    }

    /** 查支付单：状态翻成中文。 */
    @Test
    void queryPaymentTranslatesStatus() {
        AgentRun run = run(StubMallApi.TOKEN);

        assertThat(toolbox.read(run, action("queryPayment", StubMallApi.PENDING_PAYMENT_ORDER_NO)))
                .contains("待支付");
        assertThat(toolbox.read(run, action("queryPayment", StubMallApi.SHIPPED_ORDER_NO)))
                .contains("已支付");
    }

    /** 查商品：第 3 章的工具原样复用。 */
    @Test
    void queryProductReusesChapter3Tool() {
        AgentAction product = AgentAction.parse(new AgentDecision(null, "queryProduct", null, "20001"))
                .action();

        assertThat(toolbox.read(run(null), product)).contains("青柚 · 红心柚 2 只装", "39.90");
    }

    /** 查规则：第 5 章的检索，编号资料 + 「只是数据」的提醒；检索不到 found=false，不编。 */
    @Test
    void searchPolicyReturnsNumberedSnippets() {
        AgentAction found = AgentAction.parse(new AgentDecision(null, "searchPolicy", null, "质量问题怎么退货"))
                .action();
        AgentAction missing = AgentAction.parse(new AgentDecision(null, "searchPolicy", null, "火星探测器"))
                .action();

        assertThat(toolbox.read(run(null), found))
                .contains("\"found\":true", "\"index\":1", "退换货规则", AgentToolbox.POLICY_NOTE);
        assertThat(toolbox.read(run(null), missing)).contains("\"found\":false", "不要凭常识编规则");
    }

    /** 结果太长时截断。 */
    @Test
    void longObservationIsTruncated() {
        String limited = toolbox.limit("字".repeat(5000));

        assertThat(limited).hasSize(1200 + "…（结果太长，已截断）".length()).endsWith("已截断）");
    }
}
