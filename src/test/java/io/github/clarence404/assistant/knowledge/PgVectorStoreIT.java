package io.github.clarence404.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.clarence404.assistant.chat.FakeChatModel;
import io.github.clarence404.assistant.rag.RagFixtures;
import io.github.clarence404.assistant.rag.RagRetriever;
import io.github.clarence404.assistant.rag.RagSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试：真实的 PostgreSQL + pgvector 跑 {@code PgVectorStore}（第 4 章 ch04-6 的验收测试）。
 *
 * <p>测什么：以 {@code store=pgvector} 启动整个应用，看 {@link PgVectorConfig} 建的连接池和 PgVectorStore 参数对不对——
 * 表名按模型生成、向量列的维度来自模型、建了余弦距离的 HNSW 索引；入库写进表里、文档改了按 source 过滤删除旧行、删掉的文档清干净；
 * 检索的分数和内存版（同一个假模型）一致，说明「1 − 余弦距离」的换算口径对得上；已有表的维度和模型对不上时报 INDEX_MODEL_MISMATCH。
 * 向量模型用不联网的 {@link FakeEmbeddingModel}，不依赖 Ollama。
 *
 * <p>数据库从哪来：有 Docker 时用 Testcontainers 起 {@code pgvector/pgvector:0.8.1-pg17}（CI 上就是这样）；本机没有 Docker 时，可以设置
 * 环境变量 {@code PGVECTOR_IT_URL} / {@code PGVECTOR_IT_USERNAME} / {@code PGVECTOR_IT_PASSWORD} 指向一个已有的 pgvector 数据库
 * （只建、删一张随机名字的表，不碰别的表）；两者都没有就跳过。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.ai.model.chat=none",
            "spring.ai.model.embedding=none",
            "assistant.knowledge.store=pgvector",
            "assistant.knowledge.embedding-model=fake-ngram",
            "assistant.knowledge.ingest-on-startup=false"
        })
@Import(PgVectorStoreIT.Models.class)
class PgVectorStoreIT {

    static final String IMAGE = "pgvector/pgvector:0.8.1-pg17";

    /** 每次运行一张新表，连共享数据库时不会和别人（或上一次运行）撞上。 */
    static final String TABLE = "knowledge_chunk_it_"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    /** 在 {@code @BeforeAll} 里准备：没有数据库时用 Assumptions 跳过整个类（写在静态字段初始化里会变成报错而不是跳过）。 */
    static Database db;

    static final Path DOCS = tempDocs();

    @Autowired
    KnowledgeIngestionService ingestion;

    @Autowired
    KnowledgeSearchService search;

    @Autowired
    PgVectorKnowledgeStore store;

