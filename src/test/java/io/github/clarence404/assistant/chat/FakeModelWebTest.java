package io.github.clarence404.assistant.chat;

import io.github.clarence404.assistant.knowledge.FakeEmbeddingModel;
import io.github.clarence404.assistant.mall.StubMallApi;
import io.github.clarence404.assistant.rag.RagFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
 *
 * <p>第 3 章起回答时会调商城接口：{@link StubMallApi} 在随机端口起一个假的商城网关，{@code @DynamicPropertySource} 把它的地址填进
 * {@code assistant.mall.base-url}。它在整个测试进程里只起一个（static），同样每个测试前清空记录。
 *
 * <p>第 4 章起应用里还有向量模型：同样关掉 Ollama 的自动配置（{@code spring.ai.model.embedding=none}），换成不联网的
 * {@link FakeEmbeddingModel}；知识库不在启动时自动入库、不写 JSON 文件，由知识库的测试自己调用入库接口，免得测试之间互相影响。
 *
 * <p>第 5 章起规则类问题走 RAG：假向量模型的相似度分布和 bge-m3 完全不同（相关的块也只有 0.2～0.35），相似度门槛按它改成
 * {@link RagFixtures#FAKE_MIN_SCORE}，否则什么问题都过不了门槛。
 */
@SpringBootTest(
        properties = {
            "spring.ai.model.chat=none",
            "spring.ai.model.embedding=none",
            "assistant.knowledge.embedding-model=fake-ngram",
            "assistant.knowledge.ingest-on-startup=false",
            "assistant.knowledge.memory.dir=",
            "assistant.rag.min-score=" + RagFixtures.FAKE_MIN_SCORE
        })
@AutoConfigureMockMvc
@Import(FakeModelWebTest.FakeModelConfig.class)
abstract class FakeModelWebTest {

    @Autowired
    protected MockMvc mockMvc;

    /** 假商城网关：整个测试进程共用一个，JVM 退出时随之关闭。 */
    protected static final StubMallApi MALL = StubMallApi.start();

    @Autowired
    protected FakeChatModel model;

    /** 第 4 章：假向量模型。 */
    @Autowired
    protected FakeEmbeddingModel embeddingModel;

    @DynamicPropertySource
    static void mallApi(DynamicPropertyRegistry registry) {
        registry.add("assistant.mall.base-url", MALL::baseUrl);
    }

    /** 上下文与假模型在测试之间复用：先清掉上一个测试的预设和记录，避免互相影响。 */
    @BeforeEach
    void resetModel() {
        model.reset();
        embeddingModel.reset();
        MALL.reset();
    }

    /** 把假模型注册成 Spring Bean：容器里唯一的 ChatModel，ChatClient.Builder 会拿它来构建 ChatClient。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class FakeModelConfig {

        @Bean
        FakeChatModel fakeChatModel() {
            return new FakeChatModel();
        }

        /** 第 4 章：容器里唯一的 EmbeddingModel，知识库拿它向量化。 */
        @Bean
        FakeEmbeddingModel fakeEmbeddingModel() {
            return new FakeEmbeddingModel();
        }
    }
}
