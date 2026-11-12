package io.github.clarence404.assistant.simulator;

import java.time.LocalTime;
import java.util.List;

/**
 * 按时段起伏的流量曲线：给出 0～23 点每个整点的相对流量，中间按分钟线性插值，再除以最大值归一化到 0～1，最后不低于 {@code minFactor}。
 *
 * <p>为什么用分段线性而不是正弦：真实电商一天有两个高峰（午休、晚上 8～9 点），一条正弦画不出来；24 个点直接写在配置里，想要什么形状改数字即可。
 *
 * <p>用法：两次提问之间的等待 = 配置的间隔 ÷ factor(当前模拟时刻)，夜里问得少、晚上问得多。
 */
public final class LoadCurve {

    private final double[] hourly;
    private final double minFactor;

    /**
     * 创建。
     *
     * @param hourly 24 个整点的相对流量，必须全部 ≥ 0 且至少一个 > 0
     * @param minFactor 下限（0～1），夜里也保留一点流量
     */
    public LoadCurve(List<Double> hourly, double minFactor) {
        if (hourly == null || hourly.size() != 24) {
            throw new IllegalArgumentException("simulator.curve.hourly 要正好 24 个数（0～23 点）");
        }
        double max = hourly.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        if (max <= 0 || hourly.stream().anyMatch(v -> v < 0)) {
            throw new IllegalArgumentException("simulator.curve.hourly 不能有负数，且至少一个大于 0");
        }
        this.hourly = hourly.stream().mapToDouble(v -> v / max).toArray(); // 归一化：最高点 = 1
        this.minFactor = Math.clamp(minFactor, 0.0, 1.0);
    }

    /**
     * 某个时刻的流量系数。
     *
     * @param time 模拟时刻（只看时分秒）
     * @return 0～1 之间，且不低于 minFactor
     */
    public double factor(LocalTime time) {
        int hour = time.getHour();
        double fraction = (time.getMinute() * 60 + time.getSecond()) / 3600.0; // 这一小时走了多少
        double from = hourly[hour];
        double to = hourly[(hour + 1) % 24]; // 23 点之后接回 0 点
        double value = from + (to - from) * fraction;
        return Math.max(minFactor, value);
    }

    /**
     * 这个时刻应该有几个虚拟用户在线：按系数折算并四舍五入，至少 1 个（一直有数据）。
     *
     * @param time 模拟时刻
     * @param maxShoppers 最高峰时的人数
     * @return 1～maxShoppers
     */
    public int activeShoppers(LocalTime time, int maxShoppers) {
        return Math.clamp(Math.round(maxShoppers * factor(time)), 1, Math.max(1, maxShoppers));
    }
}
