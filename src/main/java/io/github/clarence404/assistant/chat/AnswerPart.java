package io.github.clarence404.assistant.chat;

import io.github.clarence404.assistant.tool.ToolInvocation;

/**
 * 流式回答里的一个片段：要么是一段文字，要么是一次工具调用的记录。
 *
 * <p>所在层：业务层与接口层之间的数据（第 3 章引入）。{@link ChatService#answerStream} 产出它，{@link ChatController#stream}
 * 把 {@link Text} 写成 {@code token} 事件、把 {@link ToolUse} 写成 {@code tool} 事件。
 *
 * <p>{@code sealed} 表示只有这两种实现，{@code switch} 时编译器能检查有没有漏掉。
 */
public sealed interface AnswerPart {

    /**
     * 一段增量文字。
     *
     * @param text 模型新生成的文字（或拒答话术）
     */
    record Text(String text) implements AnswerPart {}

    /**
     * 一次工具调用。
     *
     * @param invocation 调用记录
     */
    record ToolUse(ToolInvocation invocation) implements AnswerPart {}
}
