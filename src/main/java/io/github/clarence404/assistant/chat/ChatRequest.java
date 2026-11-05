package io.github.clarence404.assistant.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 用户发来的一条消息。 */
public record ChatRequest(@NotBlank @Size(max = 2000) String message) {}
