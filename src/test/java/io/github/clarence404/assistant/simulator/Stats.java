package io.github.clarence404.assistant.simulator;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 统计：按名字计数，每个计数有「本周期」和「累计」两份，每分钟打印一行后把本周期清零。名字用中文直接打印，如「提问」「成功」「RATE_LIMITED」。
 */
final class Stats {

    private final Map<String, LongAdder> interval = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> total = new ConcurrentHashMap<>();

    void add(String name, long n) {
        interval.computeIfAbsent(name, k -> new LongAdder()).add(n);
        total.computeIfAbsent(name, k -> new LongAdder()).add(n);
    }

    void inc(String name) {
        add(name, 1);
    }

    /**
     * 取出本周期的计数并清零（为 0 的不出现）。
     *
     * @return 名字 → 值，按名字排序
     */
    Map<String, Long> drainInterval() {
        Map<String, Long> out = new TreeMap<>();
        interval.forEach((k, v) -> {
            long n = v.sumThenReset();
            if (n != 0) {
                out.put(k, n);
            }
        });
        return out;
    }

    long total(String name) {
        LongAdder a = total.get(name);
        return a == null ? 0 : a.sum();
    }
}
