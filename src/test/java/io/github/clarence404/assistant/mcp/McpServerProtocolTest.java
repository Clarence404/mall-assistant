package io.github.clarence404.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.chat.FakeChatModel;
import io.github.clarence404.assistant.knowledge.FakeEmbeddingModel;
import io.github.clarence404.assistant.knowledge.KnowledgeIngestionService;
import io.github.clarence404.assistant.mall.StubMallApi;
import io.github.clarence404.assistant.rag.RagFixtures;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 第 8 章：MCP 协议级集成测试——用<b>真的</b> MCP Java SDK 客户端，走 Streamable HTTP 连上启动起来的整个应用的 {@code /mcp}。
 *
 * <p>和单元测试的区别：这里经过了全部真实的协议层——JSON-RPC 编解码、initialize 握手与会话 ID、{@code @McpTool} 扫描生成的工具清单与参数 Schema、
 * 传输层的身份提取（{@link McpAuth#extract}）与来源校验、资源列表。只有模型（用不到）和商城（{@link StubMallApi}）是假的。
 *
 * <p>覆盖：initialize（服务名、说明、能力）、tools/list（8 个工具、只读提示、上下文参数不进 Schema）、匿名调公开工具、匿名调订单工具被拦、
 * 带令牌查订单（手机号打码）、提出取消只拿到确认 ID（商城没收到写请求）→ 页面用确认接口确认后才取消、getConfirmation 查结果、资源列表与读取、
 * 浏览器来源校验、令牌不出现在任何工具结果和日志里。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.ai.model.chat=none",
            "spring.ai.model.embedding=none",
            "assistant.knowledge.embedding-model=fake-ngram",
            "assistant.knowledge.ingest-on-startup=false",
            "assistant.knowledge.memory.dir=",
            "assistant.rag.min-score=" + RagFixtures.FAKE_MIN_SCORE
        })
@Import(McpServerProtocolTest.FakeModels.class)
@ExtendWith(OutputCaptureExtension.class)
class McpServerProtocolTest {

    private static final StubMallApi MALL = StubMallApi.start();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TOKEN = StubMallApi.TOKEN;
    private static final String PENDING = StubMallApi.PENDING_PAYMENT_ORDER_NO;

    @LocalServerPort
    int port;

    @Autowired
    KnowledgeIngestionService ingestion;

    private McpSyncClient anonymous;
    private McpSyncClient demoUser;

    /** 假商城的 HttpServer 有非守护线程，不关的话测试进程要等 surefire 强杀。 */
    @AfterAll
    static void stopMall() {
        MALL.close();
    }

    @DynamicPropertySource
    static void mallApi(DynamicPropertyRegistry registry) {
        registry.add("assistant.mall.base-url", MALL::baseUrl);
        registry.add("assistant.mcp.confirm-url", () -> "http://localhost:8088/");
    }

    @BeforeEach
    void connect() {
        MALL.reset();
        ingestion.ingest(false);
        anonymous = client(null);
        demoUser = client(TOKEN);
    }

    @AfterEach
    void close() {
        anonymous.closeGracefully();
        demoUser.closeGracefully();
    }

