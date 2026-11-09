package io.github.clarence404.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.chat.ChatReply;
import io.github.clarence404.assistant.chat.ChatService;
import io.github.clarence404.assistant.intent.IntentResult;
import io.github.clarence404.assistant.mall.StubMallApi;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 录制工具，不是测试：对真实 Ollama 跑一遍 {@code recordings/tools-cases.json}，看模型拿到工具清单后会不会调、调哪个、参数怎么填，
 * 把每次模型调用的原样输出写进 {@code src/test/resources/recordings/tools-<模型>.json}，供 {@link ToolDecisionReplayTest} 回放。第 3 章引入。
 *
 * <p>只有设置了环境变量 {@code RECORD_OLLAMA_URL} 才运行，CI 和平时的 {@code ./mvnw verify} 都跳过它：
 *
 * <pre>
 * RECORD_OLLAMA_URL=http://localhost:11434 RECORD_OLLAMA_MODEL=qwen2.5:1.5b \
 *   ./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ToolDecisionRecordingIT
 * </pre>
 *
 * 模型服务的地址只从环境变量读，不写进录制文件。
 *
 * <p>做法：意图固定为 UNKNOWN、关掉直接查询（{@link ToolRecordings#chatService}），每条消息都由模型自己决定；商城是 {@link StubMallApi}，
 * 已登录（令牌在 ToolContext 里），所以工具结果是确定的。温度设为 0，同一个模型连续录几次结果一致。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.ai.ollama.chat.temperature=0", "assistant.knowledge.ingest-on-startup=false"})
@EnabledIfEnvironmentVariable(named = "RECORD_OLLAMA_URL", matches = "https?://.+")
class ToolDecisionRecordingIT {

    private static final Logger log = LoggerFactory.getLogger(ToolDecisionRecordingIT.class);
    private static final String MODEL = System.getenv().getOrDefault("RECORD_OLLAMA_MODEL", "qwen2.5:0.5b");

    @Autowired
    ChatModel ollama;

    @DynamicPropertySource
    static void ollamaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.model.chat", () -> "ollama");
        registry.add("spring.ai.ollama.base-url", () -> System.getenv("RECORD_OLLAMA_URL"));
        registry.add("spring.ai.ollama.chat.model", () -> MODEL);
    }

    @Test
    void record() throws Exception {
        List<ToolRecordings.RecordedCase> recorded = new ArrayList<>();
        try (StubMallApi mall = StubMallApi.start()) {
            RecordingChatModel recorder = new RecordingChatModel(ollama);
            ChatService service = ToolRecordings.chatService(recorder, mall.baseUrl());
            for (ToolRecordings.Case c : ToolRecordings.cases()) {
                recorder.calls.clear();
                ChatReply reply = service.answer(c.message(), IntentResult.unknown(), StubMallApi.TOKEN);
                List<ToolRecordings.Invocation> invocations = reply.tools().stream()
                        .map(t -> new ToolRecordings.Invocation(t.name(), t.arguments()))
                        .toList();
                recorded.add(new ToolRecordings.RecordedCase(
                        c.message(),
                        c.expectedTool(),
                        c.expectedArguments(),
                        List.copyOf(recorder.calls),
                        invocations,
                        reply.reply()));
                log.info("{} -> 工具 {}，回答：{}", c.message(), invocations, reply.reply());
            }
        }
        Path file = ToolRecordings.write(
                new ToolRecordings.Recording(MODEL, LocalDate.now().toString(), recorded));
        log.info("已写入 {}", file.toAbsolutePath());
        assertThat(recorded).isNotEmpty();
    }

    /** 包一层真实模型：原样转发，记下 Prompt 指纹、回答文字和调用意图。 */
    static final class RecordingChatModel implements ChatModel {

        private final ChatModel delegate;
        final List<ToolRecordings.Call> calls = new CopyOnWriteArrayList<>();

        RecordingChatModel(ChatModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ChatResponse response = delegate.call(prompt);
            AssistantMessage output = response.getResult().getOutput();
            calls.add(new ToolRecordings.Call(
                    ToolRecordings.promptHash(prompt),
                    output.getText(),
                    output.getToolCalls().stream()
                            .map(c -> new ToolRecordings.RecordedToolCall(c.id(), c.name(), c.arguments()))
                            .toList()));
            return response;
        }

        /** 以真实模型的默认选项为底（OllamaChatOptions：既能带工具，也能带 Schema）。 */
        @Override
        public ChatOptions getOptions() {
            return delegate.getOptions();
        }
    }
}
