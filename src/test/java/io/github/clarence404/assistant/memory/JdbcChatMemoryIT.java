package io.github.clarence404.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.assistant.chat.ChatClientConfig;
import io.github.clarence404.assistant.chat.FakeChatModel;
import io.github.clarence404.assistant.knowledge.FakeEmbeddingModel;
import io.github.clarence404.assistant.mall.StubMallApi;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试：记忆存进真实的 PostgreSQL（第 6 章 ch06-7 的验收测试）。
 *
 * <p>测什么：以 {@code assistant.memory.store=jdbc} 启动整个应用，{@link LazyJdbcChatMemoryRepository} 第一次用到时执行 Spring AI 的建表脚本、
 * 创建 {@code JdbcChatMemoryRepository}；两句话之后第二句带着第一句、查看接口读得到；表里存的键是「归属 + 会话 ID」算出的 UUID、只有原话和回答；
 * 时间戳经过数据库一进一出还在（会话过期才判断得了）；重复初始化（建表脚本再执行一次）无害；删除只删这一个会话。
 *
 * <p>数据库从哪来：同 {@code PgVectorStoreIT}——有 Docker 时 Testcontainers 起 {@code pgvector/pgvector:0.8.1-pg17}（CI 上就是这样）；
 * 没有 Docker 时设置环境变量 {@code PGVECTOR_IT_URL} / {@code PGVECTOR_IT_USERNAME} / {@code PGVECTOR_IT_PASSWORD} 指向一个已有的
 * PostgreSQL（只读写 {@code SPRING_AI_CHAT_MEMORY} 表里本测试随机生成的会话，结束时删掉）；两者都没有就跳过。
 */
@SpringBootTest(
        properties = {
            "spring.ai.model.chat=none",
            "spring.ai.model.embedding=none",
            "assistant.knowledge.embedding-model=fake-ngram",
            "assistant.knowledge.ingest-on-startup=false",
            "assistant.knowledge.memory.dir=",
            "assistant.memory.store=jdbc"
        })
@AutoConfigureMockMvc
@Import(JdbcChatMemoryIT.Models.class)
class JdbcChatMemoryIT {

    static final String IMAGE = "pgvector/pgvector:0.8.1-pg17";

    static final StubMallApi MALL = StubMallApi.start();

    /** 在 {@code @BeforeAll} 里准备：没有数据库时用 Assumptions 跳过整个类。 */
    static Database db;

    /** 本测试用到的记忆键，结束时删掉（连共享数据库时不留垃圾）。 */
    static final List<String> KEYS = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    FakeChatModel model;

    @Autowired
    ChatMemoryRepository repository;

    @Autowired
    ConversationOwners owners;