    /** 真的 MCP 客户端：Streamable HTTP，令牌放在 Authorization 头里（不是工具参数）。 */
    private McpSyncClient client(String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(
                        "http://localhost:" + port)
                .endpoint("/mcp")
                .requestBuilder(request)
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(10))
                .clientInfo(new McpSchema.Implementation("protocol-test", "1.0.0"))
                .build();
        client.initialize();
        return client;
    }

    private static McpSchema.CallToolResult call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        return client.callTool(new McpSchema.CallToolRequest(tool, arguments));
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    private static JsonNode json(McpSchema.CallToolResult result) {
        return JSON.readTree(text(result));
    }

    @Test
    void initializeTellsWhoWeAre() {
        McpSchema.InitializeResult init = anonymous.initialize();

        assertThat(init.serverInfo().name()).isEqualTo("mall-assistant");
        assertThat(init.instructions()).contains("propose", "确认");
        assertThat(init.capabilities().tools()).isNotNull();
        assertThat(init.capabilities().resources()).isNotNull();
    }

    @Test
    void listsEightToolsWithHintsAndNoContextParameter() {
        List<McpSchema.Tool> tools = anonymous.listTools().tools();

        assertThat(tools)
                .extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder(
                        "searchKnowledge",
                        "queryProduct",
                        "listMyOrders",
                        "queryOrder",
                        "proposeCancelOrder",
                        "proposeChangeAddress",
                        "proposeAfterSales",
                        "getConfirmation");
        McpSchema.Tool queryOrder = tools.stream()
                .filter(t -> t.name().equals("queryOrder"))
                .findFirst()
                .orElseThrow();
        assertThat(queryOrder.annotations().readOnlyHint()).isTrue();
        // 令牌走 HTTP 头：参数 Schema 里只有 orderNo，没有 token、context 之类的参数
        assertThat(String.valueOf(queryOrder.inputSchema().get("properties")))
                .contains("orderNo")
                .doesNotContainIgnoringCase("token")
                .doesNotContainIgnoringCase("context");
        McpSchema.Tool cancel = tools.stream()
                .filter(t -> t.name().equals("proposeCancelOrder"))
                .findFirst()
                .orElseThrow();
        assertThat(cancel.annotations().readOnlyHint()).isFalse();
        assertThat(cancel.description()).contains("不会立即执行", "确认");
        // 第 3 章的 @Tool（queryOrderStatus）没有被自动暴露出去
        assertThat(tools).extracting(McpSchema.Tool::name).doesNotContain("queryOrderStatus");
    }

    @Test
    void anonymousCanSearchKnowledgeButNotOrders() {
        McpSchema.CallToolResult knowledge = call(anonymous, "searchKnowledge", Map.of("query", "七天无理由退货要什么条件"));
        McpSchema.CallToolResult order = call(anonymous, "queryOrder", Map.of("orderNo", PENDING));

        assertThat(knowledge.isError()).isFalse();
        assertThat(json(knowledge).get("found").asBoolean()).isTrue();
        assertThat(json(knowledge).get("snippets").get(0).get("source").asString())
                .endsWith(".md");
        assertThat(order.isError()).isTrue();
        assertThat(text(order)).contains("要登录", "Authorization");
        assertThat(MALL.requests()).noneMatch(r -> r.path().startsWith("/api/orders"));
    }

    @Test
    void queryOrderWithTokenMasksPhone() {
        McpSchema.CallToolResult result = call(demoUser, "queryOrder", Map.of("orderNo", PENDING));

        assertThat(result.isError()).isFalse();
        JsonNode order = json(result);
        assertThat(order.get("statusCode").asString()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.get("receiver").asString()).contains("138****0000");
        assertThat(order.get("allowedProposals").toString()).contains("proposeCancelOrder");
        assertThat(text(result)).doesNotContain(TOKEN);
    }

    @Test
    void proposeOnlyReturnsConfirmationAndPageButtonExecutes(CapturedOutput output) throws Exception {
        McpSchema.CallToolResult proposed = call(demoUser, "proposeCancelOrder", Map.of("orderNo", PENDING));

        JsonNode proposal = json(proposed);
        assertThat(proposal.get("proposed").asBoolean()).isTrue();
        assertThat(proposal.get("executed").asBoolean()).isFalse();
        String id = proposal.get("confirmationId").asString();
        assertThat(proposal.get("confirmUrl").asString()).isEqualTo("http://localhost:8088/?confirm=" + id);
        // 只提议：商城一个写请求都没收到，订单还是待支付
        assertThat(MALL.writes()).isEmpty();
        assertThat(MALL.orderStatus(PENDING)).isEqualTo("PENDING_PAYMENT");
        assertThat(json(call(demoUser, "getConfirmation", Map.of("confirmationId", id)))
                        .get("state")
                        .asString())
                .isEqualTo("PENDING");

        // 页面打开确认链接：GET 取卡片；点「确认」：第 7 章的同一个 POST，不带会话 ID
        HttpResponse<String> card = http("GET", "/api/agent/confirmations/" + id, null, TOKEN);
        HttpResponse<String> other = http("GET", "/api/agent/confirmations/" + id, null, StubMallApi.NEW_USER_TOKEN);
        HttpResponse<String> approved =
                http("POST", "/api/agent/confirmations/" + id, "{\"decision\":\"approve\"}", TOKEN);

        assertThat(card.statusCode()).isEqualTo(200);
        assertThat(card.body()).contains("\"state\":\"PENDING\"", PENDING);
        assertThat(other.statusCode()).isEqualTo(404);
        assertThat(approved.statusCode()).isEqualTo(200);
        assertThat(approved.body()).contains("EXECUTED");
        assertThat(MALL.orderStatus(PENDING)).isEqualTo("CANCELLED");
        assertThat(json(call(demoUser, "getConfirmation", Map.of("confirmationId", id)))
                        .get("state")
                        .asString())
                .isEqualTo("EXECUTED");
        // 令牌从不出现在日志里
        assertThat(output.getAll()).doesNotContain(TOKEN);
    }

    @Test
    void invalidTokenIsRejectedEvenForPublicTools() {
        McpSyncClient forged = client("forged-token");
        try {
            McpSchema.CallToolResult result = call(forged, "searchKnowledge", Map.of("query", "退货"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("令牌无效或已过期").doesNotContain("forged-token");
        } finally {
            forged.closeGracefully();
        }
    }

    @Test
    void knowledgeDocumentsAreResources() {
        List<McpSchema.Resource> resources = anonymous.listResources().resources();

        assertThat(resources).hasSize(6);
        assertThat(resources).extracting(McpSchema.Resource::uri).contains("mall://knowledge/returns.md");
        McpSchema.ReadResourceResult read =
                anonymous.readResource(new McpSchema.ReadResourceRequest("mall://knowledge/returns.md"));
        assertThat(((McpSchema.TextResourceContents) read.contents().getFirst()).text())
                .contains("退换货规则");
    }

    @Test
    void browserFromForeignOriginIsRejected() throws Exception {
        String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"evil\",\"version\":\"1\"}}}";
        HttpResponse<String> evil = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                                .header("Content-Type", "application/json")
                                .header("Accept", "application/json, text/event-stream")
                                .header("Origin", "http://evil.example.com")
                                .POST(HttpRequest.BodyPublishers.ofString(initialize))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(evil.statusCode()).isEqualTo(403);
    }

    @Test
    void unknownToolIsProtocolError() {
        assertThatThrownBy(() -> call(anonymous, "cancelOrder", Map.of("orderNo", PENDING)))
                .hasMessageContaining("Unknown tool");
        assertThat(MALL.writes()).isEmpty();
    }

    private HttpResponse<String> http(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 和接口测试一样的假模型、假向量模型。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class FakeModels {

        @Bean
        FakeChatModel fakeChatModel() {
            return new FakeChatModel();
        }

        @Bean
        FakeEmbeddingModel fakeEmbeddingModel() {
            return new FakeEmbeddingModel();
        }
    }
}
