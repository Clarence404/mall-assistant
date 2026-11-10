package io.github.clarence404.assistant.ops;

/**
 * 一轮对话里的一次模型调用：哪一步、用了多少 Token、花了多久（第 9 章引入）。
 *
 * <p>所在层：上线运维的数据。{@link UsageAdvisor} 每次调用完生成一个，加进这一轮的 {@link TurnRecord}；阻塞接口在响应的 {@code usage.calls} 里、
 * 流式接口在最后的 {@code usage} 事件里，页面调试面板「Token」一栏逐行显示。
 *
 * @param stage 哪一步：{@code intent}（意图识别）、{@code condense}（追问改写）、{@code answer}（第 3 章的回答，含工具调用的几轮）、
 *     {@code rag}（带资料的回答）、{@code agent}（Agent 的一步）、{@code summary}（摘要记忆）、{@code other}（没标的）
 * @param model 模型名（响应里带的；没有时为 null）
 * @param inputTokens 输入 Token
 * @param outputTokens 输出 Token
 * @param estimated 是否估算
 * @param durationMs 耗时，毫秒
 */
public record ModelCall(
        String stage, String model, int inputTokens, int outputTokens, boolean estimated, long durationMs) {

    /** 输入 + 输出。 */
    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}
