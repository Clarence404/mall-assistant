package io.github.clarence404.assistant.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 组装 ChatClient：模型由 Starter 按 profile 提供；系统提示词每次请求时再设置（客服人设见 AssistantPrompts，意图识别另有提示词）。 */
@Configuration(proxyBeanMethods = false)
public class ChatClientConfig {

    /**
     * {@link ChatClient.Builder} 由 Spring AI 自动配置注入（原型作用域，每次注入一个新的），背后的 ChatModel 是 Ollama 还是
     * OpenAI 兼容接口由 {@code spring.ai.model.chat} 决定，这里不关心。
     *
     * <p>第 1 章在这里用 {@code defaultSystem} 固定了人设；第 2 章人设变成带变量的模板（含「今天」），改为每次请求时渲染。
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
