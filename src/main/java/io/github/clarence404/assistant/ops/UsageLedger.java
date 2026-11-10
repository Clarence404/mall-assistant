package io.github.clarence404.assistant.ops;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 只存今天的，过了零点清零（预算本来就是按天算的）；重启就没了，也不跨实例：生产上要持久化（写数据库或 Redis 的 {@code INCRBY}），
 * 或者交给网关 / 模型代理（如 LiteLLM、One API 一类）统一记账。
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

    // 下面这些只在 synchronized 方法里读写：一轮记一次账，量很小，一把锁足够
    private LocalDate day;
    private final Map<String, Tally> byCaller = new HashMap<>();
    private final Map<String, Tally> byIntent = new HashMap<>();
    private final Map<String, Tally> byConversation;

    /**
     * 创建。
     *
     * @param properties {@code assistant.usage.*}
     * @param clock 时钟（测试里拨动它过零点）
     */
    public UsageLedger(UsageProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        // 按插入顺序、超过上限删最早的（LinkedHashMap 的 removeEldestEntry）
        this.byConversation = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Tally> eldest) {
                return size() > properties.maxConversations();
            }
        };
        this.day = today();
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
     *   <li>先 {@link #rollover}：日期变了就清空三张表
     *   <li>这一轮的用量（{@code record.usage()}）记成一个 {@link Tally}（1 轮、输入、输出、有估算就记 1 次估算），分别合并进按调用方、按意图
     *       （没有意图记 {@link #NO_INTENT}）、按会话（有会话 ID 时，键是 {@code 调用方/会话 ID}：不同用户可能用同一个会话 ID）三张表；合并用
     *       {@link Tally#plus}
     *   <li>今天的用量超过预算了（{@code dailyTokenBudget > 0}）记一行 info 日志，下一轮进门时会被拒
     * </ul>
     *
     * @param record 这一轮
     */
    public synchronized void commit(TurnRecord record) {
        // TODO ch09-5：record.markCommitted() 为 false、或调用方是 TurnRecord.UNATTRIBUTED 就返回；先 rollover()；
        // record.usage() 记成 new Tally(1, 输入, 输出, 有估算 ? 1 : 0)，用 merge(键, tally, Tally::plus) 合进
        // byCaller（调用方）、byIntent（意图，null 记 NO_INTENT）、
        // byConversation（有会话 ID 时，键是「调用方/会话 ID」）；今天超预算了记一行 info 日志
    }

    /**
     * 这个调用方今天用了多少 Token（ch09-5 之二）：先 {@link #rollover}，再查按调用方的表，没有记录是 0。
     *
     * @param caller 调用方
     * @return 今天的输入 + 输出 Token
     */
    public synchronized long usedToday(String caller) {
        // TODO ch09-5：rollover()，再从 byCaller 里取这个调用方的 tokens()，没有记录是 0
        return 0;
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
    public synchronized Summary summary(int top) {
        rollover();
        Tally total = byCaller.values().stream().reduce(Tally.ZERO, Tally::plus);
        return new Summary(
                day.toString(),
                properties.dailyTokenBudget(),
                total,
                rows(byIntent, byIntent.size()),
                rows(byCaller, top),
                rows(byConversation, top));
    }

    /** 日期变了就清空（预算按天算）。调用方已经持有锁。 */
    private void rollover() {
        LocalDate today = today();
        if (!today.equals(day)) {
            log.info("Token 总账：{} 结束，清零（{} 个调用方）", day, byCaller.size());
            day = today;
            byCaller.clear();
            byIntent.clear();
            byConversation.clear();
        }
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), properties.zone());
    }

    private static List<Row> rows(Map<String, Tally> table, int limit) {
        return table.entrySet().stream()
                .sorted(Comparator.comparingLong(
                                (Map.Entry<String, Tally> e) -> e.getValue().tokens())
                        .reversed())
                .limit(limit)
                .map(e -> new Row(e.getKey(), e.getValue()))
                .toList();
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
