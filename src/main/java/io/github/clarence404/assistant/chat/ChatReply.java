package io.github.clarence404.assistant.chat;

import io.github.clarence404.assistant.intent.IntentResult;

/** 阻塞接口的返回：模型的完整回答，以及先识别出的意图。 */
public record ChatReply(String reply, IntentResult intent) {}
