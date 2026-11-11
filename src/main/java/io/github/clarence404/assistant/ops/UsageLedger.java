package io.github.clarence404.assistant.ops;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Token 总账：每一轮对话结束时记一笔，按调用方、会话、意图汇总今天用了多少；每日预算也看它（第 9 章引入）。
 *
 * <p>所在层：上线运维。上游是 {@code ChatController}（一轮开始 {@link #open}、结束 {@link #commit}）和 {@code guard.ChatGuard}（进门前问
 * {@link #usedToday} 有没有超预算）；{@link TokenUsageEndpoint}（{@code /actuator/tokens}）和 {@link UsageController}（{@code /api/usage/me}）
 * 读它的汇总。
 *
 * <p><b>为什么按调用方汇总不进 Prometheus</b>：Prometheus 的每个标签值组合是一条独立的时间序列，用户 ID、IP、会话 ID 当标签，序列数会随用户数无限增长
 * （「高基数」），拖垮监控系统。所以指标只按意图、步骤这种几个值的维度分（{@link ChatMetrics}），「谁用了多少」放在这里——一个进程内的小账本。
 * 只存今天的，过了零点清零（预算本来就是按天算的）。
 *
 * <p><b>第 10 章：账存在哪由 {@link UsageStore} 决定</b>。第 9 章的三张表挪进了 {@link MemoryUsageStore}（默认，重启就没了、不跨实例）；
 * {@code assistant.state.store=redis} 时是 {@code state.RedisUsageStore}（Redis 的 {@code HINCRBY} / {@code ZINCRBY}，一段 Lua 脚本里原子地加，
 * 多个实例共用一份账）。这里留下的是规则：一轮只记一次、不记 unattributed、按哪个时区算「今天」、超预算打日志。另一条路是交给网关 / 模型代理
 * （如 LiteLLM、One API 一类）统一记账。
 *
 * <p>预算的粒度是「一轮」：进门时检查今天用的有没有超，超了就拒；这一轮本身会用多少事先不知道，所以最后一轮可能超出预算一点——这和大多数按量计费的
 * 「先用后扣」一样，想严格不超就得在每次模型调用前也检查一次（讲义思考题）。
 */
@Component
public class UsageLedger {

    private static final Logger log = LoggerFactory.getLogger(UsageLedger.class);

    /** 没有意图（意图识别之前就失败了）时记在这个名字下。 */
    static final String NO_INTENT = "NONE";

    private final UsageProperties properties;
    private final Clock clock;
    private final UsageStore store;

    /**
     * 创建：账存在进程内存里（第 9 章的样子；测试里手工组装时用）。
     *
     * @param properties {@code assistant.usage.*}
     * @param clock 时钟（测试里拨动它过零点）
     */
    public UsageLedger(UsageProperties properties, Clock clock) {
        this(properties, clock, new MemoryUsageStore(properties.maxConversations()));
    }

    /**
     * 创建（Spring 注入，第 10 章）。
     *
     * @param properties {@code assistant.usage.*}
     * @param clock 时钟（测试里拨动它过零点）
     * @param store 账存在哪（内存或 Redis，按 {@code assistant.state.store}）
     */
    @Autowired
    public UsageLedger(UsageProperties properties, Clock clock, UsageStore store) {
        this.properties = properties;
        this.clock = clock;
        this.store = store;
    }

    /**
     * 开始一轮。
     *
     * @param caller 调用方（{@code guard.CallerResolver} 算出来的 {@code user:…} / {@code ip:…}）
     * @param conversationId 会话 ID，可以为 null
     * @return 这一轮的账本
     */
    public TurnRecord open(String caller, String conversationId) {
        return new TurnRecord(caller, conversationId);
    }

    /**
     * 一轮结束，记进总账（ch09-5 之一）。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>同一轮只记一次（{@code record.markCommitted()} 返回 false 就直接返回）；{@link TurnRecord#UNATTRIBUTED} 的不记（测试、评测）
     *   <li>日期变了就清零（第 10 章起由 {@link UsageStore} 按传进去的「今天」处理）
     *   <li>这一轮的用量（{@code record.usage()}）记成一个 {@link Tally}（1 轮、输入、输出、有估算就记 1 次估算），分别合并进按调用方、按意图
     *       （没有意图记 {@link #NO_INTENT}）、按会话（有会话 ID 时，键是 {@code 调用方/会话 ID}：不同用户可能用同一个会话 ID）三张表；合并用
     *       {@link Tally#plus}
     *   <li>今天的用量超过预算了（{@code dailyTokenBudget > 0}）记一行 info 日志，下一轮进门时会被拒
     * </ul>
     *
     * @param record 这一轮
     */
    public void commit(TurnRecord record) {
        if (!record.markCommitted() || TurnRecord.UNATTRIBUTED.equals(record.caller())) {
            return;
        }
        UsageTrace usage = record.usage();
        Tally tally = new Tally(1, usage.inputTokens(), usage.outputTokens(), usage.estimated() ? 1 : 0);
        String conversationKey =
                record.conversationId() == null ? null : record.caller() + "/" + record.conversationId();
        long used = store.add(
                today(),
                new UsageStore.Entry(
                        record.caller(),
                        record.intent() == null ? NO_INTENT : record.intent(),
                        conversationKey,
                        tally));
        if (properties.dailyTokenBudget() > 0 && used >= properties.dailyTokenBudget()) {
            log.info("{} 今天的 Token 用量 {} 已到预算 {}，之后的提问会被拒", record.caller(), used, properties.dailyTokenBudget());
        }
    }

    /**
     * 这个调用方今天用了多少 Token（ch09-5 之二）：查按调用方的表，没有记录是 0。
     *
     * @param caller 调用方
     * @return 今天的输入 + 输出 Token
     * @throws io.github.clarence404.assistant.state.StateUnavailableException Redis 出错、而策略是 reject 时（第 10 章）
     */
    public long usedToday(String caller) {
        return store.tokens(today(), caller);
    }

    /** 每日预算；0 = 不限。 */
    public long dailyBudget() {
        return properties.dailyTokenBudget();
    }

    /**
     * 距离预算重置（下一个零点）还有多久：TOKEN_BUDGET_EXCEEDED 的 {@code Retry-After}。
     *
     * @return 时长
     */
    public Duration untilReset() {
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), properties.zone());
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(properties.zone()));
    }

    /**
     * 今天的汇总（{@code /actuator/tokens}）：合计、按意图、用得最多的调用方和会话。
     *
     * @param top 调用方、会话各列前几个
     * @return 汇总
     */
    public Summary summary(int top) {
        LocalDate today = today();
        UsageStore.Totals totals = store.summary(today, top);
        return new Summary(
                today.toString(),
                properties.dailyTokenBudget(),
                totals.total(),
                totals.byIntent(),
                totals.topCallers(),
                totals.topConversations());
    }

    /** 按 {@code assistant.usage.zone} 算的今天。 */
    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), properties.zone());
    }

    /**
     * 一个维度上的累计。
     *
     * @param turns 几轮
     * @param inputTokens 输入 Token
     * @param outputTokens 输出 Token
     * @param estimatedTurns 其中有估算的轮数
     */
    public record Tally(long turns, long inputTokens, long outputTokens, long estimatedTurns) {

        /** 零。 */
        public static final Tally ZERO = new Tally(0, 0, 0, 0);

        /** 输入 + 输出。 */
        public long tokens() {
            return inputTokens + outputTokens;
        }

        /**
         * 相加。
         *
         * @param other 另一份
         * @return 和
         */
        public Tally plus(Tally other) {
            return new Tally(
                    turns + other.turns,
                    inputTokens + other.inputTokens,
                    outputTokens + other.outputTokens,
                    estimatedTurns + other.estimatedTurns);
        }
    }

    /**
     * 汇总里的一行。
     *
     * @param key 调用方 / 意图 / {@code 调用方/会话 ID}
     * @param usage 累计
     */
    public record Row(String key, Tally usage) {}

    /**
     * 今天的汇总。
     *
     * @param date 哪一天
     * @param dailyBudget 每个调用方的每日预算，0 = 不限
     * @param total 合计
     * @param byIntent 按意图（全部）
     * @param topCallers 用得最多的调用方
     * @param topConversations 用得最多的会话
     */
    public record Summary(
            String date,
            long dailyBudget,
            Tally total,
            List<Row> byIntent,
            List<Row> topCallers,
            List<Row> topConversations) {}
}
