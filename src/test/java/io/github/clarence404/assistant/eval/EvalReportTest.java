package io.github.clarence404.assistant.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 第 9 章 TODO ch09-10：评测报告的汇总（通过率、各类别、耗时分位、Token、裁判分）与基线对比。
 */
class EvalReportTest {

    @Test
    void summarizeCountsRatesAndOrdersCategories() {
        List<EvalResult> results = List.of(
                result("rag-01", EvalCase.Category.RAG, true, 100, 300, 50, false, 5),
                result("rag-02", EvalCase.Category.RAG, false, 200, 300, 50, true, 3),
                result("intent-01", EvalCase.Category.INTENT, true, 50, 100, 10, false, null),
                result("agent-01", EvalCase.Category.AGENT, true, 900, 2000, 200, false, null));

        EvalReport.Summary s = EvalReport.summarize(results);

        assertThat(s.total()).isEqualTo(4);
        assertThat(s.passed()).isEqualTo(3);
        assertThat(s.passRate()).isEqualTo(0.75);
        assertThat(s.categories().keySet()).containsExactly("INTENT", "RAG", "AGENT");
        assertThat(s.categories().get("RAG")).isEqualTo(new EvalReport.CategoryScore(2, 1, 0.5));
        assertThat(s.p50Ms()).isEqualTo(100);
        assertThat(s.p95Ms()).isEqualTo(900);
        assertThat(s.maxMs()).isEqualTo(900);
        assertThat(s.inputTokens()).isEqualTo(2700);
        assertThat(s.outputTokens()).isEqualTo(310);
        assertThat(s.estimated()).isTrue();
        assertThat(s.judged()).isEqualTo(2);
        assertThat(s.judgeAverage()).isEqualTo(4.0);
    }

    @Test
    void emptyResults() {
        EvalReport.Summary s = EvalReport.summarize(List.of());

        assertThat(s.total()).isZero();
        assertThat(s.passRate()).isZero();
        assertThat(s.categories()).isEmpty();
        assertThat(s.p95Ms()).isZero();
        assertThat(s.judgeAverage()).isNull();
    }

    @Test
    void rateKeepsFourDecimals() {
        assertThat(EvalReport.rate(2, 3)).isEqualTo(0.6667);
        assertThat(EvalReport.rate(0, 0)).isZero();
    }

    @Test
    void percentileUsesNearestRank() {
        List<Long> ten = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);

        assertThat(EvalReport.percentile(ten, 0.5)).isEqualTo(5);
        assertThat(EvalReport.percentile(ten, 0.95)).isEqualTo(10);
        assertThat(EvalReport.percentile(List.of(42L), 0.95)).isEqualTo(42);
        assertThat(EvalReport.percentile(List.of(), 0.5)).isZero();
    }

    @Test
    void markdownListsTotalsCategoriesAndFailures() {
        EvalReport report = report(
                "v1",
                result("rag-01", EvalCase.Category.RAG, true, 100, 300, 50, false, null),
                failed("rag-02", EvalCase.Category.RAG, "资料里没有 returns.md「适用条件」"));

        String md = report.toMarkdown();

        assertThat(md)
                .contains("## 评测报告：qwen2.5:1.5b")
                .contains("| 2 | 1 | 50.0% |")
                .contains("| rag | 2 | 1 | 50.0% |")
                .contains("- `rag-02`：资料里没有 returns.md「适用条件」");
    }

    @Test
    void sameResultsAreNotARegression() {
        EvalReport baseline = report("v1", ok("a"), ok("b"), failed("c"));

        EvalComparison comparison =
                EvalComparison.compare(baseline, report("v1", ok("a"), ok("b"), failed("c")), thresholds());

        assertThat(comparison.passed()).isTrue();
        assertThat(comparison.regressions()).isEmpty();
        assertThat(comparison.newlyFailing()).isEmpty();
        assertThat(comparison.describe()).startsWith("和基线比：没有退化");
    }

    @Test
    void passRateDropBeyondThresholdIsARegression() {
        EvalReport baseline = report("v1", ok("a"), ok("b"), ok("c"), ok("d"));
        EvalReport current = report("v1", ok("a"), ok("b"), ok("c"), failed("d"));

        EvalComparison comparison = EvalComparison.compare(baseline, current, thresholds());

        assertThat(comparison.passed()).isFalse();
        assertThat(comparison.regressions())
                .anySatisfy(r -> assertThat(r).contains("总通过率从 100.0% 掉到 75.0%"))
                .anySatisfy(r -> assertThat(r).contains("类别 rag 的通过率从 100.0% 掉到 75.0%"));
        assertThat(comparison.newlyFailing()).containsExactly("d");
    }

    @Test
    void dropWithinThresholdPassesButIsListed() {
        EvalReport baseline = report("v1", ok("a"), ok("b"), ok("c"), ok("d"));
        EvalReport current = report("v1", ok("a"), ok("b"), ok("c"), failed("d"));

        EvalComparison comparison =
                EvalComparison.compare(baseline, current, new EvalComparison.Thresholds(0.25, 0.25));

        assertThat(comparison.passed()).isTrue();
        assertThat(comparison.newlyFailing()).containsExactly("d");
    }

    @Test
    void missingCategoryCountsAsZero() {
        EvalReport baseline =
                report("v1", ok("a"), result("i-1", EvalCase.Category.INTENT, true, 1, 0, 0, false, null));
        EvalReport current = report("v1", ok("a"));

        EvalComparison comparison = EvalComparison.compare(baseline, current, new EvalComparison.Thresholds(1.0, 0.5));

        assertThat(comparison.regressions()).singleElement().asString().contains("类别 intent 的通过率从 100.0% 掉到 0.0%");
    }

    @Test
    void improvementsAreListed() {
        EvalComparison comparison =
                EvalComparison.compare(report("v1", failed("a")), report("v1", ok("a")), thresholds());

        assertThat(comparison.passed()).isTrue();
        assertThat(comparison.newlyPassing()).containsExactly("a");
    }

    @Test
    void differentEvalSetVersionCannotBeCompared() {
        EvalComparison comparison = EvalComparison.compare(report("v1", ok("a")), report("v2", ok("a")), thresholds());

        assertThat(comparison.passed()).isFalse();
        assertThat(comparison.regressions()).singleElement().asString().contains("评测集版本不同");
    }

    private static EvalComparison.Thresholds thresholds() {
        return new EvalComparison.Thresholds(0.05, 0.10);
    }

    static EvalReport report(String version, EvalResult... results) {
        List<EvalResult> list = List.of(results);
        return new EvalReport(
                "qwen2.5:1.5b", "cases-v1.jsonl", version, "2026-11-10T00:00:00Z", EvalReport.summarize(list), list);
    }

    static EvalResult ok(String id) {
        return result(id, EvalCase.Category.RAG, true, 10, 100, 10, false, null);
    }

    static EvalResult failed(String id) {
        return failed(id, EvalCase.Category.RAG, "不通过");
    }

    static EvalResult failed(String id, EvalCase.Category category, String why) {
        return new EvalResult(id, category, false, List.of(why), 10, 100, 10, false, null, null);
    }

    static EvalResult result(
            String id,
            EvalCase.Category category,
            boolean passed,
            long ms,
            long in,
            long out,
            boolean estimated,
            Integer score) {
        return new EvalResult(
                id, category, passed, passed ? List.of() : List.of("不通过"), ms, in, out, estimated, score, null);
    }
}
