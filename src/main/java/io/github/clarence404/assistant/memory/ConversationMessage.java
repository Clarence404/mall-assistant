package io.github.clarence404.assistant.memory;

import java.time.Instant;

/**
 * 会话里的一条消息（{@code GET /api/conversations/{id}/messages} 返回的形式，第 6 章引入）。
 *
 * <p>所在层：接口层的数据。{@link ConversationMemory#messages} 生成，页面刷新后用它把历史气泡画回来。
 *
 * @param role {@code user}（用户原话）、{@code assistant}（助手的回答，引用编号已去掉）或 {@code summary}（摘要记忆压缩出的早期对话摘要）
 * @param text 文字
 * @param at 时间；没有时为 null
 */
public record ConversationMessage(String role, String text, Instant at) {}
