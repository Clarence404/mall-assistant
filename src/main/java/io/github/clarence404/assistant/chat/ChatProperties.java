package io.github.clarence404.assistant.chat;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 对话相关配置，前缀 {@code assistant.chat}。
 *
 * <p>所在层：业务层的配置（第 1 章引入 streamTimeout，第 2 章加 refuseMinConfidence）。由 {@code AssistantApplication} 上的
 * {@code @ConfigurationPropertiesScan} 绑定，注入 {@link ChatService}。{@code @DefaultValue} 是 yml 里没写时的默认值；
 * 也可以用环境变量覆盖，如 {@code ASSISTANT_CHAT_STREAM_TIMEOUT=60s}（Spring Boot 的宽松绑定）。
 *
 * @param streamTimeout 流式输出时，首个片段以及相邻两个片段之间最多等多久；超过就按超时结束
 * @param refuseMinConfidence 意图为 OUT_OF_SCOPE 且把握不低于这个值时才直接拒答；低于它照常交给模型回答，宁可多答一句，不错拒正常问题
 */
@ConfigurationProperties("assistant.chat")
public record ChatProperties(
        @DefaultValue("30s") Duration streamTimeout,
        @DefaultValue("0.6") double refuseMinConfidence) {}
