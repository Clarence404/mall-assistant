package io.github.clarence404.assistant.ops;

import java.util.List;

/**
 * 一轮对话的 Token 用量，给页面调试面板看（第 9 章引入）。
 *
 * <p>所在层：接口层的数据。阻塞接口放在响应的 {@code usage} 字段，流式接口是 done 之前的一个 {@code usage} 事件。
 *
 * @param calls 这一轮的每次模型调用，按时间顺序
 * @param inputTokens 输入 Token 合计
 * @param outputTokens 输出 Token 合计
 * @param estimated 有没有估算的（只要有一次是估算的就为 true）
 * @param todayTokens 这个调用方今天一共用了多少（含这一轮）；不知道时为 null
 * @param dailyBudget 每日预算；0 表示不限，不知道时为 null
 */
public record UsageTrace(
        List<ModelCall> calls,
        int inputTokens,
        int outputTokens,
        boolean estimated,
        Long todayTokens,
        Long dailyBudget) {

    /**
     * 由一组调用算合计。
     *
     * @param calls 调用
     * @return 用量，今日用量与预算为 null
     */
    public static UsageTrace of(List<ModelCall> calls) {
        int input = 0;
        int output = 0;
        boolean estimated = false;
        for (ModelCall call : calls) {
            input += call.inputTokens();
            output += call.outputTokens();
            estimated |= call.estimated();
        }
        return new UsageTrace(List.copyOf(calls), input, output, estimated, null, null);
    }

    /**
     * 补上今日用量与预算（{@code ChatController} 记完账之后）。
     *
     * @param today 今天一共用了多少
     * @param budget 每日预算，0 = 不限
     * @return 新的用量
     */
    public UsageTrace withBudget(long today, long budget) {
        return new UsageTrace(calls, inputTokens, outputTokens, estimated, today, budget);
    }

    /** 输入 + 输出。 */
    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}
