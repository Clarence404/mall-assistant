package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.cli.CliChatModel;
import io.github.clarence404.assistant.cli.CliFlavor;
import io.github.clarence404.assistant.cli.CliProperties;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 按 profile 切换模型厂商：默认 ollama，--spring.profiles.active=openai 换成 OpenAI 兼容接口，claude-cli / codex-cli 换成本机的命令行工具。
 * 只启动上下文，不发请求（命令行工具在第一次调用时才查找，没装也能启动）。
 */
class ProviderSwitchTest {

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
    class DefaultProfile {

        @Autowired
        ChatModel chatModel;

        @Test
        void usesOllama() {
            assertThat(chatModel).isInstanceOf(OllamaChatModel.class);
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = {
                "OPENAI_BASE_URL=http://localhost:1/v1",
                "OPENAI_API_KEY=test-placeholder",
                "OPENAI_MODEL=test-model"
            })
    @ActiveProfiles("openai")
    class OpenAiProfile {

        @Autowired
        ChatModel chatModel;

        @Test
        void usesOpenAiCompatibleEndpoint() {
            assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
    @ActiveProfiles("claude-cli")
    class ClaudeCliProfile {

        @Autowired
        ChatModel chatModel;

        @Autowired
        ObjectProvider<OllamaChatModel> ollama;

        @Autowired
        CliProperties properties;

        @Test
        void usesClaudeCodeAsTheOnlyChatModel() {
            assertThat(chatModel).isInstanceOf(CliChatModel.class);
            assertThat(ollama.getIfAvailable()).isNull();
            assertThat(properties.flavor()).isEqualTo(CliFlavor.CLAUDE);
            assertThat(properties.model()).isEqualTo("haiku");
            assertThat(properties.env()).containsEntry("CLAUDE_CODE_DISABLE_CLAUDE_MDS", "1");
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
    @ActiveProfiles("codex-cli")
    class CodexCliProfile {

        @Autowired
        ChatModel chatModel;

        @Autowired
        CliProperties properties;

        @Test
        void usesCodexCli() {
            assertThat(chatModel).isInstanceOf(CliChatModel.class);
            assertThat(properties.flavor()).isEqualTo(CliFlavor.CODEX);
            assertThat(properties.model()).as("留空用 codex 的默认模型").isNull();
        }
    }
}
