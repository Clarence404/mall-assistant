package io.github.clarence404.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.chat.FakeChatModel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * 第 4 章：启动时自动入库（{@code KnowledgeStartupIngest}，已给好）与「向量模型不可用不阻止启动」。
 *
 * <p>测试思路：起两个完整的 Spring 上下文（不起 Web 端口），都打开 {@code ingest-on-startup}。一个的假向量模型正常：启动完成时库里
 * 已经有 6 篇文档；另一个的假向量模型一调用就抛异常（模拟 Ollama 没启动）：上下文照样启动成功（能注入 Bean 就说明启动没被打断），
 * 库是空的，概况接口还能用并在 warning 里说明原因。
 */
class KnowledgeStartupTest {

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = {
                "spring.ai.model.chat=none",
                "spring.ai.model.embedding=none",
                "assistant.knowledge.embedding-model=fake-ngram",
                "assistant.knowledge.ingest-on-startup=true",
                "assistant.knowledge.memory.dir="
            })
    @Import(Models.class)
    class ModelAvailable {

        @Autowired
        KnowledgeSearchService search;

        @Test
        void documentsAreIngestedOnStartup() {
            KnowledgeStats stats = search.stats();

            assertThat(stats.documentCount()).isEqualTo(6);
            assertThat(stats.chunkCount()).isGreaterThan(20);
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = {
                "spring.ai.model.chat=none",
                "spring.ai.model.embedding=none",
                "assistant.knowledge.embedding-model=fake-ngram",
                "assistant.knowledge.ingest-on-startup=true",
                "assistant.knowledge.memory.dir="
            })
    @Import(BrokenModels.class)
    class ModelUnavailable {

        @Autowired
        KnowledgeSearchService search;

        @Test
        void startupSucceedsAndStoreStaysEmpty() {
            KnowledgeStats stats = search.stats();

            assertThat(stats.documentCount()).isZero();
            assertThat(stats.dimensions()).isNull();
            assertThat(stats.warning()).contains("fake-ngram");
        }
    }

    /** 正常的假模型。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Models {

        @Bean
        FakeChatModel fakeChatModel() {
            return new FakeChatModel();
        }

        @Bean
        FakeEmbeddingModel fakeEmbeddingModel() {
            return new FakeEmbeddingModel();
        }
    }

    /** 一调用就失败、维度也探测不出来的假向量模型。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class BrokenModels {

        @Bean
        FakeChatModel fakeChatModel() {
            return new FakeChatModel();
        }

        @Bean
        FakeEmbeddingModel fakeEmbeddingModel() {
            FakeEmbeddingModel model = new FakeEmbeddingModel() {
                @Override
                public int dimensions() {
                    throw new IllegalStateException("Connection refused");
                }
            };
            model.failWith(new IllegalStateException("Connection refused"));
            return model;
        }
    }
}
