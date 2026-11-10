package io.github.clarence404.assistant.ops;

/**
 * 一次模型调用用了多少 Token（第 9 章引入）。
 *
 * <p>所在层：上线运维（{@code ops} 包）的数据。{@link UsageAdvisor} 从模型的响应里取（Ollama 的 {@code prompt_eval_count} / {@code eval_count}、
 * OpenAI 的 {@code usage}、Claude CLI 的 {@code usage}），取不到时用 {@link TokenEstimator} 估算，并把 {@code estimated} 标成 true。
 *
 * <p>Token 是模型计费和上下文长度的单位：模型不是按「字」读文本，而是先切成 Token（常见词一个、生僻字可能拆成几个）。输入（提示词：系统消息 + 历史 +
 * 资料 + 问题）和输出（回答）分开算，因为大多数厂商两者单价不同，输出通常贵几倍。
 *
 * @param input 输入 Token（提示词）
 * @param output 输出 Token（回答）
 * @param estimated 是不是估算的（模型没报告用量时）
 */
public record TokenUsage(int input, int output, boolean estimated) {

    /** 没有用量（调用失败、还没调用）。 */
    public static final TokenUsage NONE = new TokenUsage(0, 0, false);

    /** 输入 + 输出。 */
    public int total() {
        return input + output;
    }
}
