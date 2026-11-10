package io.github.clarence404.assistant.mcp;

import static io.github.clarence404.assistant.mcp.McpFixtures.as;
import static io.github.clarence404.assistant.mcp.McpFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.clarence404.assistant.agent.AgentTool;
import io.github.clarence404.assistant.mall.StubMallApi;
import io.github.clarence404.assistant.tool.MallTools;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 第 8 章：{@code @McpTool} 方法这一层（不走协议）——进门、结果格式、截断、错误不外泄。
 */
class MallMcpToolsTest {

    private static final StubMallApi MALL = StubMallApi.start();

    private McpFixtures.Mcp mcp;

    @AfterAll
    static void stop() {
        MALL.close();
    }

    @BeforeEach
    void setUp() {
        MALL.reset();
        mcp = McpFixtures.mcp(MALL);
    }

    @Test
    void anonymousOrderToolIsAnErrorResult() {
        CallToolResult result = mcp.tools().queryOrder(StubMallApi.PENDING_PAYMENT_ORDER_NO, as(null));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo(McpGate.LOGIN_REQUIRED);
    }

    @Test
    void anonymousCannotProposeAnything() {
        CallToolResult result = mcp.tools().proposeCancelOrder(StubMallApi.PENDING_PAYMENT_ORDER_NO, as(null));

        assertThat(result.isError()).isTrue();
        assertThat(MALL.requests()).isEmpty();
    }

    @Test
    void resultIsJsonWithoutToken() {
        CallToolResult result = mcp.tools().queryOrder(StubMallApi.PENDING_PAYMENT_ORDER_NO, as(StubMallApi.TOKEN));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).startsWith("{").contains("\"found\":true").doesNotContain(StubMallApi.TOKEN);
    }

    @Test
    void proposalResultSaysNotExecuted() {
        CallToolResult result =
                mcp.tools().proposeCancelOrder(StubMallApi.PENDING_PAYMENT_ORDER_NO, as(StubMallApi.TOKEN));

        assertThat(text(result)).contains("\"proposed\":true", "\"executed\":false", "confirmUrl");
        assertThat(MALL.writes()).isEmpty();
    }

    @Test
    void queryProductReusesChapter3Tool() {
        CallToolResult result = mcp.tools().queryProduct("20001", as(null));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("20001");
    }

    @Test
    void longResultsAreTruncated() {
        McpProperties small = new McpProperties(
                "http://localhost:8088/", 200, 30, List.of(), Duration.ofMinutes(10), McpProperties.Client.defaults());
        McpFixtures.Mcp tiny = McpFixtures.mcp(MALL, small);

        String text = text(tiny.tools().searchKnowledge("七天无理由退货要什么条件", as(null)));

        assertThat(text).hasSize(200 + McpResults.TRUNCATED.length()).endsWith(McpResults.TRUNCATED);
    }

    @Test
    void unexpectedErrorsDoNotLeakDetails() {
        McpMallService broken = mock(McpMallService.class);
        when(broken.propose(any(), any(AgentTool.class), any(), any()))
                .thenThrow(new IllegalStateException("连接 jdbc:postgresql://db.example/secret 失败"));
        MallMcpTools tools = new MallMcpTools(mcp.gate(), broken, mock(MallTools.class), McpProperties.defaults());

        CallToolResult result = tools.proposeCancelOrder(StubMallApi.PENDING_PAYMENT_ORDER_NO, as(StubMallApi.TOKEN));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo(MallMcpTools.INTERNAL_ERROR).doesNotContain("jdbc");
    }

    @Test
    void proposalToolNames() {
        assertThat(MallMcpTools.proposalTool(AgentTool.CANCEL_ORDER)).isEqualTo("proposeCancelOrder");
        assertThat(MallMcpTools.proposalTool(AgentTool.CHANGE_ADDRESS)).isEqualTo("proposeChangeAddress");
        assertThat(MallMcpTools.proposalTool(AgentTool.APPLY_AFTER_SALES)).isEqualTo("proposeAfterSales");
    }
}
