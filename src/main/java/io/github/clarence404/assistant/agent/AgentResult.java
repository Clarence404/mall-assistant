package io.github.clarence404.assistant.agent;

/**
 * 一次办事的结果：给用户的回答、调试信息，以及（如果提出了写操作）待确认操作（第 7 章引入）。
 *
 * <p>所在层：业务层的输出。{@link AgentService#run} 返回；{@code ChatService} 把 reply 当作这一轮的回答（阻塞接口的 {@code reply}、流式的最后一段
 * {@code token}），trace 放进 {@code agent} 字段，confirmation 放进 {@code confirmation} 字段（流式已经在步骤之后推过 {@code confirm} 事件）。
 *
 * @param reply 给用户的回答：finish 时是模型的回答，其余情况是代码写好的固定话术
 * @param trace 调试信息
 * @param confirmation 待确认操作；没提出写操作时为 null
 */
public record AgentResult(String reply, AgentTrace trace, PendingConfirmation confirmation) {}
