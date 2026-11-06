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
 * 接口测试的公共基类（第 1 章引入）：整个应用（Controller → ChatService → ChatClient）跑起来，只把 ChatModel 换成 {@link FakeChatModel}。
 *
 * <p>测试手法：{@code @SpringBootTest} 启动完整的 Spring 上下文（真实的 Controller、参数校验、异常处理、JSON 序列化、SSE 写出都在），
 * {@code @AutoConfigureMockMvc} 提供 {@link MockMvc}：在进程内模拟 HTTP 请求，不真正监听端口，但走的是和线上一样的 Spring MVC 链路。
 * 这样一个测试覆盖「HTTP 进 → 业务 → 模型 → HTTP 出」整条链，唯一被替换的是最底层的模型。
 *
 * <p>{@code spring.ai.model.chat=none} 关掉 Ollama / OpenAI 的自动配置，Spring AI 的 ChatClient.Builder 会用这里提供的假模型。
 * 子类的配置相同，Spring 测试框架会缓存并复用同一个上下文，假模型也就是共享的，所以每个测试前都要 {@link #resetModel()}。
 */
@SpringBootTest(properties = "spring.ai.model.chat=none")
@AutoConfigureMockMvc
@Import(FakeModelWebTest.FakeModelConfig.class)
abstract class FakeModelWebTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected FakeChatModel model;

    /** 上下文与假模型在测试之间复用：先清掉上一个测试的预设和记录，避免互相影响。 */
    @BeforeEach
    void resetModel() {
        model.reset();
    }

    /** 把假模型注册成 Spring Bean：容器里唯一的 ChatModel，ChatClient.Builder 会拿它来构建 ChatClient。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class FakeModelConfig {

        @Bean
        FakeChatModel fakeChatModel() {
            return new FakeChatModel();
        }
    }
}
