package io.github.clarence404.assistant.memory;

import java.time.Clock;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 对话记忆的装配：存储（内存 / JDBC）、存储窗口（{@link MessageWindowChatMemory}）、时钟，并打开定时任务（清理过期会话）（第 6 章引入）。
 *
 * <p>所在层：记忆的配置。产出的 {@link ChatMemory} 与 {@link ChatMemoryRepository} 注入 {@link ConversationMemory}。
 *
 * <p>Spring AI 的模型 Starter 已经带了 {@code ChatMemoryAutoConfiguration}：容器里没有这两个 Bean 时，它会自动配一个内存存储 + 20 条窗口的
 * {@code MessageWindowChatMemory}。这里自己声明，它就退让了（{@code @ConditionalOnMissingBean}），窗口大小和存储类型按我们的配置来。
 *
 * <p>Spring AI 里的三层：
 *
 * <ul>
 *   <li>{@link ChatMemoryRepository}：只管「存取」，按会话 ID 读出 / 整体写入一串消息。实现有内存（{@link InMemoryChatMemoryRepository}，
 *       一个 ConcurrentHashMap）、JDBC、Cassandra、Neo4j 等
 *   <li>{@link ChatMemory}：在存储之上加「策略」。{@link MessageWindowChatMemory} 是滑动窗口：写入后超过 {@code maxMessages} 条就删最早的
 *       （系统消息永远保留，切点会挪到下一条用户消息，保证一问一答不被拆开）
 *   <li>Advisor（{@code MessageChatMemoryAdvisor} 等）：挂在 ChatClient 上自动读写记忆。本项目没用，原因见 {@link ConversationMemory}
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class MemoryConfig {

    /**
     * 时钟：记忆的时间戳、过期判断都用它，测试里换成可以拨动的时钟。
     *
     * @return 系统 UTC 时钟
     */
    @Bean
    public Clock memoryClock() {
        return Clock.systemUTC();
    }

    /**
     * 默认的内存存储（{@code assistant.memory.store} 不写或写 memory 时）：零依赖，重启就没了，只在一个进程里有效。
     *
     * @return 内存存储
     */
    @Bean
    @ConditionalOnProperty(name = "assistant.memory.store", havingValue = "memory", matchIfMissing = true)
    public ChatMemoryRepository inMemoryChatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    /**
     * JDBC 存储（{@code assistant.memory.store=jdbc}）：PostgreSQL 的 {@code SPRING_AI_CHAT_MEMORY} 表，重启不丢、多个实例共享。
     * 连接池和建表都推迟到第一次用到时（见 {@link LazyJdbcChatMemoryRepository}），数据库没启动也不影响应用启动。
     *
     * @param properties {@code assistant.memory.*}（{@code jdbc.*} 连接配置）
     * @return JDBC 存储；应用关闭时关掉它的连接池
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "assistant.memory.store", havingValue = "jdbc")
    public LazyJdbcChatMemoryRepository jdbcChatMemoryRepository(MemoryProperties properties) {
        return new LazyJdbcChatMemoryRepository(properties.jdbc());
    }

    /**
     * 存储窗口：每个会话最多留 {@code max-messages} 条，超出时删最早的。
     *
     * @param repository 上面两种存储之一
     * @param properties {@code assistant.memory.*}
     * @return 对话记忆
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository, MemoryProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(properties.maxMessages())
                .build();
    }
}
