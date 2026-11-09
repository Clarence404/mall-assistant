package io.github.clarence404.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 第 4 章 ch04-3：增量判断（{@code IngestPlanner#plan}）。
 *
 * <p>测试思路：不碰向量库，直接构造「磁盘上的文档」和「库里的汇总」，看四类各归对了没有：库里没有 → 新增；哈希一样 → 跳过；
 * 哈希不同 → 更新（带上旧汇总）；磁盘上没了 → 删除。
 */
class IngestPlannerTest {

    private final ChunkDocumentFactory factory = new ChunkDocumentFactory(new MarkdownChunker(300, 50), "bge-m3", "");
    private final IngestPlanner planner = new IngestPlanner(factory);

    private static KnowledgeDocument doc(String source, String body) {
        return MarkdownKnowledgeReader.parse(source, "---\ntitle: " + source + "\n---\n# " + source + "\n" + body);
    }

    private StoredDocument stored(KnowledgeDocument doc, int chunks) {
        return new StoredDocument(doc.source(), doc.title(), "", factory.docHash(doc), "bge-m3", chunks);
    }

    @Test
    void classifiesAddedUpdatedUnchangedAndRemoved() {
        KnowledgeDocument a = doc("a.md", "不变的内容");
        KnowledgeDocument b = doc("b.md", "新内容");
        KnowledgeDocument c = doc("c.md", "全新的文档");
        StoredDocument oldB = new StoredDocument("b.md", "b.md", "", "旧的哈希", "bge-m3", 3);
        StoredDocument gone = new StoredDocument("z.md", "z.md", "", "随便", "bge-m3", 2);

        IngestPlan plan = planner.plan(List.of(a, b, c), List.of(stored(a, 1), oldB, gone));

        assertThat(plan.added()).containsExactly(c);
        assertThat(plan.unchanged()).containsExactly(a);
        assertThat(plan.updated()).containsExactly(new IngestPlan.Replacement(b, oldB));
        assertThat(plan.removed()).containsExactly(gone);
    }

    @Test
    void emptyStoreAddsEverything() {
        KnowledgeDocument a = doc("a.md", "内容");

        IngestPlan plan = planner.plan(List.of(a), List.of());

        assertThat(plan.added()).containsExactly(a);
        assertThat(plan.updated()).isEmpty();
        assertThat(plan.unchanged()).isEmpty();
        assertThat(plan.removed()).isEmpty();
    }

    @Test
    void noDocumentsRemovesEverything() {
        StoredDocument gone = new StoredDocument("a.md", "a", "", "h", "bge-m3", 4);

        IngestPlan plan = planner.plan(List.of(), List.of(gone));

        assertThat(plan.removed()).containsExactly(gone);
        assertThat(plan.added()).isEmpty();
    }
}
