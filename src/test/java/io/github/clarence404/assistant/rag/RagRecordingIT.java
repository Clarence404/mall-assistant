package io.github.clarence404.assistant.rag;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.chat.ChatProperties;
import io.github.clarence404.assistant.chat.ChatReply;
import io.github.clarence404.assistant.chat.ChatService;
import io.github.clarence404.assistant.intent.IntentResult;
import io.github.clarence404.assistant.intent.IntentService;
import io.github.clarence404.assistant.mall.MallApiClient;
import io.github.clarence404.assistant.mall.MallApiProperties;
import io.github.clarence404.assistant.prompt.TestPrompts;
import io.github.clarence404.assistant.tool.MallTools;
import io.github.clarence404.assistant.tool.ToolProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 录制工具，不是测试：用真实的向量模型检索（默认 bge-m3）、真实的对话模型回答（默认 qwen2.5:1.5b），把 {@code recordings/rag-cases.json}
 * 每条问题的引用来源和模型原样输出写进 {@code recordings/rag-<模型>.json}，供 {@link RagReplayTest} 回放（第 5 章引入）。
 *
 * <p>只有设置了环境变量 {@code RECORD_OLLAMA_URL} 才运行：
 *
 * <pre>
 * RECORD_OLLAMA_URL=http://localhost:11434 RECORD_OLLAMA_MODEL=qwen2.5:1.5b RECORD_EMBEDDING_MODEL=bge-m3 \
 *   ./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=RagRecordingIT
 * </pre>
 *
 * <p>知识库 = 6 篇示例规则 + 一篇带提示注入的「会员日活动」（{@link RagServiceTest#EVIL_DOC}），复制到临时目录、内存库入库；RAG 配置同
 * application.yml（相似度门槛 0.6 是按 bge-m3 定的）。意图直接给定，不调意图识别；Prompt 的日期固定为 2026-11-05。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"assistant.knowledge.ingest-on-startup=false", "assistant.knowledge.memory.dir="})
@EnabledIfEnvironmentVariable(named = "RECORD_OLLAMA_URL", matches = "https?://.+")
class RagRecordingIT {

    private static final Logger log = LoggerFactory.getLogger(RagRecordingIT.class);
    private static final String MODEL = System.getenv().getOrDefault("RECORD_OLLAMA_MODEL", "qwen2.5:1.5b");
    private static final String EMBEDDING = System.getenv().getOrDefault("RECORD_EMBEDDING_MODEL", "bge-m3");

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
    void record(@TempDir Path dir) throws Exception {
        RagServiceTest.copyKnowledge(dir);
        Files.writeString(dir.resolve("evil.md"), RagServiceTest.EVIL_DOC, StandardCharsets.UTF_8);
        RagFixtures.Kb kb = RagFixtures.knowledgeBase(embeddingModel, EMBEDDING, dir.toUri() + "*.md");
        kb.ingestion().ingest(false);

        RecordingChatModel recorder = new RecordingChatModel(ollama);
        ChatClient chatClient = ChatClient.builder(recorder).build();
        RagService rag =
                new RagService(chatClient, RagFixtures.retriever(kb, ragProperties), TestPrompts.prompts("v3"));
        ChatService chat = chatService(chatClient, rag);

        List<RagRecordings.RecordedCase> recorded = new ArrayList<>();
        for (RagRecordings.Case c : RagRecordings.cases()) {
            recorder.calls.clear();
            long start = System.nanoTime();
            ChatReply reply = chat.answer(c.message(), new IntentResult(c.intent(), 0.9, null, null), null);
            long ms = (System.nanoTime() - start) / 1_000_000;
            RecordingChatModel.Call call = recorder.calls.isEmpty() ? null : recorder.calls.getFirst();
            recorded.add(new RagRecordings.RecordedCase(
                    c.message(),
                    c.intent(),
                    c.note(),
                    reply.sources(),
                    call == null ? null : call.promptHash(),
                    call == null ? null : call.output(),
                    reply.reply(),
                    reply.rag().cited(),
                    reply.rag().invalidCitations(),
                    reply.rag().uncited()));
            log.info(
                    "{} -> {}（{} 条来源，{} ms，引用 {}，去掉 {}）",
                    c.message(),
                    reply.reply(),
                    reply.sources().size(),
                    ms,
                    reply.rag().cited(),
                    reply.rag().invalidCitations());
        }

        Path file = RagRecordings.write(
                new RagRecordings.Recording(MODEL, EMBEDDING, LocalDate.now().toString(), recorded));
        log.info("已写入 {}", file.toAbsolutePath());
        assertThat(recorded).isNotEmpty();
    }

    /** 录制与回放共用的 ChatService：商城地址随便给（RAG 路径不调商城），意图不经过意图识别。 */
    static ChatService chatService(ChatClient chatClient, RagService rag) {
        MallTools tools = new MallTools(new MallApiClient(
                new MallApiProperties("http://127.0.0.1:1", Duration.ofSeconds(1), Duration.ofSeconds(1))));
        return new ChatService(
                chatClient,
                new ChatProperties(Duration.ofSeconds(60), 0.6),
                TestPrompts.prompts("v3"),
                new IntentService(chatClient, new ClassPathResource("prompts/intent-v2.st")),
                tools,
                new ToolProperties(true, true),
                rag);
    }

    /** 包一层真实模型：原样转发，记下 Prompt 指纹与模型输出。 */
    static final class RecordingChatModel implements ChatModel {

        record Call(String promptHash, String output) {}

        private final ChatModel delegate;
        final List<Call> calls = new CopyOnWriteArrayList<>();

        RecordingChatModel(ChatModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ChatResponse response = delegate.call(prompt);
            calls.add(new Call(
                    RagRecordings.promptHash(prompt),
                    response.getResult().getOutput().getText()));
            return response;
        }

        @Override
        public ChatOptions getOptions() {
            return delegate.getOptions();
        }
    }
}
