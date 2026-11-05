package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 按 profile 切换模型厂商：默认 ollama，--spring.profiles.active=openai 换成 OpenAI 兼容接口。只启动上下文，不发请求。 */
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
}
