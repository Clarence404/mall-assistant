package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.assistant.cli.CliChatModel;
import io.github.clarence404.assistant.cli.FakeCliSupport;
import io.github.clarence404.assistant.mall.StubMallApi;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * claude-cli profile 整条链路：Controller → ChatService / IntentService → ChatClient → CliChatModel → 子进程。子进程是假 CLI
 * （{@code FakeCli}），回答固定为「收到：用户消息」，意图识别固定返回 ORDER_STATUS。第 2 章引入。
 *
 * <p>和 {@link FakeModelWebTest} 不同，这里不替换 ChatModel，而是用真实的 claude-cli profile 配置启动，只把要执行的命令换成
 * {@code java -cp target/test-classes ...FakeCli}：验证 profile 装配、起进程、输出解析、结构化输出在完整应用里能串起来，CI 上不需要装 claude。
 *
 * <p>第 3 章：命令行模型不支持原生工具调用（profile 里 {@code assistant.tools.native-calling=false}），查订单只走「按意图直接查询」。
 * FakeCli 的意图固定是带订单号的 ORDER_STATUS，所以每次回答前都会先查一次订单，查询结果拼进 stdin 的用户消息里；FakeCli 把收到的
 * stdin 原样回显，正好用来检查「查询结果进了提示词、令牌没进」。
 */
@SpringBootTest(properties = "assistant.knowledge.ingest-on-startup=false") // 第 4 章：不在启动时去连 Ollama 的向量模型
@AutoConfigureMockMvc
@ActiveProfiles("claude-cli")
class CliProviderWebTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * 在 Spring 上下文启动前覆盖配置：命令改成当前 JVM 的 java，参数是 {@code -cp <test-classes> FakeCli}；工作目录放临时目录；
     * 通过 {@code assistant.cli.env.*} 给子进程传环境变量，控制 FakeCli 的行为并让它记录收到的参数。
     */
    private static final StubMallApi MALL = StubMallApi.start();

    private static Path tmp;

    @DynamicPropertySource
    static void fakeCli(DynamicPropertyRegistry registry) throws Exception {
        tmp = Files.createTempDirectory("cli-web-test");
        registry.add("assistant.mall.base-url", MALL::baseUrl);
        registry.add("assistant.cli.command", FakeCliSupport::javaExecutable);
        List<String> args = FakeCliSupport.commandArgs();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            registry.add("assistant.cli.command-args[" + i + "]", () -> arg);
        }
        registry.add("assistant.cli.work-dir", () -> tmp.resolve("work").toString());
        registry.add("assistant.cli.env.FAKE_CLI_MODE", () -> "ok");
        registry.add(
                "assistant.cli.env.FAKE_CLI_RECORD", () -> tmp.resolve("record").toString());
    }

    @AfterAll
    static void stopMall() {
        MALL.close();
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ChatModel chatModel;

    @Test
    void onlyTheCliChatModelIsActive() {
        assertThat(chatModel).isInstanceOf(CliChatModel.class);
    }

    /** 没登录：直接查询得到「请先登录」，拼进用户消息交给命令行模型；不发商城请求。 */
    @Test
    void chatGoesThroughTheCliProcess() throws Exception {
        String body = mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new ChatRequest("订单 1849203385720832512 什么时候到？"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent.intent").value("ORDER_STATUS"))
                .andExpect(jsonPath("$.intent.orderNo").value("1849203385720832512"))
                .andExpect(jsonPath("$.tools[0].name").value("queryOrderStatus"))
                .andExpect(jsonPath("$.tools[0].source").value("DIRECT"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        String reply = JSON.readTree(body).get("reply").asString();
        assertThat(reply)
                .startsWith("收到：订单 1849203385720832512 什么时候到？")
                .contains("<查询结果>")
                .contains("登录");
    }

    /** 登录后：直接查询带着令牌调了商城，查询结果进了 stdin；令牌不在 stdin、系统提示词和命令行参数里。 */
    @Test
    void directLookupFeedsCliWithoutLeakingToken() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + StubMallApi.TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new ChatRequest("订单 1849203385720832512 什么时候到？"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tools.length()").value(1));

        assertThat(MALL.requests())
                .anySatisfy(r -> assertThat(r.authorization()).isEqualTo("Bearer " + StubMallApi.TOKEN));
        Path record = tmp.resolve("record");
        assertThat(FakeCliSupport.recorded(record, ".stdin"))
                .contains("<查询结果>")
                .contains("\"found\":false")
                .doesNotContain(StubMallApi.TOKEN);
        assertThat(FakeCliSupport.recorded(record, ".system")).doesNotContain(StubMallApi.TOKEN);
        assertThat(FakeCliSupport.recordedArgs(record)).noneMatch(arg -> arg.contains(StubMallApi.TOKEN));
    }

    @Test
    void streamSendsIntentThenTokensThenDone() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(JSON.writeValueAsString(new ChatRequest("怎么申请退货？"))))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(30_000); // 要真起两个 JVM 子进程（意图识别 + 回答），给足时间

        List<ChatStreamApiTest.Sse> events = ChatStreamApiTest.afterIntent(ChatStreamApiTest.parse(result));
        assertThat(events.getFirst().event()).as("直接查询的记录在文字之前").isEqualTo("tool");
        assertThat(events.getLast().event()).isEqualTo("done");
        String text = events.subList(1, events.size() - 1).stream()
                .peek(e -> assertThat(e.event()).isEqualTo("token"))
                .map(e -> e.json().get("text").asString())
                .reduce("", String::concat);
        assertThat(text).startsWith("收到：怎么申请退货？").contains("<查询结果>");
    }

    /** 带 Schema 调用时 FakeCli 才返回预设的结构化 JSON（ORDER_STATUS / 0.9）：能拿到它，说明 Schema 参数传到了命令行。 */
    @Test
    void intentEndpointUsesStructuredOutput() throws Exception {
        mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new ChatRequest("订单 1849203385720832512 发货了吗"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("ORDER_STATUS"))
                .andExpect(jsonPath("$.confidence").value(0.9));
    }
}
