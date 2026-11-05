package io.github.clarence404.assistant.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/** 组装客服用的 ChatClient：模型由 Starter 按 profile 提供，这里只负责「人设」。 */
@Configuration(proxyBeanMethods = false)
public class ChatClientConfig {

    /**
     * {@link ChatClient.Builder} 由 Spring AI 自动配置注入（原型作用域，每次注入一个新的），背后的 ChatModel 是 Ollama 还是
     * OpenAI 兼容接口由 {@code spring.ai.model.chat} 决定，这里不关心。
     */
    @Bean
    public ChatClient chatClient(
            ChatClient.Builder builder, @Value("classpath:prompts/system.st") Resource systemPrompt) {
        // TODO ch01-1：把 systemPrompt（src/main/resources/prompts/system.st）设为默认系统消息，再 build。
        //  这样之后每次 chatClient.prompt() 都会自动带上客服人设，业务代码只需要写用户消息。
        //  验收：ChatServiceTest.systemPromptFromClasspathIsSentWithEveryRequest
        return builder.build();
    }
}