    @Autowired
    MemoryProperties properties;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("assistant.memory.jdbc.url", () -> db.url());
        registry.add("assistant.memory.jdbc.username", () -> db.username());
        registry.add("assistant.memory.jdbc.password", () -> db.password());
        registry.add("assistant.mall.base-url", MALL::baseUrl);
    }

    @BeforeAll
    static void startDatabase() {
        db = Database.start();
    }

    @AfterAll
    static void cleanUp() {
        if (db != null) {
            JdbcTemplate jdbc = db.jdbc();
            KEYS.forEach(key -> jdbc.update("DELETE FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ?", key));
            db.stop();
        }
        MALL.close();
    }

    private String key(ConversationOwner owner, String conversationId) {
        String key = owners.memoryKey(owner, conversationId);
        KEYS.add(key);
        return key;
    }

    /** 整个应用跑在 jdbc 记忆上：第二句带着第一句；表里只有原话和回答，键是归属 + 会话 ID 算出来的。 */
    @Test
    void chatRemembersInPostgres() throws Exception {
        String id = UUID.randomUUID().toString();
        String key = key(ConversationOwner.user("10001"), id);
        model.reset();
        model.willReply("您好，小王[1]。");
        mockMvc.perform(chat("你好，我叫小王", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memory.store").value("jdbc"))
                .andExpect(jsonPath("$.memory.active").value(true));

        model.willReply("您叫小王。");
        mockMvc.perform(chat("我叫什么", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memory.historyTurns").value(1));
        assertThat(FakeChatModel.allText(model.answerPrompts().getLast())).contains("你好，我叫小王");

        mockMvc.perform(get("/api/conversations/{id}/messages", id)
                        .header("Authorization", "Bearer " + StubMallApi.TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(4))
                .andExpect(jsonPath("$.messages[1].text").value("您好，小王。"));

        List<String> rows = db.jdbc()
                .queryForList(
                        "SELECT type || ':' || content FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ? ORDER BY sequence_id",
                        String.class,
                        key);
        assertThat(rows).containsExactly("USER:你好，我叫小王", "ASSISTANT:您好，小王。", "USER:我叫什么", "ASSISTANT:您叫小王。");
        assertThat(db.jdbc()
                        .queryForObject(
                                "SELECT count(*) FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ?",
                                Integer.class,
                                id))
                .as("表里没有直接用页面的会话 ID 当键")
                .isZero();
    }

    /** 时间戳经过数据库一进一出还在：拨快时钟后会话照样过期。 */
    @Test
    void timestampsSurviveTheDatabaseSoTtlWorks() {
        String id = UUID.randomUUID().toString();
        key(ConversationOwner.ANONYMOUS, id);
        MemoryFixtures.MutableClock clock = new MemoryFixtures.MutableClock(Instant.now());
        ConversationMemory memory = new ConversationMemory(
                MessageWindowChatMemory.builder()
                        .chatMemoryRepository(repository)
                        .maxMessages(40)
                        .build(),
                repository,
                owners,
                new ConversationSummarizer(
                        new ChatClientConfig().chatClient(ChatClient.builder(new FakeChatModel())), properties),
                properties,
                clock);

        try (Conversation conversation = memory.open(id, null)) {
            conversation.save("积分多久过期", "12 个月。");
        }
        List<Message> stored = repository.findByConversationId(owners.memoryKey(ConversationOwner.ANONYMOUS, id));
        assertThat(stored).extracting(Message::getMessageType).containsExactly(MessageType.USER, MessageType.ASSISTANT);
        assertThat(MemoryMessages.timestamp(stored.getFirst())).isNotNull();

        clock.advance(Duration.ofMinutes(31));
        try (Conversation conversation = memory.open(id, null)) {
            assertThat(conversation.trace().expired()).isTrue();
            assertThat(conversation.history()).isEmpty();
        }
    }

    /** 直接用 LazyJdbcChatMemoryRepository：再建一个实例（建表脚本再执行一次）无害；删除只删一个会话；列得出会话。 */
    @Test
    void lazyRepositoryIsIdempotentAndScoped() {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        KEYS.add(a);
        KEYS.add(b);
        try (LazyJdbcChatMemoryRepository second = new LazyJdbcChatMemoryRepository(properties.jdbc())) {
            Instant now = Instant.now();
            second.saveAll(a, List.of(MemoryMessages.user("问 a", now), MemoryMessages.assistant("答 a", now)));
            second.saveAll(b, List.of(MemoryMessages.user("问 b", now)));

            assertThat(second.findConversationIds()).contains(a, b);
            assertThat(repository.findByConversationId(a))
                    .extracting(MemoryMessages::text)
                    .containsExactly("问 a", "答 a");

            second.deleteByConversationId(a);
            assertThat(second.findByConversationId(a)).isEmpty();
            assertThat(second.findByConversationId(b)).hasSize(1);
        }
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder chat(
            String message, String conversationId) {
        return post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + StubMallApi.TOKEN)
                .content("{\"message\":\"" + message + "\",\"conversationId\":\"" + conversationId + "\"}");
    }

    /** 假的对话模型和向量模型：这个测试只关心记忆存在哪。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Models {

        @Bean
        FakeChatModel fakeChatModel() {
            return new FakeChatModel();
        }

        @Bean
        FakeEmbeddingModel fakeEmbeddingModel() {
            return new FakeEmbeddingModel();
        }
    }

    /** 数据库：Testcontainers 或环境变量指向的现成库。 */
    record Database(PostgreSQLContainer container, String url, String username, String password) {

        static Database start() {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                PostgreSQLContainer container = new PostgreSQLContainer(
                                DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                        .withDatabaseName("mall_assistant")
                        .withUsername("mall")
                        .withPassword("mall");
                container.start();
                return new Database(container, container.getJdbcUrl(), "mall", "mall");
            }
            String url = System.getenv("PGVECTOR_IT_URL");
            Assumptions.assumeTrue(url != null && !url.isBlank(), "没有 Docker，也没有设置 PGVECTOR_IT_URL，跳过 JDBC 记忆的集成测试");
            return new Database(
                    null,
                    url,
                    System.getenv().getOrDefault("PGVECTOR_IT_USERNAME", "mall"),
                    System.getenv().getOrDefault("PGVECTOR_IT_PASSWORD", ""));
        }

        JdbcTemplate jdbc() {
            return new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        }

        void stop() {
            if (container != null) {
                container.stop();
            }
        }
    }
}
