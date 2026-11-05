package io.github.clarence404.assistant.chat;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 对话相关配置，前缀 {@code assistant.chat}。
 *
 * @param streamTimeout 流式输出时，首个片段以及相邻两个片段之间最多等多久；超过就按超时结束
 */
@ConfigurationProperties("assistant.chat")
public record ChatProperties(@DefaultValue("30s") Duration streamTimeout) {}
