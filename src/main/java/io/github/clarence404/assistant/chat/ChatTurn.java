package io.github.clarence404.assistant.chat;

import io.github.clarence404.assistant.intent.IntentResult;
import io.github.clarence404.assistant.memory.Conversation;

/**
 * 一轮对话：用户这一句、识别出的意图、商城令牌，以及这一轮的会话句柄（第 6 章引入）。
 *
 * <p>所在层：业务层的中间结果。{@link ChatService#begin} 生成（打开会话、识别意图、沿用订单号），流式接口先把 intent 推给页面，
 * 再交给 {@link ChatService#answerStream(ChatTurn)} 回答；阻塞接口在 {@link ChatService#chat(String, String, String)} 里一口气走完。
 *
 * @param message 用户原文
 * @param intent 意图（可能补上了从历史沿用的订单号 / SKU）
 * @param mallToken 商城令牌，可以为 null；只在工具里用，不进任何发给模型的文字，也不进记忆
 * @param conversation 会话句柄；单轮时是 {@link Conversation#none()}
 */
public record ChatTurn(String message, IntentResult intent, String mallToken, Conversation conversation) {}
