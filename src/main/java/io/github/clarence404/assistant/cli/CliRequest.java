package io.github.clarence404.assistant.cli;

/**
 * 一次命令行调用要发给模型的内容：{@link CliChatModel#toRequest} 从 Spring AI 的 {@code Prompt} 里拆出来，交给 {@link CliDialect}
 * 拼参数（系统提示词、Schema 写成文件）和 stdin（对话内容）。
 *
 * <p>架构层：命令行模型。不属于某一章的练习，是第 2 章之后加的可选扩展（{@code claude-cli} / {@code codex-cli} profile 才启用）。
 *
 * <p>系统提示词（system message）是给模型定的「人设与规则」，用户看不到；用户消息（user message）是本轮要回答的内容。
 * 命令行工具也区分这两者：系统提示词走专门的参数，用户消息走 stdin，所以这里分成两个字段。
 *
 * @param systemPrompt 系统提示词，可能为空串
 * @param conversation 对话内容（一般就是用户消息）
 * @param outputSchema 结构化输出的 JSON Schema，不需要时为 null
 * @param model 模型名，null 表示用工具的默认模型
 * @param stream 是否流式输出
 */
public record CliRequest(String systemPrompt, String conversation, String outputSchema, String model, boolean stream) {

    /** 是否有非空白的系统提示词：没有就不传系统提示词参数，工具用它自带的。 */
    public boolean hasSystemPrompt() {
        return systemPrompt != null && !systemPrompt.isBlank();
    }

    /** 是否要求结构化输出（意图识别走原生结构化输出时为 true，普通对话为 false）。 */
    public boolean hasOutputSchema() {
        return outputSchema != null && !outputSchema.isBlank();
    }
}
