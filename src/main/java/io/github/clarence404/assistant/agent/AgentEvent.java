package io.github.clarence404.assistant.agent;

/**
 * Agent 干活过程中的事件：走了一步，或生成了一个待确认操作（第 7 章引入）。
 *
 * <p>所在层：业务层与接口层之间的数据。{@link AgentLoop} 每走一步通过监听器发出 {@link StepTaken}，提出写操作时再发出 {@link ConfirmationRequested}；
 * {@code ChatService} 把它们转成流式的 {@code step} / {@code confirm} 事件（阻塞接口不需要监听，最后从 {@link AgentResult} 一次拿全）。
 */
public sealed interface AgentEvent {

    /**
     * 走了一步。
     *
     * @param step 这一步
     */
    record StepTaken(AgentStep step) implements AgentEvent {}

    /**
     * 生成了一个待确认操作：页面收到后画一张确认卡片。
     *
     * @param confirmation 待确认操作
     */
    record ConfirmationRequested(PendingConfirmation confirmation) implements AgentEvent {}
}
