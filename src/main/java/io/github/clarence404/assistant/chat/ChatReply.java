package io.github.clarence404.assistant.chat;

import io.github.clarence404.assistant.intent.IntentResult;

/**
 * 阻塞接口的返回：模型的完整回答，以及先识别出的意图。
 *
 * <p>所在层：接口层的响应体（第 1 章引入，第 2 章加 intent 字段）。由 {@link ChatService#chat} 生成，{@link ChatController}
 * 序列化成 JSON。前端把 intent 显示在调试面板里。
 *
 * @param reply 给用户看的回答：模型生成的文本，或超出范围时的拒答话术
 * @param intent 本次识别出的意图，已经过 {@link IntentResult#sanitized()} 校验
 */
public record ChatReply(String reply, IntentResult intent) {}
