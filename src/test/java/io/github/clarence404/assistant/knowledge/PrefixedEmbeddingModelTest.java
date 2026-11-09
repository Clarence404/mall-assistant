package io.github.clarence404.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingOptions;

/**
 * 第 4 章 ch04-7：按模型加查询 / 文档前缀（{@code PrefixedEmbeddingModel}）。
 *
 * <p>测试思路：假模型会记下收到的原始文字，看前缀加得对不对：nomic-embed-text 检索时是 {@code search_query: 问题}，入库时是
 * {@code search_document: 块文字}，三条入库路径（SimpleVectorStore 的逐块、PgVectorStore 的批量）都要加；库里存的块文字不带前缀；
 * bge-m3 什么都不加。
 */
class PrefixedEmbeddingModelTest {

    @TempDir
    Path dir;

    @Test
    void queryAndDocumentPrefixesAreAddedOnTheRightPaths() {
        FakeEmbeddingModel raw = new FakeEmbeddingModel();
        PrefixedEmbeddingModel model = new PrefixedEmbeddingModel(raw, "search_query: ", "search_document: ");
        Document chunk = new Document("退换货规则\n七天无理由");

        model.embed("七天无理由退货");
        model.embed(chunk);
        // PgVectorStore 的批量路径；最后一个参数是分批策略，这里用「不分批」的 lambda 代替默认按 token 数分批的实现
        model.embed(List.of(chunk), EmbeddingOptions.builder().build(), docs -> List.of(docs));

        assertThat(raw.calls())
                .containsExactly(
                        "search_query: 七天无理由退货", "search_document: 退换货规则\n七天无理由", "search_document: 退换货规则\n七天无理由");
        assertThat(model.getEmbeddingContent(chunk)).isEqualTo("search_document: 退换货规则\n七天无理由");
        assertThat(chunk.getText()).isEqualTo("退换货规则\n七天无理由");
    }

    /** 整条流水线：nomic 模型入库时每块都带文档前缀，检索时问题带查询前缀，库里的块文字和检索结果都不带前缀。 */
    @Test
    void nomicPrefixesFlowThroughIngestAndSearch() throws Exception {
        KnowledgeFixtures.copyKnowledge(dir);
        FakeEmbeddingModel raw = new FakeEmbeddingModel();
        KnowledgeFixtures.Kit kit = KnowledgeFixtures.kit(
                KnowledgeFixtures.properties(KnowledgeFixtures.location(dir), "nomic-embed-text:v1.5"), raw, null);

        kit.ingestion().ingest(false);
        assertThat(raw.calls()).isNotEmpty().allSatisfy(t -> assertThat(t).startsWith("search_document: "));
        raw.reset();

        List<KnowledgeHit> hits = kit.search().search("七天无理由退货", 2);

        assertThat(raw.calls()).containsExactly("search_query: 七天无理由退货");
        assertThat(hits).allSatisfy(h -> assertThat(h.content()).doesNotStartWith("search_"));
    }

    @Test
    void bgeM3HasNoPrefix() throws Exception {
        KnowledgeFixtures.copyKnowledge(dir);
        FakeEmbeddingModel raw = new FakeEmbeddingModel();
        KnowledgeFixtures.Kit kit = KnowledgeFixtures.kit(
                KnowledgeFixtures.properties(KnowledgeFixtures.location(dir), "bge-m3"), raw, null);

        kit.ingestion().ingest(false);
        kit.search().search("七天无理由退货", 2);

        assertThat(kit.embedding().profile().queryPrefix()).isEmpty();
        assertThat(raw.calls()).contains("七天无理由退货").noneMatch(t -> t.startsWith("search_"));
    }
}
