package io.github.clarence404.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 第 4 章 ch04-5：相似度检索与分数换算（{@code KnowledgeSearchService#similaritySearch}）。
 *
 * <p>测试思路：把真实的 6 篇知识文档入库（假模型按字面相似度打分），问几个字面上能对上的问题，断言最相关的那块排第一、来源和标题路径
 * 都对；再检查分数的口径：score 在 0～1 之间、从高到低、保留 4 位小数，distance = 1 − score；topK 的默认值与上限。
 */
class KnowledgeSearchServiceTest {

    @TempDir
    Path dir;

    private KnowledgeFixtures.Kit kit;

    @BeforeEach
    void setUp() throws Exception {
        KnowledgeFixtures.copyKnowledge(dir);
        kit = KnowledgeFixtures.kit(
                KnowledgeFixtures.properties(KnowledgeFixtures.location(dir), "fake-ngram"),
                new FakeEmbeddingModel(),
                null);
        kit.ingestion().ingest(false);
    }

    /**
     * 「七天无理由退货的适用条件」：排第一的是退换货规则「七天无理由退货 > 适用条件」那一块，来源、标题、版本都带着。
     * 假模型只认字面，问句要和标题用词接近；真实的 bge-m3 问「七天无理由退货要什么条件」也能排第一（见 docs/ch04.md 的实测）。
     */
    @Test
    void sevenDayReturnQuestionFindsTheConditionsChunk() {
        List<KnowledgeHit> hits = kit.search().search("七天无理由退货的适用条件", 4);

        KnowledgeHit top = hits.getFirst();
        assertThat(top.headingPath()).isEqualTo("退换货规则 > 七天无理由退货 > 适用条件");
        assertThat(top.source()).isEqualTo("returns.md");
        assertThat(top.title()).isEqualTo("退换货规则");
        assertThat(top.content()).startsWith(top.headingPath() + "\n").contains("自签收次日起 7 天内");
        assertThat(top.chunkIndex()).isNotNull();
        assertThat(top.docVersion()).isEqualTo("2026.10");
    }

    @Test
    void otherTopicsRankTheirOwnDocumentsFirst() {
        assertThat(kit.search().search("增值税专用发票怎么开", 3).getFirst().source()).isEqualTo("payment-invoice.md");
        assertThat(kit.search().search("连续输错密码账户锁定", 3).getFirst().source()).isEqualTo("account-security.md");
        assertThat(kit.search().search("偏远地区运费标准", 3).getFirst().source()).isEqualTo("shipping.md");
    }

    @Test
    void scoresAreCosineSimilarityRoundedAndSorted() {
        List<KnowledgeHit> hits = kit.search().search("积分有效期多久", 5);

        assertThat(hits).hasSize(5);
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeHit hit = hits.get(i);
            assertThat(hit.score()).isBetween(0.0, 1.0);
            assertThat(BigDecimal.valueOf(hit.score()).scale()).as("保留 4 位小数").isLessThanOrEqualTo(4);
            assertThat(hit.distance()).isEqualTo(Math.round((1 - hit.score()) * 10_000) / 10_000.0);
            if (i > 0) {
                assertThat(hit.score()).isLessThanOrEqualTo(hits.get(i - 1).score());
            }
        }
    }

    @Test
    void topKDefaultsAndIsClamped() {
        assertThat(kit.search().search("退货", null)).hasSize(4);
        assertThat(kit.search().search("退货", 100)).hasSize(10);
        assertThat(kit.search().search("退货", 0)).hasSize(1);
    }

    @Test
    void statsCountDocumentsAndChunks() {
        KnowledgeStats stats = kit.search().stats();

        assertThat(stats.documentCount()).isEqualTo(6);
        assertThat(stats.chunkCount())
                .isEqualTo(stats.documents().stream()
                        .mapToInt(StoredDocument::chunkCount)
                        .sum());
        assertThat(stats.embeddingModel()).isEqualTo("fake-ngram");
        assertThat(stats.dimensions()).isEqualTo(FakeEmbeddingModel.DEFAULT_DIMENSIONS);
        assertThat(stats.store()).isEqualTo("memory");
        assertThat(stats.warning()).isNull();
    }
}
