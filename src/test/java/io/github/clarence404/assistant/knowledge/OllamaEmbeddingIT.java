package io.github.clarence404.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.chat.FakeChatModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.ollama.OllamaContainer;

/**
 * 集成测试：真实的 Ollama 向量模型（默认 {@code nomic-embed-text}，约 270 MB）走一遍入库与检索（第 4 章引入）。
 *
 * <p>测什么：Spring AI 自动配置的 OllamaEmbeddingModel 真能被 {@link PrefixedEmbeddingModel} 包住使用；维度从模型探测出来（768）；
 * nomic 的前缀配置生效；入库、检索不报错，分数在合理范围。为什么用 nomic-embed-text 不用默认的 bge-m3：bge-m3 约 1.2 GB，CI 每次缓存未命中
 * 都要下载，太大；两个模型走的是完全相同的代码路径。断言放得宽（nomic 以英文为主，中文检索只要求「退换货规则」在前三名里），
 * 准不准看 docs/ch04.md 里两个模型的实测对比。
 *
 * <p>模型从哪来：有 Docker 时用 Testcontainers 起 {@code ollama/ollama}（镜像版本、模型缓存目录与 {@code OllamaChatIT} 共用，
 * CI 的 actions/cache 键里写了这个模型）；本机没有 Docker 时可以设置环境变量 {@code EMBEDDING_IT_OLLAMA_URL} 指向一个已经
 * pull 好模型的 Ollama；都没有就跳过。对话模型用假的，不需要。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.ai.model.chat=none",
            "spring.ai.model.embedding=ollama",
            "assistant.knowledge.ingest-on-startup=false",
            "assistant.knowledge.memory.dir="
        })
@Import(OllamaEmbeddingIT.Models.class)
class OllamaEmbeddingIT {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingIT.class);

    static final String MODEL = System.getenv().getOrDefault("OLLAMA_EMBEDDING_IT_MODEL", "nomic-embed-text");

    static OllamaContainer ollama;
    static String baseUrl;

    @Autowired
    KnowledgeIngestionService ingestion;

    @Autowired
    KnowledgeSearchService search;

    @Autowired
    KnowledgeEmbedding embedding;

    /** 先准备好模型服务，再启动 Spring 上下文；都没有时跳过整个类。 */
    @BeforeAll
    static void startOllama() throws Exception {
        String external = System.getenv("EMBEDDING_IT_OLLAMA_URL");
        if (external != null && !external.isBlank()) {
            baseUrl = external;
            return;
        }
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "没有 Docker，也没有设置 EMBEDDING_IT_OLLAMA_URL，跳过真实向量模型的集成测试");
        ollama = new OllamaContainer(OllamaChatItSupport.IMAGE)
                .withFileSystemBind(OllamaChatItSupport.modelCacheDir(), "/root/.ollama", BindMode.READ_WRITE);
        ollama.start();
        ExecResult pull = ollama.execInContainer("ollama", "pull", MODEL);
        log.info("ollama pull {} -> exit {}", MODEL, pull.getExitCode());
        assertThat(pull.getExitCode()).as(pull.getStderr()).isZero();
        baseUrl = ollama.getEndpoint();
    }

    @AfterAll
    static void stopOllama() {
        if (ollama != null) {
            ollama.stop();
        }
    }

    @DynamicPropertySource
    static void ollamaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", () -> baseUrl);
        registry.add("spring.ai.ollama.embedding.model", () -> MODEL);
        registry.add("assistant.knowledge.embedding-model", () -> MODEL);
    }

    @Test
    void realEmbeddingModelIngestsAndSearches() {
        IngestReport report = ingestion.ingest(true);
        log.info("入库：{} 块，{} ms", report.totalChunks(), report.durationMs());

        assertThat(report.documentsAdded()).isEqualTo(6);
        assertThat(embedding.dimensions()).isGreaterThan(100);
        if (MODEL.startsWith("nomic-embed-text")) {
            assertThat(embedding.dimensions()).isEqualTo(768);
            assertThat(embedding.profile().queryPrefix()).isEqualTo("search_query: ");
        }

        List<KnowledgeHit> hits = search.search("七天无理由退货要什么条件", 3);
        log.info(
                "检索：{}",
                hits.stream().map(h -> h.headingPath() + "=" + h.score()).toList());
        assertThat(hits).hasSize(3);
        assertThat(hits).allSatisfy(h -> assertThat(h.score()).isBetween(-1.0, 1.0));
        assertThat(hits).extracting(KnowledgeHit::source).contains("returns.md");
    }

    /** 测试用的假对话模型：应用启动需要一个 ChatModel，这个测试不调它。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Models {

        @Bean
        FakeChatModel fakeChatModel() {
            return new FakeChatModel();
        }
    }

    /** 与 {@code OllamaChatIT} 共用的镜像版本与模型缓存目录（那个类在 chat 包里且是包内可见的，这里抄一份常量）。 */
    static final class OllamaChatItSupport {

        static final String IMAGE = "ollama/ollama:0.40.14";

        private OllamaChatItSupport() {}

        static String modelCacheDir() throws IOException {
            String configured = System.getenv("OLLAMA_MODELS_CACHE");
            Path dir = configured != null && !configured.isBlank()
                    ? Path.of(configured)
                    : Path.of(System.getProperty("user.home"), ".cache", "mall-assistant", "ollama");
            Files.createDirectories(dir);
            return dir.toAbsolutePath().toString();
        }
    }
}
