package io.github.clarence404.assistant.memory;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.PostgresChatMemoryRepositoryDialect;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * 记忆存进 PostgreSQL：包一层 Spring AI 的 {@link JdbcChatMemoryRepository}，第一次用到时才建表、才创建它（第 6 章引入）。
 *
 * <p>所在层：记忆的存储。由 {@link MemoryConfig} 在 {@code assistant.memory.store=jdbc} 时创建，交给 {@code MessageWindowChatMemory} 使用。
 *
 * <p><b>表结构</b>（Spring AI 自带的 {@code schema-postgresql.sql}）：{@code SPRING_AI_CHAT_MEMORY(conversation_id VARCHAR(36), content TEXT,
 * type VARCHAR(10), "timestamp" TIMESTAMP, sequence_id BIGINT)}，一条消息一行。只存文字、类型和时间——所以记忆里只能放纯文字消息，
 * 我们放进 metadata 的别的东西都会丢（时间戳例外，见 {@link MemoryMessages#TIMESTAMP}）；工具调用消息它直接过滤掉不存。
 * 每次 {@code saveAll} 是「删掉这个会话的全部行、再整体插入」（在一个事务里），所以存储窗口不宜太大。
 *
 * <p><b>为什么要「懒」</b>（和第 4 章 {@code PgVectorConfig} 同一个思路：数据库没启动，应用也要能起来）：
 *
 * <ul>
 *   <li>{@code JdbcChatMemoryRepository.builder().build()} 会<b>当场连一次数据库</b>：就算明确指定了方言，它也要读数据库的产品名，和指定的比对一下；
 *       连不上时等满连接超时、打一大段警告，然后默认用 PostgreSQL 方言。放在启动时，数据库没开就拖慢启动
 *   <li>建表脚本也要连数据库。Spring AI 的 Starter 是借 Spring Boot 的 SQL 初始化机制执行它的，我们没用 Starter（会触发 JDBC 自动配置），
 *       只能自己用 {@link ResourceDatabasePopulator} 执行
 * </ul>
 *
 * 所以连接池设成启动时不连（{@code initializationFailTimeout = -1}），建表和创建 repository 都推迟到第一次读写。失败了下次再试；
 * 失败时的异常由 {@link ConversationMemory} 接住，对话降级成单轮，接口返回 503。
 */
public final class LazyJdbcChatMemoryRepository implements ChatMemoryRepository, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LazyJdbcChatMemoryRepository.class);

    /** Spring AI 自带的建表脚本（在 spring-ai-model-chat-memory-repository-jdbc 的 jar 里，各数据库各一份）。 */
    static final String SCHEMA = "org/springframework/ai/chat/memory/repository/jdbc/schema-postgresql.sql";

    private final HikariDataSource dataSource;
    private final boolean initializeSchema;
    private volatile JdbcChatMemoryRepository delegate;

    /**
     * 创建：只建连接池（不连数据库）。
     *
     * @param jdbc 连接配置
     */
    public LazyJdbcChatMemoryRepository(MemoryProperties.Jdbc jdbc) {
        this.dataSource = dataSource(jdbc);
        this.initializeSchema = jdbc.initializeSchema();
    }

    /**
     * 记忆专用的连接池（HikariCP）。和第 4 章知识库的连接池分开：两边各自开关、各自出问题互不影响，也不用在 Spring 容器里放两个
     * {@code DataSource} Bean（第 4 章的 {@code PgVectorConfig} 按类型注入，多一个就分不清了）。
     *
     * @param jdbc 连接配置
     * @return 连接池：启动时不连数据库、不常驻空闲连接、连接超时 3 秒
     */
    static HikariDataSource dataSource(MemoryProperties.Jdbc jdbc) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("memory-jdbc");
        config.setJdbcUrl(jdbc.url());
        config.setUsername(jdbc.username());
        config.setPassword(jdbc.password());
        config.setMaximumPoolSize(jdbc.maxPoolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(3_000);
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    /**
     * 第一次用到时：（按配置）执行建表脚本，再创建 {@link JdbcChatMemoryRepository}。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>已经建好就直接返回（双重检查：{@code volatile} 字段 + {@code synchronized}，并发的第一次只初始化一次）
     *   <li>{@code initialize-schema=true} 时用 {@code new ResourceDatabasePopulator(new ClassPathResource(SCHEMA)).execute(dataSource)} 建表
     *       （脚本里都是 {@code IF NOT EXISTS}，重复执行无害）
     *   <li>{@code JdbcChatMemoryRepository.builder().dataSource(dataSource).dialect(new PostgresChatMemoryRepositoryDialect()).build()}
     *   <li>任何一步失败都直接抛出（不缓存失败），下次调用再试
     * </ul>
     *
     * @return 已初始化的 repository
     */
    JdbcChatMemoryRepository delegate() {
        JdbcChatMemoryRepository current = delegate;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (delegate == null) {
                if (initializeSchema) {
                    new ResourceDatabasePopulator(new ClassPathResource(SCHEMA)).execute(dataSource);
                }
                delegate = JdbcChatMemoryRepository.builder()
                        .dataSource(dataSource)
                        .dialect(new PostgresChatMemoryRepositoryDialect())
                        .build();
                log.info("对话记忆库已就绪：PostgreSQL 表 SPRING_AI_CHAT_MEMORY");
            }
            return delegate;
        }
    }

    @Override
    public List<String> findConversationIds() {
        return delegate().findConversationIds();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return delegate().findByConversationId(conversationId);
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        delegate().saveAll(conversationId, messages);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        delegate().deleteByConversationId(conversationId);
    }

    /** 应用关闭时关掉连接池。 */
    @Override
    public void close() {
        dataSource.close();
    }
}
