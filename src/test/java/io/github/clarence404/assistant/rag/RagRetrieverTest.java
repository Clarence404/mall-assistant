package io.github.clarence404.assistant.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.knowledge.FakeEmbeddingModel;
import io.github.clarence404.assistant.knowledge.KnowledgeException;
import io.github.clarence404.assistant.knowledge.KnowledgeFailure;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 第 5 章 TODO ch05-3：两路检索融合后按门槛筛选、取前 topK；顺带验证检索器把两路结果、关键词索引、整理成来源串起来了。
 *
 * <p>手法：不启动 Spring，假向量模型 + 6 篇真实规则文档入库（{@link RagFixtures#fakeKnowledgeBase()}）；门槛用假模型的口径
 * （{@link RagFixtures#FAKE_MIN_SCORE}）。{@link #select} 的几个用例直接造候选，分数随意定，结果可以一眼看出来。
 */
class RagRetrieverTest {

    private static RagFixtures.Kb kb;

    @BeforeAll
    static void ingest() {
        kb = RagFixtures.fakeKnowledgeBase();
    }

    private static RagCandidate candidate(String key, Double vector, Double coverage) {
        RagChunk chunk = new RagChunk(key, "路径\n正文", key, "标题", "路径 > " + key, 0);
        return new RagCandidate(chunk, vector, coverage == null ? null : 1.0, coverage, 0.01, List.of());
    }

    @Test
    void selectKeepsCandidatesAboveEitherThresholdInOrder() {
        RagRetriever retriever = RagFixtures.retriever(kb, RagFixtures.properties(0.6, true));
        List<RagCandidate> ranked = List.of(
                candidate("低分向量", 0.55, null),
                candidate("高分向量", 0.65, null),
                candidate("高覆盖关键词", null, 0.7),
                candidate("低覆盖关键词", null, 0.4),
                candidate("向量低但覆盖高", 0.2, 0.6),
                candidate("都低", 0.3, 0.3));

        assertThat(retriever.select(ranked, 10))
                .extracting(c -> c.chunk().key())
                .containsExactly("高分向量", "高覆盖关键词", "向量低但覆盖高");
    }

    @Test
    void selectStopsAtTopK() {
        RagRetriever retriever = RagFixtures.retriever(kb, RagFixtures.properties(0.6, true));
        List<RagCandidate> ranked =
                List.of(candidate("a", 0.9, null), candidate("b", 0.8, null), candidate("c", 0.7, null));

        assertThat(retriever.select(ranked, 2)).extracting(c -> c.chunk().key()).containsExactly("a", "b");
    }

    @Test
    void nothingRelevantGivesEmptySelection() {
        RagRetriever retriever = RagFixtures.retriever(kb, RagFixtures.properties(0.6, true));

        assertThat(retriever.select(List.of(candidate("a", 0.1, 0.1)), 4)).isEmpty();
        assertThat(retriever.select(List.of(), 4)).isEmpty();
    }

    /** 混合检索：「东西坏了能退吗」排第一的是「质量问题退换货」，而且被两路检索都找到了。 */
    @Test
    void hybridRetrievalNumbersRelevantSections() {
        RagRetriever retriever = RagFixtures.retriever(kb, RagFixtures.properties());

        RagRetriever.Retrieval retrieval = retriever.retrieve("东西坏了能退吗");

        assertThat(retrieval.vectorCandidates()).isEqualTo(10);
        assertThat(retrieval.keywordCandidates()).isPositive();
        assertThat(retrieval.sources()).isNotEmpty().hasSizeLessThanOrEqualTo(4);
        assertThat(retrieval.sources()).extracting(RagSource::index).startsWith(1);
        RagSource first = retrieval.sources().getFirst();
        assertThat(first.headingPath()).contains("质量问题退换货");
        assertThat(first.foundBy()).contains(RagRetriever.VECTOR, RagRetriever.KEYWORD);
    }

    /** 只用向量检索时没有关键词候选，来源都是 vector 找到的。 */
    @Test
    void vectorOnlyModeSkipsKeywordSearch() {
        RagRetriever retriever = RagFixtures.retriever(kb, RagFixtures.properties(RagFixtures.FAKE_MIN_SCORE, false));

        RagRetriever.Retrieval retrieval = retriever.retrieve("七天无理由退货要什么条件");

        assertThat(retrieval.keywordCandidates()).isZero();
        assertThat(retrieval.sources()).allSatisfy(s -> assertThat(s.foundBy()).containsExactly(RagRetriever.VECTOR));
    }

    /** 和知识库毫不相干的问题：向量检索照样返回「最像的 10 块」，但都过不了门槛，最后一块都不交给模型。 */
    @Test
    void unrelatedQuestionPassesNoThreshold() {
        RagRetriever retriever = RagFixtures.retriever(kb, RagFixtures.properties());

        RagRetriever.Retrieval retrieval = retriever.retrieve("火星探测器");

        assertThat(retrieval.vectorCandidates()).isEqualTo(10);
        assertThat(retrieval.sources()).isEmpty();
    }

    /** 重新入库（文档变了）后，关键词索引按新的块重建；没变时复用同一个索引。 */
    @Test
    void keywordIndexIsRebuiltOnlyWhenStoreChanges() {
        RagFixtures.Kb fresh = RagFixtures.fakeKnowledgeBase();
        RagRetriever retriever = RagFixtures.retriever(fresh, RagFixtures.properties());

        KeywordIndex first = retriever.keywordIndex();
        assertThat(retriever.keywordIndex()).isSameAs(first);

        fresh.store().reset();
        assertThat(retriever.keywordIndex().size()).isZero();
    }

    @Test
    void embeddingFailureSurfacesAsKnowledgeException() {
        FakeEmbeddingModel broken = new FakeEmbeddingModel();
        RagFixtures.Kb kb = RagFixtures.knowledgeBase(broken, "fake-ngram", RagFixtures.KNOWLEDGE);
        kb.ingestion().ingest(false);
        broken.failWith(new IllegalStateException("模型挂了"));
        RagRetriever retriever = RagFixtures.retriever(kb, RagFixtures.properties());

        assertThatThrownBy(() -> retriever.retrieve("东西坏了能退吗"))
                .isInstanceOfSatisfying(
                        KnowledgeException.class,
                        e -> assertThat(e.failure()).isEqualTo(KnowledgeFailure.EMBEDDING_UNAVAILABLE));
    }
}
