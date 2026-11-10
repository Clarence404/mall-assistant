package io.github.clarence404.assistant.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 和基线比：这次的评测结果有没有退化（第 9 章引入）。
 *
 * <p>所在层：评测。{@code EvalGateTest}（CI 门禁）和 {@code EvalIT}（真实模型评测）拿这次的 {@link EvalReport} 和仓库里的基线
 * （{@code src/test/resources/eval/baseline-*.json}，上一次认可的结果）比，{@link #passed()} 为 false 就让构建失败。
 *
 * <p><b>为什么要「和基线比」而不是「通过率必须 100%」</b>：小模型本来就会错（1.5b 的意图识别也只分对八九成），要求全对等于永远红；真正要防的是
 * 「改了代码 / 提示词 / 模型之后，比原来差了」。所以门禁看的是<b>变化</b>：总通过率掉了超过阈值、某个类别掉了超过阈值，就算退化；
 * 原来过、现在不过的用例逐条列出来，方便定位。阈值留了余量，因为真实模型每次跑结果会有小波动（温度不是 0 的那几步）。
 *
 * @param passed 没有退化
 * @param regressions 退化的说明（总通过率、类别、评测集版本不同），一条一句话
 * @param newlyFailing 基线里通过、这次没通过的用例编号
 * @param newlyPassing 基线里没通过、这次通过了的用例编号（变好了也列出来：说明基线该更新了）
 */
public record EvalComparison(
        boolean passed, List<String> regressions, List<String> newlyFailing, List<String> newlyPassing) {

    /** 浮点比较的容差：0.95 - 0.9 不一定正好等于 0.05。 */
    private static final double EPSILON = 1e-9;

    /**
     * 阈值。
     *
     * @param maxPassRateDrop 总通过率最多允许掉多少（0.05 = 5 个百分点）
     * @param maxCategoryDrop 每个类别的通过率最多允许掉多少（类别里用例少，一条就是十几个点，阈值要比总的宽）
     */
    public record Thresholds(double maxPassRateDrop, double maxCategoryDrop) {

        /**
         * 默认：总的 5 个点、每类 20 个点。
         *
         * @return 阈值
         */
        public static Thresholds defaults() {
            return new Thresholds(0.05, 0.20);
        }
    }

    /**
     * 比较（ch09-10 之二）。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>评测集版本不同：加一条退化说明（「评测集版本不同…先用同一份评测集重新生成基线」），不再比通过率（比了也没意义），但照样列出用例的变化
     *   <li>总通过率：{@code 基线 - 这次 > maxPassRateDrop}（加 {@link #EPSILON} 容差）就加一条说明，写出两个百分比
     *   <li>每个类别（以基线里有的为准）：这次没有这个类别按 0 算；掉得超过 {@code maxCategoryDrop} 加一条说明
     *   <li>按用例编号对：基线通过、这次不通过的进 {@code newlyFailing}；反过来的进 {@code newlyPassing}；只在一边有的不算
     *   <li>{@code passed} = 没有任何退化说明（{@code newlyFailing} 不为空但没超阈值时仍然算通过，只是列出来）
     * </ul>
     *
     * @param baseline 基线
     * @param current 这次
     * @param thresholds 阈值
     * @return 比较结果
     */
    public static EvalComparison compare(EvalReport baseline, EvalReport current, Thresholds thresholds) {
        List<String> regressions = new ArrayList<>();
        if (!baseline.evalSetVersion().equals(current.evalSetVersion())) {
            regressions.add("评测集版本不同（基线 " + baseline.evalSetVersion() + "，这次 " + current.evalSetVersion()
                    + "）：通过率没法比，先用同一份评测集重新生成基线");
        } else {
            double drop = baseline.summary().passRate() - current.summary().passRate();
            if (drop > thresholds.maxPassRateDrop() + EPSILON) {
                regressions.add("总通过率从 " + percent(baseline.summary().passRate()) + " 掉到 "
                        + percent(current.summary().passRate()) + "，超过允许的 " + percent(thresholds.maxPassRateDrop()));
            }
            Map<String, EvalReport.CategoryScore> now = current.summary().categories();
            baseline.summary().categories().forEach((name, before) -> {
                EvalReport.CategoryScore after = now.get(name);
                double rate = after == null ? 0 : after.passRate();
                if (before.passRate() - rate > thresholds.maxCategoryDrop() + EPSILON) {
                    regressions.add("类别 " + name.toLowerCase(Locale.ROOT) + " 的通过率从 " + percent(before.passRate())
                            + " 掉到 " + percent(rate) + "，超过允许的 " + percent(thresholds.maxCategoryDrop()));
                }
            });
        }
        Map<String, Boolean> before = byId(baseline);
        Map<String, Boolean> after = byId(current);
        List<String> newlyFailing = new ArrayList<>();
        List<String> newlyPassing = new ArrayList<>();
        after.forEach((id, ok) -> {
            Boolean was = before.get(id);
            if (Boolean.TRUE.equals(was) && !ok) {
                newlyFailing.add(id);
            } else if (Boolean.FALSE.equals(was) && ok) {
                newlyPassing.add(id);
            }
        });
        return new EvalComparison(
                regressions.isEmpty(), List.copyOf(regressions), List.copyOf(newlyFailing), List.copyOf(newlyPassing));
    }

    /**
     * 写成给人看的几行（失败时的断言消息、CI 摘要）。
     *
     * @return 文字
     */
    public String describe() {
        StringBuilder text = new StringBuilder(passed ? "和基线比：没有退化" : "和基线比：有退化");
        regressions.forEach(r -> text.append("\n- ").append(r));
        if (!newlyFailing.isEmpty()) {
            text.append("\n- 原来通过、这次没通过：").append(newlyFailing);
        }
        if (!newlyPassing.isEmpty()) {
            text.append("\n- 原来没通过、这次通过了（可以考虑更新基线）：").append(newlyPassing);
        }
        return text.toString();
    }

    private static Map<String, Boolean> byId(EvalReport report) {
        return report.results().stream()
                .collect(Collectors.toMap(EvalResult::id, EvalResult::passed, (a, b) -> a, LinkedHashMap::new));
    }

    private static String percent(double rate) {
        return String.format(Locale.ROOT, "%.1f%%", rate * 100);
    }
}
