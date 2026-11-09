package io.github.clarence404.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.chat.FakeChatModel;
import io.github.clarence404.assistant.rag.RagFixtures;
import io.github.clarence404.assistant.rag.RagProperties;
import io.github.clarence404.assistant.rag.RagRetriever;
import io.github.clarence404.assistant.rag.RetrievalEval;
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
 * 准不准看 docs/ch04.md 里两个模型的实测对比。第 5 章加了检索评测（hit@k），对比只用向量与混合检索。
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

    @Autowired
    KnowledgeStore store;

    @Autowired
    RagProperties ragProperties;

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

    /**
     * 第 5 章：真实向量模型上的检索评测（hit@k），对比只用向量检索和混合检索（关键词 BM25 + 向量，RRF 融合）。评测集见
     * {@link RetrievalEval}；结果打进日志，docs/ch05.md 的对比表就是这样跑出来的（CI 上是 nomic-embed-text，开发机上用
     * {@code OLLAMA_EMBEDDING_IT_MODEL=bge-m3} 再跑一遍）。
     *
     * <p>排序评测不设门槛（-1）；另外按 application.yml 的默认门槛（min-score 0.6，按 bge-m3 定的）再跑一遍混合检索，看门槛会不会误伤。
     */
    @Test
    void hybridRetrievalBeatsVectorOnlyOnColloquialQuestions() {
        ingestion.ingest(false);
        RagRetriever vector = new RagRetriever(search, store, RagFixtures.properties(-1, false));
        RagRetriever hybrid = new RagRetriever(search, store, RagFixtures.properties(-1, true));
        RagRetriever defaults = new RagRetriever(search, store, ragProperties);

        for (int k : new int[] {1, 3, 4}) {
            RetrievalEval.Result v = RetrievalEval.evaluate(MODEL + " 只用向量", vector, k);
            RetrievalEval.Result h = RetrievalEval.evaluate(MODEL + " 混合检索", hybrid, k);
            log.info(v.summary());
            log.info(h.summary());
            assertThat(h.hits()).as("hit@" + k + "：混合检索不比只用向量差").isGreaterThanOrEqualTo(v.hits());
        }
        RetrievalEval.Result h4 = RetrievalEval.evaluate(MODEL + " 混合检索", hybrid, 4);
        log.info("{} 混合检索各题名次：{}", MODEL, h4.ranks());
        RetrievalEval.Result d4 = RetrievalEval.evaluate(MODEL + " 混合检索 + 默认门槛", defaults, 4);
        log.info(d4.summary());
        assertThat(h4.hitRate()).as("混合检索 hit@4").isGreaterThanOrEqualTo(0.5);
        if (MODEL.startsWith("bge-m3")) {
            assertThat(RetrievalEval.evaluate(MODEL + " 混合检索", hybrid, 1).hitRate())
                    .isGreaterThanOrEqualTo(0.9);
            assertThat(d4.misses()).doesNotContain("东西坏了能退吗");
        }
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
