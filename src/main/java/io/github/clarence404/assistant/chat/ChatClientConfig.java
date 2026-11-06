package io.github.clarence404.assistant.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 组装 ChatClient：模型由 Starter 按 profile 提供；系统提示词每次请求时再设置（客服人设见 AssistantPrompts，意图识别另有提示词）。
 *
 * <p>所在层：模型接入（第 1 章引入）。下游是 Spring AI 自动配置的 {@code ChatModel}；上游是 {@link ChatService} 与
 * {@code IntentService}，两者共用这一个 ChatClient。
 *
 * <p>两个容易混的概念：
 *
 * <ul>
 *   <li>{@code ChatModel}：底层接口，一个厂商一个实现（OllamaChatModel、OpenAiChatModel，本项目还有 CliChatModel），输入一个
 *       {@code Prompt}（若干条消息 + 选项），输出一个 {@code ChatResponse}。换厂商只换它
 *   <li>{@code ChatClient}：架在 ChatModel 之上的流式 API（类似 RestClient 之于 HTTP），用
 *       {@code prompt().system(..).user(..).call()} 拼消息、发请求、取结果，还负责把输出转成 Java 对象（{@code entity()}）。业务代码只依赖它
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class ChatClientConfig {

    /**
     * {@link ChatClient.Builder} 由 Spring AI 自动配置注入（原型作用域，每次注入一个新的），背后的 ChatModel 是 Ollama 还是
     * OpenAI 兼容接口由 {@code spring.ai.model.chat} 决定，这里不关心。
     *
     * <p>第 1 章在这里用 {@code defaultSystem} 固定了人设；第 2 章人设变成带变量的模板（含「今天」），改为每次请求时渲染。
     *
     * @param builder Spring AI 自动配置提供的构建器，已经绑好了当前 profile 的 ChatModel
     * @return 不带默认系统提示词的 ChatClient；每次 {@code prompt()} 都会新建一份请求，多个 Service 共用同一个实例互不影响
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
