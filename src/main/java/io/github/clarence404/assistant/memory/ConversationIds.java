package io.github.clarence404.assistant.memory;

/**
 * 会话 ID 的格式（第 6 章引入）：小写的 UUID，如 {@code 3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d}。
 *
 * <p>所在层：接口层的约定。{@code ChatRequest#conversationId} 和 {@link ConversationController} 的路径参数都用 {@link #PATTERN} 校验，
 * 格式不对直接 400，不进业务。
 *
 * <p>为什么限制格式：会话 ID 由页面生成（{@code crypto.randomUUID()}，本来就是小写 UUID），服务端不能假设它可信。限死格式之后，它不可能是一段
 * 超长文本、SQL 片段或路径，日志里打出来也安全；只认小写，免得 {@code ABC…} 和 {@code abc…} 被当成两个会话。
 */
public final class ConversationIds {

    /** 小写 UUID：8-4-4-4-12 个十六进制字符。 */
    public static final String PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private ConversationIds() {}
}
