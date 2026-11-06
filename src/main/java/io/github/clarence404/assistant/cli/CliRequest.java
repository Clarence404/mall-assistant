package io.github.clarence404.assistant.cli;

/**
 * 一次命令行调用要发给模型的内容。
 *
 * @param systemPrompt 系统提示词，可能为空串
 * @param conversation 对话内容（一般就是用户消息）
 * @param outputSchema 结构化输出的 JSON Schema，不需要时为 null
 * @param model 模型名，null 表示用工具的默认模型
 * @param stream 是否流式输出
 */
public record CliRequest(String systemPrompt, String conversation, String outputSchema, String model, boolean stream) {

    public boolean hasSystemPrompt() {
        return systemPrompt != null && !systemPrompt.isBlank();
    }

    public boolean hasOutputSchema() {
        return outputSchema != null && !outputSchema.isBlank();
    }
}
