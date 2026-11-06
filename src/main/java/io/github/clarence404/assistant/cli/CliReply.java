package io.github.clarence404.assistant.cli;

/**
 * 一次阻塞调用的结果：方言从 stdout 里解析出来（{@link CliDialect#parseReply}），{@link CliChatModel} 再把它包成 Spring AI 的
 * {@code ChatResponse}（文本 + 模型名 + token 用量元数据）。
 *
 * <p>架构层：命令行模型。不属于某一章的练习，是第 2 章之后加的可选扩展（{@code claude-cli} / {@code codex-cli} profile 才启用）。
 *
 * <p>token：模型计费和上下文长度的单位，大致是一个词片段。输入 token 包括系统提示词和用户消息，所以提示词越长每次调用越贵；
 * 这里记下来只用于日志。
 *
 * @param text 回答文本；结构化输出时为 JSON 字符串
 * @param model 工具报告的实际模型名，可能为 null
 * @param inputTokens 输入 token 数，未知时为 null
 * @param outputTokens 输出 token 数，未知时为 null
 * @param costUsd 工具报告的费用（美元），未知时为 null
 */
public record CliReply(String text, String model, Integer inputTokens, Integer outputTokens, Double costUsd) {

    /** 只有文本、其余信息未知的结果。 */
    public static CliReply text(String text) {
        return new CliReply(text, null, null, null, null);
    }
}
