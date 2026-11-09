package io.github.clarence404.assistant.memory;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 多轮对话与记忆的配置，前缀 {@code assistant.memory}（第 6 章引入）。每一项在 {@code application.yml} 里都有注释。
 *
 * <p>所在层：业务层的配置。由 {@code @ConfigurationPropertiesScan} 绑定，注入 {@link MemoryConfig}、{@link ConversationMemory}、
 * {@link QuestionCondenser}、{@link ConversationSummarizer} 等。
 *
 * <p>先分清两个「窗口」，它们管的事不一样：
 *
 * <ul>
 *   <li><b>存储窗口</b>（{@code maxMessages}）：一个会话在记忆库里最多留几条消息，超出时最早的被删掉。它防的是「一个会话无限变长、撑爆内存或表」
 *   <li><b>提示词窗口</b>（{@code windowTurns} + {@code budgetChars}）：每次调模型时从存储里取最近几轮、且总字数不超过预算的历史带上。它防的是
 *       「提示词太长」：上下文窗口有上限，越长越慢、越贵，小模型还越容易被前面的话带偏
 * </ul>
 *
 * 一般存储窗口比提示词窗口大：页面刷新后能恢复更多历史，但模型每次只看最近几轮。
 *
 * @param enabled 总开关；关掉后请求里带了会话 ID 也按单轮处理（和第 5 章一样），不读不写记忆
 * @param store 记忆存在哪：{@code memory}（默认，JVM 内存，重启就没了，零依赖）或 {@code jdbc}（PostgreSQL，Spring AI 的
 *     {@code JdbcChatMemoryRepository}）
 * @param ttl 会话空闲多久算过期：最后一条消息之后超过这么久没再说话，下次再来时当作新会话（历史清空）；后台也会定期清理过期会话
 * @param sweepInterval 后台清理过期会话的间隔
 * @param maxMessages 存储窗口：每个会话最多留几条消息（一问一答算两条），超出时删最早的（Spring AI 的 {@code MessageWindowChatMemory}）
 * @param windowTurns 提示词窗口：最多带最近几轮历史（一轮 = 用户一句 + 助手一句）
 * @param budgetChars 提示词窗口：带进提示词的历史最多多少个字（粗略代替 token 预算），超出时从最早的一轮开始丢
 * @param entityTurns 指代沿用：最近几轮里提到过的订单号 / SKU 可以被「它」「这单」沿用；更早的不算（话题多半已经换了）
 * @param condense 追问改写（condense question）
 * @param summary 摘要记忆（默认关）
 * @param ownerCacheTtl 商城令牌 → 商城用户的缓存时长：同一个令牌在这段时间里不再重复问商城「你是谁」
 * @param jdbc {@code store=jdbc} 时的数据库连接
 */
@ConfigurationProperties("assistant.memory")
public record MemoryProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("memory") StoreType store,
        @DefaultValue("30m") Duration ttl,
        @DefaultValue("10m") Duration sweepInterval,
        @DefaultValue("40") int maxMessages,
        @DefaultValue("6") int windowTurns,
        @DefaultValue("2000") int budgetChars,
        @DefaultValue("3") int entityTurns,
        @DefaultValue Condense condense,
        @DefaultValue Summary summary,
        @DefaultValue("5m") Duration ownerCacheTtl,
        @DefaultValue Jdbc jdbc) {

    /** 启动时就校验，配错了立刻报错，而不是第一个用户提问时才发现。 */
    public MemoryProperties {
        if (maxMessages < 2) {
            throw new IllegalArgumentException("assistant.memory.max-messages 至少为 2（一问一答），当前 " + maxMessages);
        }
        if (windowTurns < 0 || budgetChars < 0 || entityTurns < 0) {
            throw new IllegalArgumentException("assistant.memory.window-turns / budget-chars / entity-turns 不能为负数");
        }
        if (windowTurns * 2 > maxMessages) {
            throw new IllegalArgumentException("assistant.memory.window-turns 的两倍不能大于 max-messages（存储里都没留那么多），当前 "
                    + windowTurns + " / " + maxMessages);
        }
    }

    /** 记忆库类型。 */
    public enum StoreType {
        /** Spring AI 的 {@code InMemoryChatMemoryRepository}：一个 ConcurrentHashMap，重启就没了，适合学习和单机。 */
        MEMORY,
        /** Spring AI 的 {@code JdbcChatMemoryRepository}：存进 PostgreSQL 的 {@code SPRING_AI_CHAT_MEMORY} 表，重启不丢、多实例共享。 */
        JDBC
    }

    /**
     * 追问改写：带着历史的问题（「那运费谁出」）先让模型改写成一句不依赖上文的独立问题（「七天无理由退货的运费谁出」），再拿去检索。
     *
     * @param enabled 开关；关掉后检索直接用用户原话（追问多半检索不到）
     * @param historyTurns 改写时给模型看最近几轮（只是为了弄清指代，不用太多）
     * @param maxChars 改写结果最多多少字；超过就当改写失败（小模型有时会直接开始回答问题，写一大段）
     */
    public record Condense(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("3") int historyTurns,
            @DefaultValue("120") int maxChars) {}

    /**
     * 摘要记忆：被提示词窗口挤出去的早期对话，让模型压成一段摘要留在会话里，代替那些原话。
     *
     * @param enabled 开关，默认关：每次压缩都多一次模型调用（回答之后同步做，用户要多等），摘要还可能漏掉或说错细节
     * @param maxChars 摘要最多多少字
     */
    public record Summary(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("300") int maxChars) {}

    /**
     * 记忆存进 PostgreSQL 时的连接（{@code store=jdbc}）。默认在 application.yml 里引用第 4 章 pgvector 的地址和账号，同一个库、不同的表。
     *
     * @param url JDBC 地址
     * @param username 数据库用户
     * @param password 密码；真实密码写进不入库的 config/application-local.yml 或环境变量
     * @param initializeSchema 第一次用到时是否执行 Spring AI 自带的建表脚本（{@code CREATE TABLE IF NOT EXISTS}，已存在不动）
     * @param maxPoolSize 连接池最多几个连接
     */
    public record Jdbc(
            @DefaultValue("jdbc:postgresql://localhost:5432/mall_assistant")
            String url,

            @DefaultValue("mall") String username,
            @DefaultValue("") String password,
            @DefaultValue("true") boolean initializeSchema,
            @DefaultValue("4") int maxPoolSize) {}
}
