package io.github.clarence404.assistant.simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 按权重随机挑一个：权重 55 的被挑中的概率是权重 11 的 5 倍。权重 ≤ 0 的永远不会被挑中（配置里写 0 就等于关掉这种行为）。
 *
 * <p>做法：把权重依次累加成一条数轴（[0,55)、[55,73)……），在 [0, 总权重) 里取一个随机数，看它落在哪一段。
 *
 * @param <T> 候选项类型
 */
public final class WeightedPicker<T> {

    private final List<T> items = new ArrayList<>();
    private final double[] cumulative;
    private final double total;

    /**
     * 创建。
     *
     * @param weights 候选项 → 权重；按 Map 的遍历顺序排在数轴上
     * @throws IllegalArgumentException 没有任何权重 > 0 的候选项
     */
    public WeightedPicker(Map<T, Double> weights) {
        List<Double> sums = new ArrayList<>();
        double sum = 0;
        for (Map.Entry<T, Double> e : weights.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                sum += e.getValue();
                items.add(e.getKey());
                sums.add(sum);
            }
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("至少要有一个权重大于 0 的候选项");
        }
        this.cumulative = sums.stream().mapToDouble(Double::doubleValue).toArray();
        this.total = sum;
    }

    /**
     * 挑一个。
     *
     * @param random 随机源（测试里传固定种子）
     * @return 选中的候选项
     */
    public T pick(RandomGenerator random) {
        double r = random.nextDouble(total);
        for (int i = 0; i < cumulative.length; i++) {
            if (r < cumulative[i]) {
                return items.get(i);
            }
        }
        return items.getLast(); // 浮点误差兜底
    }

    /**
     * 某一项被挑中的概率（打印启动信息、测试用）。
     *
     * @param item 候选项
     * @return 0～1；不在候选里返回 0
     */
    public double probability(T item) {
        int i = items.indexOf(item);
        if (i < 0) {
            return 0;
        }
        double weight = cumulative[i] - (i == 0 ? 0 : cumulative[i - 1]);
        return weight / total;
    }
}
