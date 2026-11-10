package io.github.clarence404.assistant.mcp;

import io.github.clarence404.assistant.aftersales.AfterSalesStore;
import io.github.clarence404.assistant.aftersales.InMemoryAfterSalesStore;
import io.github.clarence404.assistant.agent.AgentProperties;
import io.github.clarence404.assistant.agent.ConfirmationService;
import io.github.clarence404.assistant.agent.WriteActionGuard;
import io.github.clarence404.assistant.chat.ChatClientConfig;
import io.github.clarence404.assistant.chat.FakeChatModel;
import io.github.clarence404.assistant.mall.MallApiClient;
import io.github.clarence404.assistant.mall.MallApiProperties;
import io.github.clarence404.assistant.mall.StubMallApi;
import io.github.clarence404.assistant.memory.MemoryFixtures;
import io.github.clarence404.assistant.rag.RagFixtures;
import io.github.clarence404.assistant.rag.RagService;
import io.github.clarence404.assistant.tool.MallTools;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 第 8 章测试的公共材料：不启动 Spring、不走 MCP 协议，手工组装 MCP 工具背后的整套零件（第 7 章 {@code AgentFixtures} 的做法）。
 *
 * <p>商城是 {@link StubMallApi}，知识库是假向量模型 + 6 篇真实规则文档，确认、售后申请单在内存里，时钟可以拨动（测过期、测次数窗口）。
 * 协议层（JSON-RPC、工具清单、HTTP 头）由 {@link McpServerProtocolTest} 用真的 MCP 客户端测。
 */
public final class McpFixtures {

    private McpFixtures() {}

    /**
     * 默认配置的一套。
     *
     * @param mall 假商城
     * @return 一套
     */
    public static Mcp mcp(StubMallApi mall) {
        return mcp(mall, McpProperties.defaults());
    }

    /**
     * 指定配置的一套。
     *
     * @param mall 假商城
     * @param properties MCP 配置
     * @return 一套
     */
    public static Mcp mcp(StubMallApi mall, McpProperties properties) {
        ChatClient chatClient = new ChatClientConfig().chatClient(ChatClient.builder(new FakeChatModel()));
        RagService rag = RagFixtures.service(chatClient, RagFixtures.fakeKnowledgeBase(), RagFixtures.properties());
        MemoryFixtures.Memory memory = MemoryFixtures.memory(chatClient, mall, MemoryFixtures.properties());
        MallApiClient mallApi =
                new MallApiClient(new MallApiProperties(mall.baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(2)));
        AfterSalesStore afterSales = new InMemoryAfterSalesStore();
        ConfirmationService confirmations = new ConfirmationService(
                mallApi,
                afterSales,
                memory.memory(),
                memory.owners(),
                AgentProperties.defaults(AgentProperties.Mode.LOOP),
                memory.clock());
        McpMallService service =
                new McpMallService(mallApi, rag, new WriteActionGuard(afterSales), confirmations, properties);
        McpGate gate = new McpGate(memory.owners(), new McpRateLimiter(properties, memory.clock()), properties);
        MallMcpTools tools = new MallMcpTools(gate, service, new MallTools(mallApi), properties);
        return new Mcp(tools, service, gate, confirmations, afterSales, memory);
    }

    /**
     * 模拟传输层取到的身份（{@link McpAuth#extract} 的产物）。
     *
     * @param token 商城令牌；null 表示没带
     * @return 传输上下文
     */
    public static McpTransportContext as(String token) {
        return token == null
                ? McpTransportContext.EMPTY
                : McpTransportContext.create(Map.of(McpAuth.CREDENTIAL, new McpAuth.Credential(token)));
    }

    /**
     * 工具结果的文字。
     *
     * @param result 工具结果
     * @return 第一段文字
     */
    public static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    /**
     * 一套零件。
     *
     * @param tools MCP 工具（协议层之下的入口）
     * @param service 业务
     * @param gate 进门
     * @param confirmations 确认（模拟用户在页面上点按钮）
     * @param afterSales 售后申请单
     * @param memory 记忆、归属与时钟
     */
    public record Mcp(
            MallMcpTools tools,
            McpMallService service,
            McpGate gate,
            ConfirmationService confirmations,
            AfterSalesStore afterSales,
            MemoryFixtures.Memory memory) {}
}
