package io.github.clarence404.assistant.eval;

import java.util.List;

/**
 * 一条用例的结果（第 9 章引入）。
 *
 * <p>所在层：评测的数据。{@link EvalRunner} 生成，{@link EvalReport} 汇总，报告和基线文件里逐条列出。
 *
 * @param id 用例编号
 * @param category 类别
 * @param passed 是否通过（{@code failures} 为空）
 * @param failures 没满足的期望，一条一句话；通过时为空
 * @param durationMs 最后一轮（被判定的那一句）的耗时，毫秒；多轮用例前几轮不算
 * @param inputTokens 这条用例所有轮次的输入 Token
 * @param outputTokens 这条用例所有轮次的输出 Token
 * @param estimated Token 里有没有估算的
 * @param judgeScore LLM 裁判给的分（1～5）；没请裁判或裁判失败时为 null
 * @param judgeReason 裁判的理由
 */
public record EvalResult(
        String id,
        EvalCase.Category category,
        boolean passed,
        List<String> failures,
        long durationMs,
        long inputTokens,
        long outputTokens,
        boolean estimated,
        Integer judgeScore,
        String judgeReason) {

    /** 紧凑构造器：失败原因为 null 时当作空列表。 */
    public EvalResult {
        failures = failures == null ? List.of() : List.copyOf(failures);
    }
}
