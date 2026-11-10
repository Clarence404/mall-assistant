package io.github.clarence404.assistant.eval;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一次评测的报告：被测模型、评测集版本、汇总指标、逐条结果（第 9 章引入）。
 *
 * <p>所在层：评测的数据。{@link EvalRunner#run} 生成；测试把它写成 JSON（{@code target/eval/report-*.json}，也是基线文件的格式）和 Markdown
 * （{@link #toMarkdown()}，CI 贴进运行摘要）。{@link EvalComparison} 拿两份报告比较。
 *
 * @param model 被测模型
 * @param evalSet 评测集名字
 * @param evalSetVersion 评测集版本（内容的哈希）
 * @param startedAt 开始时间
 * @param summary 汇总
 * @param results 逐条结果，顺序同评测集
 */
public record EvalReport(
        String model,
        String evalSet,
        String evalSetVersion,
        String startedAt,
        Summary summary,
        List<EvalResult> results) {

    /**
     * 汇总指标。
     *
     * @param total 用例数
     * @param passed 通过数
     * @param passRate 通过率（0～1，保留 4 位小数）
     * @param categories 按类别：顺序同 {@link EvalCase.Category}，只列出现过的类别
     * @param p50Ms 耗时中位数（被判定那一轮），毫秒
     * @param p95Ms 耗时 95 分位，毫秒
     * @param maxMs 最长耗时，毫秒
     * @param inputTokens 输入 Token 合计
     * @param outputTokens 输出 Token 合计
     * @param estimated Token 里有没有估算的
     * @param judged 裁判打了分的用例数
     * @param judgeAverage 裁判的平均分（保留 2 位小数）；没有打分时为 null
     */
    public record Summary(
            int total,
            int passed,
            double passRate,
            Map<String, CategoryScore> categories,
            long p50Ms,
            long p95Ms,
            long maxMs,
            long inputTokens,
            long outputTokens,
            boolean estimated,
            int judged,
            Double judgeAverage) {}

    /**
     * 一个类别的得分。
     *
     * @param total 用例数
     * @param passed 通过数
     * @param passRate 通过率（0～1，保留 4 位小数）
     */
    public record CategoryScore(int total, int passed, double passRate) {}

    /**
     * 汇总逐条结果（ch09-10 之一）。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>总数、通过数、通过率（{@link #rate}：通过 / 总数，没有用例时为 0，保留 4 位小数）
     *   <li>按类别：按 {@link EvalCase.Category} 的声明顺序，只放出现过的类别，键是类别名（{@code name()}）
     *   <li>耗时：{@code durationMs} 的 p50、p95（{@link #percentile}，最近秩法）和最大值；没有用例时都是 0
     *   <li>Token：输入、输出分别求和；有一条是估算的 {@code estimated} 就为 true
     *   <li>裁判：{@code judgeScore} 不为 null 的条数与平均分（保留 2 位小数），一条都没有时平均分为 null
     * </ul>
     *
     * @param results 逐条结果
     * @return 汇总
     */
    public static Summary summarize(List<EvalResult> results) {
        // TODO ch09-10：总数、通过数、rate(通过, 总数)；
        // 按 EvalCase.Category.values() 的顺序算出现过的类别（LinkedHashMap，键是 name()）；
        // durationMs 排序后 percentile(…, 0.50 / 0.95) 和最大值；Token 求和、有一条估算就 true；
        // judgeScore 不为 null 的条数与平均分（保留 2 位小数，没有就 null）
        return new Summary(results.size(), 0, 0, Map.of(), 0, 0, 0, 0, 0, false, 0, null);
    }

    /**
     * 通过率：保留 4 位小数，总数为 0 时是 0。
     *
     * @param passed 通过数
     * @param total 总数
     * @return 0～1
     */
    static double rate(int passed, int total) {
        return total == 0 ? 0 : Math.round(passed * 10000.0 / total) / 10000.0;
    }

    /**
     * 最近秩法的分位数：排好序的 n 个数里取第 {@code ceil(q * n)} 个（从 1 数），空列表是 0。
     *
     * <p>例：10 个数的 p95 是第 10 个（ceil(9.5)），p50 是第 5 个。用例只有几十条，分位数本来就粗，最近秩法简单、结果一定是某个真实测到的值。
     *
     * @param sorted 从小到大排好的数
     * @param q 分位（0～1）
     * @return 分位数
     */
    static long percentile(List<Long> sorted, double q) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(q * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size(), rank) - 1));
    }

    /**
     * 写成 Markdown：一张总表、一张按类别的表、没通过的用例与原因。CI 把它贴进运行摘要。
     *
     * @return Markdown 文本
     */
    public String toMarkdown() {
        StringBuilder md = new StringBuilder();
        md.append("## 评测报告：").append(model).append("\n\n");
        md.append("评测集 `")
                .append(evalSet)
                .append("`（版本 ")
                .append(evalSetVersion)
                .append("），开始于 ")
                .append(startedAt)
                .append("\n\n");
        md.append("| 用例 | 通过 | 通过率 | 耗时 p50 / p95 / 最长 | 输入 / 输出 Token | 裁判平均分 |\n");
        md.append("|---|---|---|---|---|---|\n");
        md.append("| ")
                .append(summary.total())
                .append(" | ")
                .append(summary.passed())
                .append(" | ")
                .append(percent(summary.passRate()))
                .append(" | ")
                .append(summary.p50Ms())
                .append(" / ")
                .append(summary.p95Ms())
                .append(" / ")
                .append(summary.maxMs())
                .append(" ms | ")
                .append(summary.inputTokens())
                .append(" / ")
                .append(summary.outputTokens())
                .append(summary.estimated() ? "（含估算）" : "")
                .append(" | ")
                .append(summary.judgeAverage() == null ? "-" : summary.judgeAverage() + "（" + summary.judged() + " 条）")
                .append(" |\n\n");
        md.append("| 类别 | 用例 | 通过 | 通过率 |\n|---|---|---|---|\n");
        summary.categories()
                .forEach((name, score) -> md.append("| ")
                        .append(name.toLowerCase(Locale.ROOT))
                        .append(" | ")
                        .append(score.total())
                        .append(" | ")
                        .append(score.passed())
                        .append(" | ")
                        .append(percent(score.passRate()))
                        .append(" |\n"));
        List<EvalResult> failed = results.stream().filter(r -> !r.passed()).toList();
        if (!failed.isEmpty()) {
            md.append("\n没通过的用例：\n\n");
            for (EvalResult r : failed) {
                md.append("- `")
                        .append(r.id())
                        .append("`：")
                        .append(String.join("；", r.failures()))
                        .append('\n');
            }
        }
        return md.toString();
    }

    private static String percent(double rate) {
        return String.format(Locale.ROOT, "%.1f%%", rate * 100);
    }
}
