package io.github.clarence404.assistant.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.chat.ChatReply;
import io.github.clarence404.assistant.chat.ChatService;
import io.github.clarence404.assistant.chat.FakeChatModel;
import io.github.clarence404.assistant.intent.Intent;
import io.github.clarence404.assistant.intent.IntentResult;
import io.github.clarence404.assistant.knowledge.FakeEmbeddingModel;
import io.github.clarence404.assistant.knowledge.KnowledgeIngestionService;
import io.github.clarence404.assistant.mall.StubMallApi;
import io.github.clarence404.assistant.rag.RagFixtures;
import io.modelcontextprotocol.client.McpSyncClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.annotation.McpMeta;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 第 8 章：客户端一侧的端到端测试——打开 Spring AI MCP Client，连<b>自己</b>的 {@code /mcp}（回环），把白名单里的远程工具交给对话模型。
 *
 * <p>为什么连自己：不依赖任何外部 MCP 服务，又能把客户端那一半的真实链路（Starter 自动配置的传输与客户端、远程工具的 ToolCallback、
 * 我们的 {@link ExternalMcpTools} 与 {@link McpClientConfig}）全部跑一遍。端口要在启动前定下来（客户端的地址写在配置里），所以先找一个空闲端口。
 *
 * <p>测试里多注册了一个只在测试里有的 MCP 工具 {@code echoMeta}：它把收到的 {@code _meta} 原样返回，用来证明「ToolContext 里的商城令牌没有被发给外部服务」。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
            "spring.ai.model.chat=none",
            "spring.ai.model.embedding=none",
            "assistant.knowledge.embedding-model=fake-ngram",
            "assistant.knowledge.ingest-on-startup=false",
            "assistant.knowledge.memory.dir=",
            "assistant.rag.min-score=" + RagFixtures.FAKE_MIN_SCORE,
            "spring.ai.mcp.client.enabled=true",
            "spring.ai.mcp.client.initialized=false",
            "assistant.mcp.client.allowed-tools=searchKnowledge,echoMeta"
        })
@Import(McpClientLoopbackTest.TestBeans.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpClientLoopbackTest {

    private static final StubMallApi MALL = StubMallApi.start();
    private static final int PORT = freePort();

    @Autowired
    ExternalMcpTools externalTools;

    @Autowired
    SyncMcpToolCallbackProvider remote;

    @Autowired
    ChatService chatService;

    @Autowired
    FakeChatModel model;

    @Autowired
    KnowledgeIngestionService ingestion;

    @Autowired
    List<McpSyncClient> clients;

    /**
     * 测完先关客户端再关假商城。客户端连着自己的 /mcp，会开一个长连接（Streamable HTTP 的 GET 事件流）：不先关，进程退出时 Tomcat 的优雅停机要等这个请求结束，
     * 一直等到 {@code spring.lifecycle.timeout-per-shutdown-phase}（30 秒）。假商城的 HttpServer 有非守护线程，也要关。
     */
    @AfterAll
    void closeClients() {
        clients.forEach(McpSyncClient::closeGracefully);
        MALL.close();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("spring.ai.mcp.client.streamable-http.connections.self.url", () -> "http://localhost:" + PORT);
        registry.add("spring.ai.mcp.client.streamable-http.connections.self.endpoint", () -> "/mcp");
        registry.add("assistant.mall.base-url", MALL::baseUrl);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BeforeEach
    void setUp() {
        model.reset();
        MALL.reset();
        ingestion.ingest(false);
    }

    @Test
    void onlyAllowListedRemoteToolsReachTheModel() {
        List<String> names = externalTools.callbacks().stream()
                .map(t -> t.getToolDefinition().name())
                .toList();

        assertThat(names).containsExactlyInAnyOrder("ext_searchKnowledge", "ext_echoMeta");
        assertThat(externalTools.describe())
                .filteredOn(info -> info.name().equals("proposeCancelOrder"))
                .singleElement()
                .satisfies(info -> assertThat(info.exposedAs()).isNull());
    }

    @Test
    void mallTokenInToolContextIsNotSentAsMeta() {
        ToolCallback echo = Arrays.stream(remote.getToolCallbacks())
                .filter(t -> t.getToolDefinition().name().equals("echoMeta"))
                .findFirst()
                .orElseThrow();

        // 直接用 Spring AI 的远程工具、带着放了令牌的 ToolContext 调：McpClientConfig 的转换器让 _meta 为空
        String result = echo.call("{}", new ToolContext(Map.of("mallToken", StubMallApi.TOKEN)));

        assertThat(result).doesNotContain(StubMallApi.TOKEN).contains("meta-keys:[]");
    }

    @Test
    void chatModelCanCallTheRemoteToolLikeAnyOtherTool() {
        model.willCallTool("ext_searchKnowledge", "{\"query\":\"七天无理由退货要什么条件\"}");
        model.willReply("签收 7 天内、商品完好可以无理由退货。");

        ChatReply reply = chatService.answer(
                "七天无理由退货要什么条件", new IntentResult(Intent.CHITCHAT, 0.9, null, null), StubMallApi.TOKEN);

        Prompt first = model.answerPrompts().getFirst();
        assertThat(FakeChatModel.toolNames(first)).contains("queryOrderStatus", "ext_searchKnowledge");
        Prompt second = model.answerPrompts().getLast();
        assertThat(FakeChatModel.toolResponses(second).getFirst()).contains("returns.md");
        assertThat(FakeChatModel.allText(second)).contains(ExternalMcpTools.ORIGIN_NOTE);
        assertThat(reply.reply()).contains("无理由退货");
    }

    /** 测试专用的 MCP 工具与假模型。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        FakeChatModel fakeChatModel() {
            return new FakeChatModel();
        }

        @Bean
        FakeEmbeddingModel fakeEmbeddingModel() {
            return new FakeEmbeddingModel();
        }

        @Bean
        EchoMetaTool echoMetaTool() {
            return new EchoMetaTool();
        }
    }

    /** 只在测试里有的 MCP 工具：返回收到的 _meta 的键。 */
    static class EchoMetaTool {

        @McpTool(name = "echoMeta", description = "测试用：返回收到的 _meta")
        public String echoMeta(McpMeta meta) {
            Map<String, Object> received = meta == null || meta.meta() == null ? Map.of() : meta.meta();
            return "meta-keys:" + received.keySet() + " values:" + received.values();
        }
    }
}
