package io.github.clarence404.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.chat.ChatReply;
import io.github.clarence404.assistant.mall.StubMallApi;
import io.github.clarence404.assistant.prompt.TestPrompts;
import io.github.clarence404.assistant.rag.RagFixtures;
import io.github.clarence404.assistant.rag.RagProperties;
import io.github.clarence404.assistant.rag.RagService;
import io.github.clarence404.assistant.tool.ToolInvocation;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * 录制工具，不是测试：用真实的对话模型（默认 qwen2.5:1.5b）和向量模型（默认 bge-m3）把 {@code recordings/conversation-cases.json} 里的每段对话
 * 完整跑一遍（意图识别 → 沿用订单号 → 追问改写 → 检索 → 回答 → 写记忆），写进 {@code recordings/conversation-<模型>.json}，供
 * {@link ConversationReplayTest} 回放（第 6 章引入）。
 *
 * <p>只有设置了环境变量 {@code RECORD_OLLAMA_URL} 才运行：
 *
 * <pre>
 * RECORD_OLLAMA_URL=http://localhost:11434 RECORD_OLLAMA_MODEL=qwen2.5:1.5b RECORD_EMBEDDING_MODEL=bge-m3 \
 *   ./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ConversationRecordingIT
 * </pre>
 *
 * <p>知识库 = 6 篇示例规则（内存库入库）；商城是 {@link StubMallApi}（登录的对话带演示账号的令牌）；记忆是内存存储，配置同 application.yml；
 * RAG 配置取应用的（bge-m3 的门槛 0.6）。Prompt 里的日期固定为 2026-11-05。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"assistant.knowledge.ingest-on-startup=false", "assistant.knowledge.memory.dir="})
@EnabledIfEnvironmentVariable(named = "RECORD_OLLAMA_URL", matches = "https?://.+")
class ConversationRecordingIT {

    private static final Logger log = LoggerFactory.getLogger(ConversationRecordingIT.class);
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
    void record() throws Exception {
        RagFixtures.Kb kb = RagFixtures.knowledgeBase(embeddingModel, EMBEDDING, RagFixtures.KNOWLEDGE);
        kb.ingestion().ingest(false);
        RecordingChatModel recorder = new RecordingChatModel(ollama);
        ChatClient chatClient = ChatClient.builder(recorder).build();
        RagService rag =
                new RagService(chatClient, RagFixtures.retriever(kb, ragProperties), TestPrompts.prompts("v3"));

        List<ConversationRecordings.RecordedCase> recorded = new ArrayList<>();
        try (StubMallApi mall = StubMallApi.start()) {
            for (ConversationRecordings.Case c : ConversationRecordings.cases()) {
                MemoryFixtures.Chat chat = MemoryFixtures.chat(chatClient, rag, mall, MemoryFixtures.properties());
                recorder.calls.clear();
                recorded.add(run(chat, c, recorder));
            }
        }

        Path file = ConversationRecordings.write(new ConversationRecordings.Recording(
                MODEL, EMBEDDING, LocalDate.now().toString(), recorded));
        log.info("已写入 {}", file.toAbsolutePath());
        assertThat(recorded).isNotEmpty();
    }

    /** 跑一段对话：录制与回放共用（回放时模型和 RAG 换成录下来的）。 */
    static ConversationRecordings.RecordedCase run(
            MemoryFixtures.Chat chat, ConversationRecordings.Case c, RecordingChatModel recorder) {
        String conversationId = UUID.nameUUIDFromBytes(c.name().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
        String token = c.login() ? StubMallApi.TOKEN : null;
        List<ConversationRecordings.Turn> turns = new ArrayList<>();
        for (String message : c.turns()) {
            long start = System.nanoTime();
            ChatReply reply = chat.service().chat(message, conversationId, token);
            long ms = (System.nanoTime() - start) / 1_000_000;
            MemoryTrace memory = reply.memory();
            ConversationRecordings.Turn turn = new ConversationRecordings.Turn(
                    message,
                    reply.intent(),
                    memory.historyTurns(),
                    memory.condensedQuestion(),
                    memory.carriedOrderNo(),
                    reply.rag() == null ? null : reply.rag().query(),
                    reply.sources(),
                    reply.tools().stream().map(ToolInvocation::name).toList(),
                    reply.reply());
            turns.add(turn);
            log.info(
                    "[{}] {} -> 意图 {}，带 {} 轮历史，改写「{}」，沿用订单号 {}，工具 {}，{} 条资料，{} ms：{}",
                    c.name(),
                    message,
                    reply.intent().intent(),
                    memory.historyTurns(),
                    memory.condensedQuestion(),
                    memory.carriedOrderNo(),
                    turn.tools(),
                    reply.sources().size(),
                    ms,
                    reply.reply());
        }
        return new ConversationRecordings.RecordedCase(
                c.name(), c.login(), turns, recorder == null ? List.of() : List.copyOf(recorder.calls));
    }

    /** 包一层真实模型：原样转发，记下 Prompt 指纹与模型输出。 */
    static final class RecordingChatModel implements ChatModel {

        private final ChatModel delegate;
        final List<ConversationRecordings.Call> calls = new CopyOnWriteArrayList<>();

        RecordingChatModel(ChatModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ChatResponse response = delegate.call(prompt);
            calls.add(new ConversationRecordings.Call(
                    ConversationRecordings.promptHash(prompt),
                    response.getResult().getOutput().getText()));
            return response;
        }

        @Override
        public ChatOptions getOptions() {
            return delegate.getOptions();
        }
    }
}
