package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.assistant.cli.CliChatModel;
import io.github.clarence404.assistant.cli.FakeCliSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("claude-cli")
class CliProviderWebTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * 在 Spring 上下文启动前覆盖配置：命令改成当前 JVM 的 java，参数是 {@code -cp <test-classes> FakeCli}；工作目录放临时目录；
     * 通过 {@code assistant.cli.env.*} 给子进程传环境变量，控制 FakeCli 的行为并让它记录收到的参数。
     */
    @DynamicPropertySource
    static void fakeCli(DynamicPropertyRegistry registry) throws Exception {
        Path tmp = Files.createTempDirectory("cli-web-test");
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

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ChatModel chatModel;

    @Test
    void onlyTheCliChatModelIsActive() {
        assertThat(chatModel).isInstanceOf(CliChatModel.class);
    }

    @Test
    void chatGoesThroughTheCliProcess() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new ChatRequest("订单 1849203385720832512 什么时候到？"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("收到：订单 1849203385720832512 什么时候到？"))
                .andExpect(jsonPath("$.intent.intent").value("ORDER_STATUS"))
                .andExpect(jsonPath("$.intent.orderNo").value("1849203385720832512"));
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
        assertThat(events.getLast().event()).isEqualTo("done");
        String text = events.subList(0, events.size() - 1).stream()
                .peek(e -> assertThat(e.event()).isEqualTo("token"))
                .map(e -> e.json().get("text").asString())
                .reduce("", String::concat);
        assertThat(text).isEqualTo("收到：怎么申请退货？");
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
