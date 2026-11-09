package io.github.clarence404.assistant.agent;

import io.github.clarence404.assistant.memory.ConversationOwner;
import java.util.List;
import org.springframework.ai.chat.messages.Message;

/**
 * 交给 Agent 的一件事：用户这一句、对话历史、谁在办（第 7 章引入）。
 *
 * <p>所在层：业务层的输入。{@code ChatService} 在一轮对话里组装好交给 {@link AgentService#run}。
 *
 * @param message 用户这一句（原话）。改地址时代码会核对：新地址必须是用户自己说的（见 {@link WriteActionGuard}）
 * @param history 对话历史（第 6 章的 {@code Conversation#history()}），单轮时为空列表；放在系统提示词之后、这一轮之前
 * @param mallToken 商城令牌：只在代码里用来调商城（查订单、执行写操作），<b>从不</b>进入发给模型的任何文字
 * @param owner 谁在办：商城确认过的用户（待确认操作绑定到他，别人拿到确认 ID 也点不了）
 * @param conversationId 会话 ID，可以为 null；待确认操作也绑定到这个会话，确认后的结果写回这个会话的记忆
 */
public record AgentTask(
        String message, List<Message> history, String mallToken, ConversationOwner owner, String conversationId) {}
