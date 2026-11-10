package io.github.clarence404.assistant.chat;

import io.github.clarence404.assistant.agent.AgentStep;
import io.github.clarence404.assistant.agent.PendingConfirmation;
import io.github.clarence404.assistant.memory.MemoryTrace;
import io.github.clarence404.assistant.rag.BlockedSource;
import io.github.clarence404.assistant.rag.RagSource;
import io.github.clarence404.assistant.tool.ToolInvocation;

/**
 * 流式回答里的一个片段：一段文字、一次工具调用的记录、一条 RAG 引用来源、这一轮的记忆情况，或 Agent 的一步与待确认操作（第 7 章）。
 *
 * <p>所在层：业务层与接口层之间的数据（第 3 章引入，第 5 章加 {@link Source}，第 6 章加 {@link Memory}）。{@link ChatService#answerStream} 产出它，
 * {@link ChatController#stream} 把 {@link Text} 写成 {@code token} 事件、把 {@link ToolUse} 写成 {@code tool} 事件、
 * 把 {@link Source} 写成 {@code source} 事件、把 {@link Memory} 写成 {@code memory} 事件、把 {@link Step} 写成 {@code step} 事件、
 * 把 {@link Confirm} 写成 {@code confirm} 事件。
 *
 * <p>{@code sealed} 表示只有这几种实现，{@code switch} 时编译器能检查有没有漏掉。
 */
public sealed interface AnswerPart {

    /**
     * 一段增量文字。
     *
     * @param text 模型新生成的文字（或拒答话术、RAG 的固定话术）
     */
    record Text(String text) implements AnswerPart {}

    /**
     * 一次工具调用。
     *
     * @param invocation 调用记录
     */
    record ToolUse(ToolInvocation invocation) implements AnswerPart {}

    /**
     * 一条 RAG 引用来源（第 5 章）：在模型的第一段文字之前推出，页面先拿到 [1][2]… 对应的资料，文字里出现编号时就能对上。
     *
     * @param source 引用来源
     */
    record Source(RagSource source) implements AnswerPart {}

    /**
     * 这一轮的记忆情况（第 6 章）：带了几轮历史、裁掉几轮、追问改写成了什么。请求带了会话 ID 时，在第一段文字（和引用来源）之前推出一次。
     *
     * @param trace 记忆调试信息
     */
    record Memory(MemoryTrace trace) implements AnswerPart {}

    /**
     * Agent 走了一步（第 7 章）：边走边推，页面的「Agent 步骤」一栏实时多一行。
     *
     * @param step 这一步
     */
    record Step(AgentStep step) implements AnswerPart {}

    /**
     * Agent 提出了一个写操作（第 7 章）：在它那一步之后、回答文字之前推出，页面画一张确认卡片。
     *
     * @param confirmation 待确认操作
     */
    record Confirm(PendingConfirmation confirmation) implements AnswerPart {}

    /**
     * 一块被提示注入过滤挡下的资料（第 9 章）：RAG 流式回答里，在引用来源之后、文字之前推出，页面调试面板用红色列出。阻塞接口在 {@code rag.blocked} 里。
     *
     * @param source 被挡下的资料
     */
    record Blocked(BlockedSource source) implements AnswerPart {}
}
