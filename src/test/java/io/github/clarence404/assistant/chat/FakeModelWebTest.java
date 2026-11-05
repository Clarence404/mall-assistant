package io.github.clarence404.assistant.chat;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 整个应用（Controller → ChatService → ChatClient）跑起来，只把 ChatModel 换成 {@link FakeChatModel}。
 *
 * <p>{@code spring.ai.model.chat=none} 关掉 Ollama / OpenAI 的自动配置，Spring AI 的 ChatClient.Builder 会用这里提供的假模型。
 */
@SpringBootTest(properties = "spring.ai.model.chat=none")
@AutoConfigureMockMvc
@Import(FakeModelWebTest.FakeModelConfig.class)
abstract class FakeModelWebTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected FakeChatModel model;

    @BeforeEach
    void resetModel() {
        model.reset();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeModelConfig {

        @Bean
        FakeChatModel fakeChatModel() {
            return new FakeChatModel();
        }
    }
}
