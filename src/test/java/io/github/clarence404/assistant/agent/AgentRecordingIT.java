package io.github.clarence404.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.chat.ChatReply;
import io.github.clarence404.assistant.mall.StubMallApi;
import io.github.clarence404.assistant.prompt.TestPrompts;
import io.github.clarence404.assistant.rag.RagFixtures;
import io.github.clarence404.assistant.rag.RagPlan;
import io.github.clarence404.assistant.rag.RagProperties;
import io.github.clarence404.assistant.rag.RagService;
import io.github.clarence404.assistant.rag.RagSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 录制工具，不是测试：用真实的对话模型（默认 qwen2.5:1.5b）和向量模型（默认 bge-m3）把 {@code recordings/agent-cases.json} 里的每件事完整办一遍
 * （意图识别 → Agent 每一步 → 停下），写进 {@code recordings/agent-<模型>.json}，供 {@link AgentReplayTest} 回放（第 7 章引入）。
 *
 * <p>只有设置了环境变量 {@code RECORD_OLLAMA_URL} 才运行：
 *
 * <pre>
 * RECORD_OLLAMA_URL=http://localhost:11434 RECORD_OLLAMA_MODEL=qwen2.5:1.5b RECORD_EMBEDDING_MODEL=bge-m3 \
 *   ./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=AgentRecordingIT
 * </pre>
 *
 * <p>商城是 {@link StubMallApi}（演示账号登录，每件事前恢复订单的初始状态），知识库 = 6 篇示例规则（内存库入库），RAG 配置取应用的；Prompt 里的日期固定为
 * 2026-11-05。另设 {@code RECORD_AGENT_MODE=native} 时改用方案 A 跑一遍做对比：原生工具调用的输出带工具调用、回放不了，结果只写到
 * {@code target/agent-native-<模型>.json}，不进源码目录。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"assistant.knowledge.ingest-on-startup=false", "assistant.knowledge.memory.dir="})
@EnabledIfEnvironmentVariable(named = "RECORD_OLLAMA_URL", matches = "https?://.+")
class AgentRecordingIT {

    private static final Logger log = LoggerFactory.getLogger(AgentRecordingIT.class);
    private static final String MODEL = System.getenv().getOrDefault("RECORD_OLLAMA_MODEL", "qwen2.5:1.5b");
    private static final String EMBEDDING = System.getenv().getOrDefault("RECORD_EMBEDDING_MODEL", "bge-m3");
    private static final AgentProperties.Mode MODE = AgentProperties.Mode.valueOf(
            System.getenv().getOrDefault("RECORD_AGENT_MODE", "loop").toUpperCase(Locale.ROOT));

    @Autowired
    ChatModel ollama;

    @Autowired
    EmbeddingModel embeddingModel;

    @Autowired
    RagProperties ragProperties;

    @DynamicPropertySource
    static void ollamaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.model.chat", () -> "ollama");
        registry.add("spring.ai.model.embedding", () -> "ollama");
        registry.add("spring.ai.ollama.base-url", () -> System.getenv("RECORD_OLLAMA_URL"));
        registry.add("spring.ai.ollama.chat.model", () -> MODEL);
        registry.add("spring.ai.ollama.embedding.model", () -> EMBEDDING);
        registry.add("assistant.knowledge.embedding-model", () -> EMBEDDING);
    }

    @Test
    void record() throws Exception {
        RagFixtures.Kb kb = RagFixtures.knowledgeBase(embeddingModel, EMBEDDING, RagFixtures.KNOWLEDGE);
        kb.ingestion().ingest(false);
        RecordingChatModel recorder = new RecordingChatModel(ollama);
        Map<String, List<RagSource>> retrievals = new LinkedHashMap<>();
        RagService rag =
                new RagService(
                        ChatClient.builder(recorder).build(),
                        RagFixtures.retriever(kb, ragProperties),
                        TestPrompts.prompts("v3")) {
                    @Override
                    public RagPlan plan(String query) {
                        RagPlan plan = super.plan(query);
                        retrievals.put(query, plan.sources());
                        return plan;
                    }
                };

        List<AgentRecordings.RecordedCase> recorded = new ArrayList<>();
        try (StubMallApi mall = StubMallApi.start()) {
            for (AgentRecordings.Case c : AgentRecordings.cases()) {
                mall.reset();
                recorder.calls.clear();
                retrievals.clear();
                AgentFixtures.Agent agent = AgentFixtures.agent(recorder, rag, mall, AgentProperties.defaults(MODE));
                long start = System.nanoTime();
                ChatReply reply = agent.chat().chat(c.message(), AgentRecordings.token());
                long ms = (System.nanoTime() - start) / 1_000_000;
                AgentRecordings.RecordedCase result = AgentRecordings.recorded(
                        c, reply, ms, new LinkedHashMap<>(retrievals), List.copyOf(recorder.calls));
                recorded.add(result);
                log.info(
                        "[{}] 意图 {}，{} 步 {}，停止 {}，提出 {}，模型调用 {} 次，{} ms：{}",
                        c.name(),
                        reply.intent().intent(),
                        result.steps().size(),
                        result.steps().stream()
                                .map(s -> s.action() + "/" + s.kind())
                                .toList(),
                        result.stopReason(),
                        result.proposal() == null ? null : result.proposal().action(),
                        result.calls().size(),
                        ms,
                        reply.reply());
            }
        }

        AgentRecordings.Recording recording =
                new AgentRecordings.Recording(MODEL, EMBEDDING, LocalDate.now().toString(), recorded);
        Path file;
        if (MODE == AgentProperties.Mode.LOOP) {
            file = AgentRecordings.write(recording);
        } else {
            file = Path.of("target", "agent-native-" + MODEL.replace(':', '-') + ".json");
            Files.writeString(file, AgentRecordings.JSON.writeValueAsString(recording) + "\n", StandardCharsets.UTF_8);
        }
        log.info("已写入 {}", file.toAbsolutePath());
        assertThat(recorded).isNotEmpty();
    }

    /** 包一层真实模型：原样转发，记下 Prompt 指纹与模型输出。 */
    static final class RecordingChatModel implements ChatModel {

        private final ChatModel delegate;
        final List<AgentRecordings.Call> calls = new CopyOnWriteArrayList<>();

        RecordingChatModel(ChatModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ChatResponse response = delegate.call(prompt);
            calls.add(new AgentRecordings.Call(
                    AgentRecordings.promptHash(prompt),
                    response.getResult().getOutput().getText()));
            return response;
        }

        @Override
        public ChatOptions getOptions() {
            return delegate.getOptions();
        }
    }
}
