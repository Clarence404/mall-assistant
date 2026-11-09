package io.github.clarence404.assistant.memory;

import io.github.clarence404.assistant.chat.ChatClientConfig;
import io.github.clarence404.assistant.chat.ChatProperties;
import io.github.clarence404.assistant.chat.ChatService;
import io.github.clarence404.assistant.chat.FakeChatModel;
import io.github.clarence404.assistant.intent.IntentService;
import io.github.clarence404.assistant.mall.MallApiClient;
import io.github.clarence404.assistant.mall.MallApiProperties;
import io.github.clarence404.assistant.mall.StubMallApi;
import io.github.clarence404.assistant.prompt.TestPrompts;
import io.github.clarence404.assistant.rag.RagFixtures;
import io.github.clarence404.assistant.rag.RagService;
import io.github.clarence404.assistant.tool.MallTools;
import io.github.clarence404.assistant.tool.ToolProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.core.io.ClassPathResource;

/**
 * 第 6 章测试的公共材料：不带 Spring 的记忆（内存存储 + 可以拨动的时钟）、确认归属用的假商城，以及带记忆的 ChatService。
 *
 * <p>和 application.yml 一样的默认值：存储窗口 40 条、提示词窗口 6 轮 / 2000 字、过期 30 分钟、沿用最近 3 轮里的订单号、改写看最近 3 轮。
 */
public final class MemoryFixtures {

    /** 测试的「现在」：2026-11-05 10:00（UTC）。 */
    public static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    /** 两个合法的会话 ID。 */
    public static final String CONVERSATION = "3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d";

    public static final String OTHER_CONVERSATION = "0c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f";

    private MemoryFixtures() {}

    /**
     * 和 application.yml 一样的配置。
     *
     * @return 配置
     */
    public static MemoryProperties properties() {
        return properties(6, 2000, false);
    }

    /**
     * 指定提示词窗口和摘要开关，其余同 application.yml。
     *
     * @param windowTurns 最多带几轮
     * @param budgetChars 历史最多多少字
     * @param summary 是否开摘要记忆
     * @return 配置
     */
    public static MemoryProperties properties(int windowTurns, int budgetChars, boolean summary) {
        return new MemoryProperties(
                true,
                MemoryProperties.StoreType.MEMORY,
                Duration.ofMinutes(30),
                Duration.ofMinutes(10),
                40,
                windowTurns,
                budgetChars,
                3,
                new MemoryProperties.Condense(true, 3, 120),
                new MemoryProperties.Summary(summary, 300),
                Duration.ofMinutes(5),
                new MemoryProperties.Jdbc("jdbc:postgresql://localhost:5432/mall_assistant", "mall", "", true, 4));
    }

    /**
     * 一套记忆。
     *
     * @param chatClient 摘要记忆用的 ChatClient
     * @param mall 假商城（确认归属）
     * @param properties 配置
     * @return 记忆与它的零件
     */
    public static Memory memory(ChatClient chatClient, StubMallApi mall, MemoryProperties properties) {
        MutableClock clock = new MutableClock(NOW);
        ChatMemoryRepository repository = new InMemoryChatMemoryRepository();
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(properties.maxMessages())
                .build();
        ConversationOwners owners = new ConversationOwners(mallApi(mall), properties, clock);
        ConversationMemory memory = new ConversationMemory(
                chatMemory, repository, owners, new ConversationSummarizer(chatClient, properties), properties, clock);
        return new Memory(memory, repository, owners, clock);
    }

    /**
     * 带记忆的 ChatService：模型是假的，知识库是假向量模型 + 6 篇真实规则文档，商城是假网关。
     *
     * @param model 假模型
     * @param mall 假商城
     * @param properties 记忆配置
     * @return ChatService 与记忆
     */
    public static Chat chat(FakeChatModel model, StubMallApi mall, MemoryProperties properties) {
        ChatClient chatClient = new ChatClientConfig().chatClient(ChatClient.builder(model));
        RagService rag = RagFixtures.service(chatClient, RagFixtures.fakeKnowledgeBase(), RagFixtures.properties());
        return chat(chatClient, rag, mall, properties);
    }

    /**
     * 带记忆的 ChatService，RAG 由调用方给（录制回放时用录下的检索结果）。
     *
     * @param chatClient ChatClient
     * @param rag RAG 服务
     * @param mall 假商城
     * @param properties 记忆配置
     * @return ChatService 与记忆
     */
    public static Chat chat(ChatClient chatClient, RagService rag, StubMallApi mall, MemoryProperties properties) {
        Memory memory = memory(chatClient, mall, properties);
        ChatService service = new ChatService(
                chatClient,
                new ChatProperties(Duration.ofSeconds(5), 0.6),
                TestPrompts.prompts("v3"),
                new IntentService(chatClient, new ClassPathResource("prompts/intent-v2.st")),
                new MallTools(mallApi(mall)),
                new ToolProperties(true, true),
                rag,
                memory.memory(),
                new QuestionCondenser(chatClient, properties));
        return new Chat(service, memory);
    }

    /** 指向假商城的客户端。 */
    static MallApiClient mallApi(StubMallApi mall) {
        return new MallApiClient(new MallApiProperties(mall.baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(2)));
    }

    /**
     * 一套记忆。
     *
     * @param memory 对话记忆
     * @param repository 背后的内存存储（断言「存了什么」用）
     * @param owners 归属
     * @param clock 可以拨动的时钟
     */
    public record Memory(
            ConversationMemory memory, ChatMemoryRepository repository, ConversationOwners owners, MutableClock clock) {

        /**
         * 某个归属的某个会话在存储里的全部消息。
         *
         * @param owner 归属
         * @param conversationId 会话 ID
         * @return 消息
         */
        public java.util.List<org.springframework.ai.chat.messages.Message> stored(
                ConversationOwner owner, String conversationId) {
            return repository.findByConversationId(owners.memoryKey(owner, conversationId));
        }
    }

    /**
     * 带记忆的 ChatService。
     *
     * @param service ChatService
     * @param memory 记忆
     */
    public record Chat(ChatService service, Memory memory) {}

    /** 可以拨动的时钟：测会话过期、归属缓存过期。 */
    public static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        /**
         * 往后拨。
         *
         * @param duration 拨多久
         */
        public void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
