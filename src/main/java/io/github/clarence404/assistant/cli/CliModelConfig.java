package io.github.clarence404.assistant.cli;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 设置了 {@code assistant.cli.flavor}（{@code claude-cli} / {@code codex-cli} profile）时，用命令行工具提供 ChatModel。
 *
 * <p>这两个 profile 同时把 {@code spring.ai.model.chat} 设为 {@code none}，关掉 Ollama 与 OpenAI 的自动配置，保证只有这一个 ChatModel；
 * ChatClient.Builder 照常由 Spring AI 自动配置，业务代码不用改。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "assistant.cli", name = "flavor")
public class CliModelConfig {

    @Bean
    public CliChatModel cliChatModel(CliProperties properties) {
        return new CliChatModel(properties);
    }
}
