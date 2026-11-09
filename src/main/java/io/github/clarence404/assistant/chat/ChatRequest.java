package io.github.clarence404.assistant.chat;

import io.github.clarence404.assistant.memory.ConversationIds;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 用户发来的一条消息。
 *
 * <p>所在层：接口层的请求体（第 1 章引入，第 6 章加 conversationId）。{@link ChatController} 的两个接口和 {@code IntentController} 共用
 * （意图识别接口不看 conversationId）。
 *
 * <p>会话 ID 为什么放在请求体里、而不是请求头（如 {@code X-Conversation-Id}）：它和这条消息是一回事——「这句话是哪个对话里说的」，属于业务数据，
 * 和 message 放在一起最直观，调试时看请求体就全知道了；请求头更适合「每个请求都一样」的横切信息，比如商城令牌（{@code Authorization}）。
 * 两种都能用，选一种、全项目统一就行。
 *
 * @param message 用户原文；不能为空白，最长 2000 字。限制长度既防止滥用，也是控制成本：发给模型的文字越长，消耗的 token
 *     越多（token 是模型计费和计算上下文长度的单位，中文大致一两个字算一个），响应也越慢
 * @param conversationId 会话 ID（第 6 章）：小写 UUID，由页面生成并存在 localStorage；不带时和第 5 章一样，每次都是单轮对话，不读不写记忆。
 *     格式不对直接 400
 */
public record ChatRequest(
        @NotBlank @Size(max = 2000) String message,
        @Pattern(regexp = ConversationIds.PATTERN) String conversationId) {

    /**
     * 不带会话 ID 的单轮消息（前几章的写法）。
     *
     * @param message 用户原文
     */
    public ChatRequest(String message) {
        this(message, null);
    }
}
