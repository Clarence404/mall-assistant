package io.github.clarence404.assistant.ops;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token 总账的内存实现：三张表（按调用方、意图、会话），只存一天，日期变了就清空（第 9 章 {@link UsageLedger} 里的做法，第 10 章挪到这里）。
 *
 * <p>所在层：上线运维。{@code assistant.state.store=memory}（默认）时用。重启就没了，也不跨实例：部署两个实例，同一个用户在两边各有一份预算。
 */
public final class MemoryUsageStore implements UsageStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryUsageStore.class);

    // 下面这些只在 synchronized 方法里读写：一轮记一次账，量很小，一把锁足够
    private LocalDate day;
    private final Map<String, UsageLedger.Tally> byCaller = new HashMap<>();
    private final Map<String, UsageLedger.Tally> byIntent = new HashMap<>();
    private final Map<String, UsageLedger.Tally> byConversation;

    /**
     * 创建。
     *
     * @param maxConversations 按会话汇总时最多记多少个会话（超了丢最早的），防止内存无限增长
     */
    public MemoryUsageStore(int maxConversations) {
        // 按插入顺序、超过上限删最早的（LinkedHashMap 的 removeEldestEntry）
        this.byConversation = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, UsageLedger.Tally> eldest) {
                return size() > maxConversations;
            }
        };
    }

    @Override
    public synchronized long add(LocalDate today, Entry entry) {
        rollover(today);
        byCaller.merge(entry.caller(), entry.tally(), UsageLedger.Tally::plus);
        byIntent.merge(entry.intent(), entry.tally(), UsageLedger.Tally::plus);
        if (entry.conversationKey() != null) {
            byConversation.merge(entry.conversationKey(), entry.tally(), UsageLedger.Tally::plus);
        }
        return byCaller.get(entry.caller()).tokens();
    }

    @Override
    public synchronized long tokens(LocalDate today, String caller) {
        rollover(today);
        UsageLedger.Tally tally = byCaller.get(caller);
        return tally == null ? 0 : tally.tokens();
    }

    @Override
    public synchronized Totals summary(LocalDate today, int top) {
        rollover(today);
        UsageLedger.Tally total = byCaller.values().stream().reduce(UsageLedger.Tally.ZERO, UsageLedger.Tally::plus);
        return new Totals(total, rows(byIntent, byIntent.size()), rows(byCaller, top), rows(byConversation, top));
    }

    /** 日期变了就清空（预算按天算）。调用方已经持有锁。 */
    private void rollover(LocalDate today) {
        if (day == null) {
            day = today;
        } else if (!today.equals(day)) {
            log.info("Token 总账：{} 结束，清零（{} 个调用方）", day, byCaller.size());
            day = today;
            byCaller.clear();
            byIntent.clear();
            byConversation.clear();
        }
    }

    /** 按用量从多到少取前几行。 */
    static List<UsageLedger.Row> rows(Map<String, UsageLedger.Tally> table, int limit) {
        return table.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, UsageLedger.Tally> e) ->
                                e.getValue().tokens())
                        .reversed())
                .limit(limit)
                .map(e -> new UsageLedger.Row(e.getKey(), e.getValue()))
                .toList();
    }
}
