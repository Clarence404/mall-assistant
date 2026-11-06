package io.github.clarence404.assistant.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户发来的一条消息。
 *
 * <p>所在层：接口层的请求体（第 1 章引入）。{@link ChatController} 的两个接口和 {@code IntentController} 共用。
 *
 * @param message 用户原文；不能为空白，最长 2000 字。限制长度既防止滥用，也是控制成本：发给模型的文字越长，消耗的 token
 *     越多（token 是模型计费和计算上下文长度的单位，中文大致一两个字算一个），响应也越慢
 */
public record ChatRequest(@NotBlank @Size(max = 2000) String message) {}
