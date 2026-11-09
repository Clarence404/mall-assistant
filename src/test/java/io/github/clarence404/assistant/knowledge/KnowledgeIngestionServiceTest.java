package io.github.clarence404.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

/**
 * 第 4 章 ch04-4（连同 ch04-2、ch04-3）：增量入库（{@code KnowledgeIngestionService#apply}）。
 *
 * <p>测试思路：不起 Spring，用内存向量库（SimpleVectorStore）+ 假向量模型 {@link FakeEmbeddingModel}，文档放在临时目录里，
 * 这样可以改文档、删文档。每一步看三样：入库报告的计数、库里实际的块（{@code store.documents()} 与检索）、模型被调了几次
 * （没变的文档不能调模型——这正是增量的意义）。
 */
class KnowledgeIngestionServiceTest {

    @TempDir
    Path dir;

    private FakeEmbeddingModel model;
    private KnowledgeFixtures.Kit kit;
    private int docCount;

    @BeforeEach
    void setUp() throws Exception {
        docCount = KnowledgeFixtures.copyKnowledge(dir);
        model = new FakeEmbeddingModel();
        kit = KnowledgeFixtures.kit(
                KnowledgeFixtures.properties(KnowledgeFixtures.location(dir), "fake-ngram"), model, null);
    }

    @Test
    void firstIngestAddsEveryDocument() {
        IngestReport report = kit.ingestion().ingest(false);

        assertThat(report.documentsAdded()).isEqualTo(docCount).isEqualTo(6);
        assertThat(report.documentsUpdated()).isZero();
        assertThat(report.documentsSkipped()).isZero();
        assertThat(report.chunksAdded()).isEqualTo(report.totalChunks()).isGreaterThan(20);
        assertThat(report.totalDocuments()).isEqualTo(6);
        assertThat(report.embeddingModel()).isEqualTo("fake-ngram");
        assertThat(report.store()).isEqualTo("memory");
        assertThat(report.documents()).allSatisfy(c -> assertThat(c.action()).isEqualTo(IngestReport.Action.ADDED));
        // 每块调一次模型（SimpleVectorStore 逐块向量化）
        assertThat(model.calls()).hasSize(report.chunksAdded());
        assertThat(kit.store().documents())
                .extracting(StoredDocument::embeddingModel)
                .containsOnly("fake-ngram");
    }

    @Test
    void unchangedDocumentsAreSkippedWithoutCallingTheModel() {
        IngestReport first = kit.ingestion().ingest(false);
        model.reset();

        IngestReport second = kit.ingestion().ingest(false);

        assertThat(second.documentsSkipped()).isEqualTo(6);
        assertThat(second.documentsAdded() + second.documentsUpdated() + second.documentsRemoved())
                .isZero();
        assertThat(second.chunksAdded()).isZero();
        assertThat(second.totalChunks()).isEqualTo(first.totalChunks());
        assertThat(model.calls()).as("没变的文档不调向量模型").isEmpty();
    }

    /** 文档变了：先删旧块再写新块，旧内容在库里找不到了，块数按新版本算，其他文档不动。 */
    @Test
    void changedDocumentReplacesItsOldChunks() throws Exception {
        IngestReport first = kit.ingestion().ingest(false);
        Path returns = dir.resolve("returns.md");
        String text = Files.readString(returns, StandardCharsets.UTF_8);
        // 删掉后半部分（块数变少，只覆盖不删除会留下多余的旧块），并改掉一句话
        String edited = text.substring(0, text.indexOf("## 质量问题退换货")).replace("7 天内（含第 7 天）", "10 天内（含第 10 天）");
        Files.writeString(returns, edited, StandardCharsets.UTF_8);
        model.reset();

        IngestReport second = kit.ingestion().ingest(false);

        assertThat(second.documentsUpdated()).isEqualTo(1);
        assertThat(second.documentsSkipped()).isEqualTo(5);
        IngestReport.DocumentChange change = second.documents().stream()
                .filter(c -> c.source().equals("returns.md"))
                .findFirst()
                .orElseThrow();
        assertThat(change.action()).isEqualTo(IngestReport.Action.UPDATED);
        int oldChunks = first.documents().stream()
                .filter(c -> c.source().equals("returns.md"))
                .findFirst()
                .orElseThrow()
                .chunksAdded();
        assertThat(change.chunksRemoved()).isEqualTo(oldChunks);
        assertThat(change.chunksAdded()).isLessThan(oldChunks);
        assertThat(model.calls()).hasSize(change.chunksAdded());

        List<Document> returnsChunks = allChunksOf("returns.md");
        assertThat(returnsChunks).hasSize(change.chunksAdded());
        assertThat(returnsChunks).noneMatch(d -> d.getText().contains("7 天内（含第 7 天）"));
        assertThat(returnsChunks).anyMatch(d -> d.getText().contains("10 天内（含第 10 天）"));
        assertThat(second.totalChunks()).isEqualTo(first.totalChunks() - oldChunks + change.chunksAdded());
    }

