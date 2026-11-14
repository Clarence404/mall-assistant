package io.github.clarence404.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 内存向量库的缓存（审查修复）：连着检索不重复算「第一块向量的维度」和「文档汇总」，库里的内容一变缓存就作废。
 *
 * <p>修复前：每次检索 {@link MemoryKnowledgeStore#vectorStore()} 都用 Jackson 把一整块（含向量）转成 Map 数维度，
 * {@link MemoryKnowledgeStore#documents()} 每次把全部块过一遍。
 */
class MemoryKnowledgeStoreTest {

    @TempDir
    Path docs;

    private KnowledgeFixtures.Kit kit;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(docs.resolve("a.md"), "# 甲\n\n## 第一节\n\n七天无理由退货的运费由买家承担。\n");
        Files.writeString(docs.resolve("b.md"), "# 乙\n\n## 第一节\n\n订单签收后十五天内可以申请换货。\n");
        kit = KnowledgeFixtures.kit(
                KnowledgeFixtures.properties(KnowledgeFixtures.location(docs), "bge-m3"),
                new FakeEmbeddingModel(16),
                null);
        kit.ingestion().ingest(false);
    }

    /** 连着检索三次：维度只算一次（修复前是每次检索一次）。 */
    @Test
    void dimensionsAreProbedOnceAcrossSearches() {
        MemoryKnowledgeStore.InspectableSimpleVectorStore inner =
                (MemoryKnowledgeStore.InspectableSimpleVectorStore) kit.store().vectorStore();
        long before = inner.dimensionProbes();

        kit.search().search("运费谁出", 2);
        kit.search().search("能换货吗", 2);
        kit.search().search("运费谁出", 2);

        assertThat(inner.dimensionProbes() - before).isLessThanOrEqualTo(1);
        assertThat(inner.firstDimensions()).isEqualTo(16);
    }

    /** 库没变时，文档汇总是同一个对象（没有重新算）；它同时是关键词索引的「签名」。 */
    @Test
    void documentsAreCachedWhileStoreIsUnchanged() {
        List<StoredDocument> first = kit.store().documents();

        assertThat(kit.store().documents()).isSameAs(first);
        assertThat(first).extracting(StoredDocument::source).containsExactly("a.md", "b.md");
    }

    /** 再入库（改了一篇、删了一篇）之后，汇总和维度都按新内容重新算，不会拿着旧缓存。 */
    @Test
    void cachesAreDroppedWhenContentChanges() throws IOException {
        List<StoredDocument> before = kit.store().documents();
        Files.writeString(docs.resolve("a.md"), "# 甲\n\n## 第一节\n\n改过了：运费由卖家承担。\n\n## 第二节\n\n新增的一节。\n");
        Files.delete(docs.resolve("b.md"));

        kit.ingestion().ingest(false);

        List<StoredDocument> after = kit.store().documents();
        assertThat(after).isNotSameAs(before);
        assertThat(after).extracting(StoredDocument::source).containsExactly("a.md");
        assertThat(after.getFirst().chunkCount()).isEqualTo(2);
        assertThat(after.getFirst().docHash()).isNotEqualTo(before.getFirst().docHash());
    }

    /** 清空重建之后缓存也清掉：空库的汇总是空列表。 */
    @Test
    void resetDropsCaches() {
        assertThat(kit.store().documents()).isNotEmpty();

        kit.store().reset();

        assertThat(kit.store().documents()).isEmpty();
    }
}
