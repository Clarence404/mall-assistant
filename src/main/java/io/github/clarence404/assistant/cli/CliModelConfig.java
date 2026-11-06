package io.github.clarence404.assistant.cli;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 设置了 {@code assistant.cli.flavor}（{@code claude-cli} / {@code codex-cli} profile）时，用命令行工具提供 ChatModel。
 *
 * <p>这两个 profile 同时把 {@code spring.ai.model.chat} 设为 {@code none}，关掉 Ollama 与 OpenAI 的自动配置，保证只有这一个 ChatModel；
 * ChatClient.Builder 照常由 Spring AI 自动配置，业务代码不用改。
 *
 * <p>架构层：命令行模型。不属于某一章的练习，是第 2 章之后加的可选扩展（{@code claude-cli} / {@code codex-cli} profile 才启用）。
 *
 * <p>为什么能「无缝替换」：业务代码（{@code ChatClientConfig} → {@code ChatService} / {@code IntentService}）只依赖 Spring AI 的
 * {@code ChatClient}，而 ChatClient 底下调用的是容器里唯一的 {@code ChatModel} Bean。平时这个 Bean 是 OllamaChatModel 或
 * OpenAiChatModel（自动配置提供），这里换成自己实现的 {@link CliChatModel}，上层完全感知不到。
 *
 * <p>上游：Spring 容器（profile 设置了 {@code assistant.cli.flavor} 时才加载）；下游：创建 {@link CliChatModel}。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "assistant.cli", name = "flavor")
public class CliModelConfig {

    /**
     * 注册命令行实现的 ChatModel。
     *
     * @param properties {@code assistant.cli.*} 配置（由启动类上的 {@code @ConfigurationPropertiesScan} 注册）
     * @return 唯一的 ChatModel Bean，Spring AI 自动配置的 ChatClient.Builder 会用它
     */
    @Bean
    public CliChatModel cliChatModel(CliProperties properties) {
        return new CliChatModel(properties);
    }
}