    @Test
    void deletedDocumentIsRemovedFromStore() throws Exception {
        IngestReport first = kit.ingestion().ingest(false);
        Files.delete(dir.resolve("shipping.md"));

        IngestReport second = kit.ingestion().ingest(false);

        assertThat(second.documentsRemoved()).isEqualTo(1);
        assertThat(second.totalDocuments()).isEqualTo(5);
        assertThat(kit.store().documents()).extracting(StoredDocument::source).doesNotContain("shipping.md");
        assertThat(allChunksOf("shipping.md")).isEmpty();
        assertThat(second.chunksRemoved()).isEqualTo(first.totalChunks() - second.totalChunks());
    }

    /** 持久化：入库后存成 JSON，新的库实例读回来，再入库全部跳过、不调模型（相当于重启应用）。 */
    @Test
    void persistedStoreSurvivesRestart() {
        Path file = dir.resolve("store").resolve("kb.json");
        KnowledgeProperties props = KnowledgeFixtures.properties(KnowledgeFixtures.location(dir), "fake-ngram");
        IngestReport first =
                KnowledgeFixtures.kit(props, model, file).ingestion().ingest(false);
        assertThat(file).exists();
        model.reset();

        KnowledgeFixtures.Kit restarted = KnowledgeFixtures.kit(props, model, file);
        IngestReport second = restarted.ingestion().ingest(false);

        assertThat(second.documentsSkipped()).isEqualTo(6);
        assertThat(second.totalChunks()).isEqualTo(first.totalChunks());
        assertThat(model.calls()).isEmpty();
    }

    /** 库里是别的模型生成的块：拒绝入库并提示重建；rebuild=true 清空后用当前模型全部重来。 */
    @Test
    void otherModelInStoreRequiresRebuild() {
        Path file = dir.resolve("shared.json");
        KnowledgeFixtures.kit(KnowledgeFixtures.properties(KnowledgeFixtures.location(dir), "model-a"), model, file)
                .ingestion()
                .ingest(false);

        KnowledgeFixtures.Kit other = KnowledgeFixtures.kit(
                KnowledgeFixtures.properties(KnowledgeFixtures.location(dir), "model-b"), model, file);

        assertThatThrownBy(() -> other.ingestion().ingest(false))
                .isInstanceOf(KnowledgeException.class)
                .hasMessageContaining("model-a")
                .hasMessageContaining("rebuild=true")
                .extracting(e -> ((KnowledgeException) e).failure())
                .isEqualTo(KnowledgeFailure.INDEX_MODEL_MISMATCH);

        IngestReport rebuilt = other.ingestion().ingest(true);
        assertThat(rebuilt.rebuild()).isTrue();
        assertThat(rebuilt.documentsAdded()).isEqualTo(6);
        assertThat(other.store().documents())
                .extracting(StoredDocument::embeddingModel)
                .containsOnly("model-b");
    }

    /** 同一个文件里的向量维度和当前模型对不上（比如手动改了模型名后缀）：报 INDEX_MODEL_MISMATCH。 */
    @Test
    void dimensionMismatchIsDetected() {
        Path file = dir.resolve("dims.json");
        KnowledgeProperties props = KnowledgeFixtures.properties(KnowledgeFixtures.location(dir), "fake-ngram");
        KnowledgeFixtures.kit(props, new FakeEmbeddingModel(256), file)
                .ingestion()
                .ingest(false);

        KnowledgeFixtures.Kit wider = KnowledgeFixtures.kit(props, new FakeEmbeddingModel(128), file);

        assertThatThrownBy(() -> wider.search().search("退货", 3))
                .isInstanceOf(KnowledgeException.class)
                .hasMessageContaining("256 维")
                .hasMessageContaining("128 维");
    }

    @Test
    void modelFailureIsReportedAsEmbeddingUnavailable() {
        model.failWith(new IllegalStateException("Connection refused"));

        assertThatThrownBy(() -> kit.ingestion().ingest(false))
                .isInstanceOf(KnowledgeException.class)
                .extracting(e -> ((KnowledgeException) e).failure())
                .isEqualTo(KnowledgeFailure.EMBEDDING_UNAVAILABLE);
    }

    /** 用过滤条件 source == x 在库里找出这篇文档的全部块（相似度阈值放到最低，topK 足够大）。 */
    private List<Document> allChunksOf(String source) {
        return kit.store()
                .vectorStore()
                .similaritySearch(SearchRequest.builder()
                        .query("任意")
                        .topK(1000)
                        .similarityThresholdAll()
                        .filterExpression(KnowledgeIngestionService.bySource(source))
                        .build());
    }
}