    @Autowired
    FakeEmbeddingModel model;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("assistant.knowledge.pgvector.url", () -> db.url());
        registry.add("assistant.knowledge.pgvector.username", () -> db.username());
        registry.add("assistant.knowledge.pgvector.password", () -> db.password());
        registry.add("assistant.knowledge.pgvector.table", () -> TABLE);
        registry.add("assistant.knowledge.location", () -> KnowledgeFixtures.location(DOCS));
    }

    /** 每个测试从同一个状态开始：文档恢复成原样，清空重建。 */
    @BeforeEach
    void freshStore() throws IOException {
        try (Stream<Path> files = Files.list(DOCS)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        KnowledgeFixtures.copyKnowledge(DOCS);
        ingestion.ingest(true);
        model.reset();
    }

    /** JUnit 先执行它，再创建测试实例、启动 Spring 上下文（那时才读下面 {@code @DynamicPropertySource} 里的地址）。 */
    @BeforeAll
    static void startDatabase() {
        db = Database.start();
    }

    @AfterAll
    static void dropTable() {
        if (db != null) {
            db.jdbc().execute("DROP TABLE IF EXISTS public." + TABLE);
            db.stop();
        }
    }

    @Test
    void ingestCreatesTableWithDimensionsAndCosineHnswIndex() {
        JdbcTemplate jdbc = db.jdbc();

        Integer dims = jdbc.queryForObject(
                "SELECT atttypmod FROM pg_attribute WHERE attrelid = to_regclass(?) AND attname = 'embedding'",
                Integer.class,
                "public." + TABLE);
        assertThat(dims).isEqualTo(FakeEmbeddingModel.DEFAULT_DIMENSIONS);
        List<String> indexes = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = ? AND indexdef ILIKE '%hnsw%'", String.class, TABLE);
        assertThat(indexes).singleElement().asString().contains("vector_cosine_ops");

        Integer rows = jdbc.queryForObject("SELECT count(*) FROM public." + TABLE, Integer.class);
        KnowledgeStats stats = search.stats();
        assertThat(stats.store()).isEqualTo("pgvector");
        assertThat(stats.location()).isEqualTo("public." + TABLE);
        assertThat(stats.documentCount()).isEqualTo(6);
        assertThat(stats.chunkCount()).isEqualTo(rows).isGreaterThan(20);
    }

    @Test
    void incrementalIngestUpdatesAndDeletesBySource() throws IOException {
        Path returns = DOCS.resolve("returns.md");
        Files.writeString(
                returns,
                Files.readString(returns, StandardCharsets.UTF_8).replace("7 天内（含第 7 天）", "10 天内（含第 10 天）"),
                StandardCharsets.UTF_8);
        Files.delete(DOCS.resolve("shipping.md"));

        IngestReport report = ingestion.ingest(false);

        assertThat(report.documentsUpdated()).isEqualTo(1);
        assertThat(report.documentsRemoved()).isEqualTo(1);
        assertThat(report.documentsSkipped()).isEqualTo(4);
        JdbcTemplate jdbc = db.jdbc();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM public." + TABLE + " WHERE metadata->>'source' = 'shipping.md'",
                        Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM public." + TABLE + " WHERE content LIKE '%7 天内（含第 7 天）%'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM public." + TABLE + " WHERE content LIKE '%10 天内（含第 10 天）%'",
                        Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public." + TABLE, Integer.class))
                .isEqualTo(report.totalChunks());
    }

    /** 同一个假模型、同一个问题，pgvector 和内存版的相似度一样：两边的 score 都是余弦相似度（pgvector 是 1 − 余弦距离）。 */
    @Test
    void searchScoresMatchInMemoryCosineSimilarity() throws IOException {
        List<KnowledgeHit> pg = search.search("增值税专用发票怎么开", 3);

        Path memDocs = Files.createTempDirectory("kb-mem");
        KnowledgeFixtures.copyKnowledge(memDocs);
        KnowledgeFixtures.Kit memory = KnowledgeFixtures.kit(
                KnowledgeFixtures.properties(KnowledgeFixtures.location(memDocs), "fake-ngram"),
                new FakeEmbeddingModel(),
                null);
        memory.ingestion().ingest(false);
        List<KnowledgeHit> mem = memory.search().search("增值税专用发票怎么开", 3);

        assertThat(pg.getFirst().source()).isEqualTo("payment-invoice.md");
        assertThat(pg)
                .extracting(KnowledgeHit::headingPath)
                .isEqualTo(mem.stream().map(KnowledgeHit::headingPath).toList());
        for (int i = 0; i < pg.size(); i++) {
            assertThat(pg.get(i).score()).isCloseTo(mem.get(i).score(), within(0.001));
        }
    }

    /**
     * 第 5 章：pgvector 也能列出全部块（id、文字、元数据，不取向量列），关键词检索在内存里给它们建索引；混合检索在 pgvector 上的结果
     * 和内存版一致。
     */
    @Test
    void chunksFeedKeywordIndexAndHybridRetrievalLikeMemory() throws IOException {
        List<Document> chunks = store.chunks();

        Integer rows = db.jdbc().queryForObject("SELECT count(*) FROM public." + TABLE, Integer.class);
        assertThat(chunks).hasSize(rows);
        assertThat(chunks.getFirst().getMetadata()).containsKeys(ChunkMetadata.SOURCE, ChunkMetadata.HEADING_PATH);
        assertThat(chunks.getFirst().getMetadata().get(ChunkMetadata.CHUNK_INDEX))
                .isInstanceOf(Number.class);
        assertThat(chunks).extracting(Document::getText).doesNotContainNull();

        RagRetriever pg = new RagRetriever(search, store, RagFixtures.properties());
        RagRetriever mem = RagFixtures.retriever(RagFixtures.fakeKnowledgeBase(), RagFixtures.properties());
        List<RagSource> fromPg = pg.retrieve("东西坏了能退吗").sources();
        List<RagSource> fromMem = mem.retrieve("东西坏了能退吗").sources();

        assertThat(fromPg).isNotEmpty();
        assertThat(fromPg.getFirst().headingPath()).contains("质量问题退换货");
        assertThat(fromPg)
                .extracting(RagSource::headingPath)
                .isEqualTo(fromMem.stream().map(RagSource::headingPath).toList());
    }

    /** 表已经是 256 维，换成 128 维的模型还指到同一张表：不往里写，报 INDEX_MODEL_MISMATCH 提示重建。 */
    @Test
    void existingTableWithOtherDimensionsIsRejected() {
        KnowledgeProperties props = KnowledgeFixtures.properties(KnowledgeFixtures.location(DOCS), "fake-ngram");
        KnowledgeEmbedding narrow = new KnowledgeEmbedding(new FakeEmbeddingModel(128), props);
        JdbcTemplate jdbc = db.jdbc();
        PgVectorKnowledgeStore other = new PgVectorKnowledgeStore(
                jdbc,
                "public",
                TABLE,
                narrow,
                dims -> PgVectorConfig.createStore(jdbc, narrow, props.pgvector(), TABLE, dims));

        assertThatThrownBy(other::vectorStore)
                .isInstanceOf(KnowledgeException.class)
                .hasMessageContaining("256 维")
                .hasMessageContaining("128 维")
                .extracting(e -> ((KnowledgeException) e).failure())
                .isEqualTo(KnowledgeFailure.INDEX_MODEL_MISMATCH);
    }

    @Test
    void tableNameFollowsModelByDefault() {
        KnowledgeProperties props = KnowledgeFixtures.properties("classpath:knowledge/*.md", "nomic-embed-text:latest");
        KnowledgeEmbedding embedding = new KnowledgeEmbedding(new FakeEmbeddingModel(), props);

        assertThat(PgVectorConfig.tableName(props.pgvector(), embedding)).isEqualTo("knowledge_chunk_nomic_embed_text");
        assertThat(store.table()).isEqualTo(TABLE);
    }

    /** 测试用的模型：假对话模型（应用启动需要）+ 假向量模型。 */
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

    private static Path tempDocs() {
        try {
            return Files.createTempDirectory("kb-pgvector-it");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 数据库：Testcontainers 起的容器，或环境变量指定的现成数据库。 */
    record Database(PostgreSQLContainer container, String url, String username, String password) {

        static Database start() {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                PostgreSQLContainer container = new PostgreSQLContainer(
                                DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                        .withDatabaseName("mall_assistant")
                        .withUsername("mall")
                        .withPassword("mall");
                container.start();
                return new Database(container, container.getJdbcUrl(), "mall", "mall");
            }
            String url = System.getenv("PGVECTOR_IT_URL");
            Assumptions.assumeTrue(url != null && !url.isBlank(), "没有 Docker，也没有设置 PGVECTOR_IT_URL，跳过 pgvector 集成测试");
            return new Database(
                    null,
                    url,
                    System.getenv().getOrDefault("PGVECTOR_IT_USERNAME", "mall"),
                    System.getenv().getOrDefault("PGVECTOR_IT_PASSWORD", ""));
        }

        JdbcTemplate jdbc() {
            return new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        }

        void stop() {
            if (container != null) {
                container.stop();
            }
        }
    }
}
