package io.github.clarence404.assistant.state;

import io.github.clarence404.assistant.ops.UsageLedger;
import io.github.clarence404.assistant.ops.UsageStore;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Token 总账的 Redis 实现：几个实例共用一份账（第 10 章引入）。
 *
 * <p>所在层：共享状态。{@code assistant.state.store=redis} 时注入 {@code ops.UsageLedger}。一天的账是几个键（键里带日期，两天后过期，
 * 不用像内存版那样在零点清空）：
 *
 * <ul>
 *   <li>{@code usage:{日期}:caller} / {@code :intent} / {@code :conversation} / {@code :total}：哈希，字段是「成员|turns / in / out / estimated」，
 *       用 {@code HINCRBY} 累加——Redis 的整数自增是原子的，两个实例同时加不会丢
 *   <li>{@code usage:{日期}:caller-rank} / {@code :conversation-rank}：有序集合，分数是 Token，{@code ZINCRBY} 累加，「用得最多的前 N 个」就是一条
 *       {@code ZREVRANGE}；每日预算查的也是它（{@code ZSCORE}，一条命令）
 * </ul>
 *
 * <p>一轮要改的六个键写在一段 Lua 脚本里（{@code redis/usage-add.lua}）一次执行完，汇总时不会读到「半轮」。
 *
 * <p>和内存版的两点不同：会话维度没有条数上限（内存版最多 500 个会话），靠两天过期兜底；汇总里的会话、调用方只取排名前 N 的，不用把整张表读回来。
 */
public final class RedisUsageStore implements UsageStore {

    private static final RedisScript<Long> ADD = RedisKeys.script("usage-add.lua", Long.class);
    private static final String COMPONENT = "usage";
    /** 账只需要今天的，键留两天（跨零点那一刻前后两天都在）。 */
    static final Duration KEEP = Duration.ofDays(2);

    private final StringRedisTemplate redis;
    private final RedisKeys keys;
    private final StateFailures failures;

    /**
     * 创建。
     *
     * @param redis Redis 客户端
     * @param keys 键前缀
     * @param failures 出错时怎么办
     */
    public RedisUsageStore(StringRedisTemplate redis, RedisKeys keys, StateFailures failures) {
        this.redis = redis;
        this.keys = keys;
        this.failures = failures;
    }

    /**
     * 记一轮（ch10-3 之一）。
     *
     * <p>要求（测试按这个写）：执行 {@link #ADD} 脚本，KEYS 依次是 {@link #key} 的 caller、intent、conversation、caller-rank、conversation-rank、total，
     * ARGV 依次是调用方、意图、会话键（没有会话传空字符串）、turns、输入、输出、估算轮数、{@link #KEEP} 的毫秒数；返回脚本给的「这个调用方当天的 Token」。
     * Redis 出错（含冷却期）：{@code failures.record} 记下来、返回 0——回答已经给出去了，记不上账只能认了（指标上看得到）。
     */
    @Override
    public long add(LocalDate day, Entry entry) {
        UsageLedger.Tally t = entry.tally();
        try {
            failures.ensureAvailable();
            Long used = redis.execute(
                    ADD,
                    List.of(
                            key(day, "caller"),
                            key(day, "intent"),
                            key(day, "conversation"),
                            key(day, "caller-rank"),
                            key(day, "conversation-rank"),
                            key(day, "total")),
                    entry.caller(),
                    entry.intent(),
                    entry.conversationKey() == null ? "" : entry.conversationKey(),
                    String.valueOf(t.turns()),
                    String.valueOf(t.inputTokens()),
                    String.valueOf(t.outputTokens()),
                    String.valueOf(t.estimatedTurns()),
                    String.valueOf(KEEP.toMillis()));
            return used == null ? 0 : used;
        } catch (DataAccessException e) {
            failures.record(COMPONENT, e);
            return 0;
        }
    }

    /**
     * 这个调用方当天用了多少（ch10-3 之二）。
     *
     * <p>要求（测试按这个写）：{@code ZSCORE caller-rank 调用方}，没有是 0。Redis 出错（含冷却期）：{@code failures.allowOrThrow}——
     * 放行策略返回 0（当作今天还没用，预算这一关放行），拒绝策略抛异常（对话接口 503）。
     */
    @Override
    public long tokens(LocalDate day, String caller) {
        try {
            failures.ensureAvailable();
            Double score = redis.opsForZSet().score(key(day, "caller-rank"), caller);
            return score == null ? 0 : score.longValue();
        } catch (DataAccessException e) {
            failures.allowOrThrow(COMPONENT, e);
            return 0;
        }
    }

    @Override
    public Totals summary(LocalDate day, int top) {
        try {
            failures.ensureAvailable();
            UsageLedger.Tally total = tallies(key(day, "total")).getOrDefault("all", UsageLedger.Tally.ZERO);
            List<UsageLedger.Row> byIntent = new ArrayList<>();
            tallies(key(day, "intent")).forEach((k, v) -> byIntent.add(new UsageLedger.Row(k, v)));
            byIntent.sort(
                    Comparator.comparingLong((UsageLedger.Row r) -> r.usage().tokens())
                            .reversed());
            return new Totals(
                    total,
                    byIntent,
                    topRows(key(day, "caller-rank"), key(day, "caller"), top),
                    topRows(key(day, "conversation-rank"), key(day, "conversation"), top));
        } catch (DataAccessException e) {
            failures.record(COMPONENT, e);
            return Totals.EMPTY;
        }
    }

    /** 排名前 N 的成员，各自的明细从哈希里取。 */
    private List<UsageLedger.Row> topRows(String rankKey, String hashKey, int top) {
        Set<String> members = redis.opsForZSet().reverseRange(rankKey, 0, top - 1L);
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        Map<String, UsageLedger.Tally> all = tallies(hashKey);
        return members.stream()
                .map(m -> new UsageLedger.Row(m, all.getOrDefault(m, UsageLedger.Tally.ZERO)))
                .toList();
    }

    /** 读一个「成员|项目」哈希，拼回每个成员的 {@link UsageLedger.Tally}。 */
    private Map<String, UsageLedger.Tally> tallies(String hashKey) {
        Map<Object, Object> raw = redis.opsForHash().entries(hashKey);
        Map<String, long[]> parts = new LinkedHashMap<>();
        raw.forEach((field, value) -> {
            String f = field.toString();
            int bar = f.lastIndexOf('|');
            if (bar < 0) {
                return;
            }
            long[] p = parts.computeIfAbsent(f.substring(0, bar), k -> new long[4]);
            long n = Long.parseLong(value.toString());
            switch (f.substring(bar + 1)) {
                case "turns" -> p[0] = n;
                case "in" -> p[1] = n;
                case "out" -> p[2] = n;
                case "estimated" -> p[3] = n;
                default -> {
                    // 不认识的字段（别的版本写的）忽略
                }
            }
        });
        Map<String, UsageLedger.Tally> result = new LinkedHashMap<>();
        parts.forEach((k, p) -> result.put(k, new UsageLedger.Tally(p[0], p[1], p[2], p[3])));
        return result;
    }

    /** 一天的某个键：{@code 前缀usage:{日期}:用途}（花括号见 {@link RedisKeys}）。 */
    private String key(LocalDate day, String what) {
        return keys.of("usage", "{" + day + "}", what);
    }
}
