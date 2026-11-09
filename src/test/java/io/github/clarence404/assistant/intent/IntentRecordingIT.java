package io.github.clarence404.assistant.intent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 录制工具，不是测试：对真实 Ollama 跑一遍 {@code recordings/intent-cases.json}，把每次模型调用的原样输出写进
 * {@code src/test/resources/recordings/intent-<模型>.json}，供 {@link IntentReplayTest} 回放。
 *
 * <p>只有设置了环境变量 {@code RECORD_OLLAMA_URL} 才运行，CI 和平时的 {@code ./mvnw verify} 都跳过它：
 *
 * <pre>
 * RECORD_OLLAMA_URL=http://localhost:11434 RECORD_OLLAMA_MODEL=qwen2.5:0.5b \
 *   ./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=IntentRecordingIT
 * </pre>
 *
 * 模型服务的地址只从环境变量读，不写进录制文件。录完用 {@code git diff src/test/resources/recordings} 看看模型的输出变了什么。
 *
 * <p>做法：用 {@link RecordingChatModel} 包住 Spring 装配好的真实 Ollama 模型，走真正的 {@link IntentService}（含重试），
 * 每次模型调用都记下 Prompt 指纹和原样输出。{@code @EnabledIfEnvironmentVariable} 让它在没设置环境变量时被跳过而不是失败。
 * 类名以 IT 结尾，由 failsafe 在 verify 阶段执行。第 2 章引入。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "assistant.knowledge.ingest-on-startup=false")
@EnabledIfEnvironmentVariable(named = "RECORD_OLLAMA_URL", matches = "https?://.+")
class IntentRecordingIT {

    private static final Logger log = LoggerFactory.getLogger(IntentRecordingIT.class);
    private static final String MODEL = System.getenv().getOrDefault("RECORD_OLLAMA_MODEL", "qwen2.5:0.5b");

    @Autowired
    ChatModel ollama;

    /** 不管当前 profile，强制用 RECORD_OLLAMA_URL 指向的 Ollama 和 RECORD_OLLAMA_MODEL 指定的模型。 */
    @DynamicPropertySource
    static void ollamaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.model.chat", () -> "ollama");
        registry.add("spring.ai.ollama.base-url", () -> System.getenv("RECORD_OLLAMA_URL"));
        registry.add("spring.ai.ollama.chat.model", () -> MODEL);
    }

    /** 逐条跑 intent-cases.json，记下每条消息的全部模型调用和最终解析结果，写成一份录制文件。 */
    @Test
    void record() throws Exception {
        RecordingChatModel recorder = new RecordingChatModel(ollama);
        IntentService service =
                new IntentService(ChatClient.builder(recorder).build(), new ClassPathResource("prompts/intent-v1.st"));

        List<IntentRecordings.RecordedCase> recorded = new ArrayList<>();
        for (IntentRecordings.Case c : IntentRecordings.cases()) {
            recorder.calls.clear();
            IntentResult result = service.classify(c.message());
            recorded.add(
                    new IntentRecordings.RecordedCase(c.message(), c.expected(), List.copyOf(recorder.calls), result));
            log.info("{} -> {}（{} 次调用）", c.message(), result, recorder.calls.size());
        }

        Path file = IntentRecordings.write(
                new IntentRecordings.Recording(MODEL, LocalDate.now().toString(), recorded));
        log.info("已写入 {}", file.toAbsolutePath());
        assertThat(recorded).isNotEmpty();
    }

    /** 包一层真实模型：原样转发，顺手记下 Prompt 指纹与模型输出。 */
    static final class RecordingChatModel implements ChatModel {

        private final ChatModel delegate;
        final List<IntentRecordings.Call> calls = new CopyOnWriteArrayList<>();

        RecordingChatModel(ChatModel delegate) {
            this.delegate = delegate;
        }

        /** 转发给真实模型，并记下这次调用的 Prompt 指纹与模型输出文本。 */
        @Override
        public ChatResponse call(Prompt prompt) {
            ChatResponse response = delegate.call(prompt);
            String output = response.getResult().getOutput().getText();
            calls.add(new IntentRecordings.Call(IntentRecordings.promptHash(prompt), output));
            return response;
        }

        /** 让 ChatClient 以真实模型的默认参数为底（模型名、keep-alive……），再叠加 IntentService 的温度。 */
        @Override
        public ChatOptions getOptions() {
            return delegate.getOptions();
        }
    }
}
