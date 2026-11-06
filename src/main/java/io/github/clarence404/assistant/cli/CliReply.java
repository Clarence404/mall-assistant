package io.github.clarence404.assistant.cli;

/**
 * 一次阻塞调用的结果。
 *
 * @param text 回答文本；结构化输出时为 JSON 字符串
 * @param model 工具报告的实际模型名，可能为 null
 * @param inputTokens 输入 token 数，未知时为 null
 * @param outputTokens 输出 token 数，未知时为 null
 * @param costUsd 工具报告的费用（美元），未知时为 null
 */
public record CliReply(String text, String model, Integer inputTokens, Integer outputTokens, Double costUsd) {

    public static CliReply text(String text) {
        return new CliReply(text, null, null, null, null);
    }
}
