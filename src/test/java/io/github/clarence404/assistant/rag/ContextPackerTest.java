package io.github.clarence404.assistant.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 第 5 章 TODO ch05-4：去重、合并同一节的相邻块、上下文预算、编号与片段。 */
class ContextPackerTest {

    private static RagCandidate candidate(String source, int index, String headingPath, String body, Double score) {
        RagChunk chunk =
                new RagChunk(RagChunk.key(source, index), headingPath + "\n" + body, source, "标题", headingPath, index);
        return new RagCandidate(chunk, score, null, null, score == null ? 0.01 : score, List.of(RagRetriever.VECTOR));
    }

    private static RagCandidate keywordOnly(String source, int index, String headingPath, String body) {
        RagChunk chunk =
                new RagChunk(RagChunk.key(source, index), headingPath + "\n" + body, source, "标题", headingPath, index);
        return new RagCandidate(chunk, null, 3.5, 0.8, 0.0164, List.of(RagRetriever.KEYWORD));
    }

    @Test
    void numbersSourcesInOrderWithSnippetAndContent() {
        List<RagSource> sources = ContextPacker.pack(
                List.of(
                        candidate("returns.md", 3, "退换货规则 > 质量问题退换货", "签收后 15 天内发现质量问题可以退换。", 0.81234),
                        candidate("shipping.md", 0, "配送与运费 > 发货时效", "现货商品付款后 24 小时内发货。", 0.7)),
                1500,
                8);

        assertThat(sources).extracting(RagSource::index).containsExactly(1, 2);
        RagSource first = sources.getFirst();
        assertThat(first.source()).isEqualTo("returns.md");
        assertThat(first.headingPath()).isEqualTo("退换货规则 > 质量问题退换货");
        assertThat(first.content()).isEqualTo("退换货规则 > 质量问题退换货\n签收后 15 天内发现质量问题可以退换。");
        assertThat(first.snippet()).isEqualTo("签收后 15 天…");
        assertThat(first.chunkIndexes()).containsExactly(3);
        assertThat(first.score()).isEqualTo(0.81234);
        assertThat(first.fusedScore()).isEqualTo(0.8123);
        assertThat(first.foundBy()).containsExactly(RagRetriever.VECTOR);
    }

    @Test
    void duplicateKeysAreDropped() {
        RagCandidate a = candidate("a.md", 0, "A > 一", "第一节。", 0.8);

        assertThat(ContextPacker.pack(List.of(a, a), 1500, 80)).hasSize(1);
    }

    /** 同一节被切成两块、相邻两块重叠了一段：合成一条，重叠只留一份，序号都记下；分数取最大，检索器取并集。 */
    @Test
    void adjacentChunksOfSameSectionAreMerged() {
        String path = "退换货规则 > 七天无理由退货 > 适用条件";
        RagCandidate second = candidate("returns.md", 2, path, "未经使用、未经洗涤。提供订单号，在我的订单中申请。", 0.75);
        RagCandidate other = candidate("shipping.md", 5, "配送与运费 > 修改地址", "发货前可以修改一次地址。", 0.7);
        RagCandidate first = keywordOnly("returns.md", 1, path, "自签收次日起 7 天内可以申请。未经使用、未经洗涤。");

        List<RagSource> sources = ContextPacker.pack(List.of(second, other, first), 1500, 80);

        assertThat(sources).hasSize(2);
        RagSource merged = sources.getFirst();
        assertThat(merged.index()).isEqualTo(1);
        assertThat(merged.chunkIndexes()).containsExactly(1, 2);
        assertThat(merged.content()).isEqualTo(path + "\n自签收次日起 7 天内可以申请。未经使用、未经洗涤。提供订单号，在我的订单中申请。");
        assertThat(merged.score()).isEqualTo(0.75);
        assertThat(merged.keywordScore()).isEqualTo(3.5);
        assertThat(merged.foundBy()).containsExactly(RagRetriever.VECTOR, RagRetriever.KEYWORD);
        assertThat(sources.get(1).index()).isEqualTo(2);
    }

    /** 不同节的相邻块（序号挨着但标题路径不同）不合并；同一节但不挨着的也不合并。 */
    @Test
    void onlySameSectionAndAdjacentAreMerged() {
        List<RagSource> sources = ContextPacker.pack(
                List.of(
                        candidate("returns.md", 1, "退换货规则 > 甲", "甲。", 0.8),
                        candidate("returns.md", 2, "退换货规则 > 乙", "乙。", 0.7),
                        candidate("returns.md", 4, "退换货规则 > 甲", "甲又一段。", 0.6)),
                1500,
                80);

        assertThat(sources).hasSize(3);
    }

    @Test
    void joinRemovesOverlapOnlyWhenLongEnough() {
        assertThat(ContextPacker.join("一二三四五六七八九", "五六七八九十")).isEqualTo("一二三四五六七八九十");
        assertThat(ContextPacker.join("甲乙丙", "丙丁")).isEqualTo("甲乙丙\n丙丁");
    }

    /** 预算：放不下的条目连同后面的都不要；第一条再长也保留，截断加「…」。 */
    @Test
    void budgetStopsAddingAndTruncatesOnlyFirst() {
        RagCandidate a = candidate("a.md", 0, "A", "一".repeat(60), 0.9);
        RagCandidate b = candidate("b.md", 0, "B", "二".repeat(60), 0.8);
        RagCandidate c = candidate("c.md", 0, "C", "三".repeat(10), 0.7);

        List<RagSource> two = ContextPacker.pack(List.of(a, b, c), 130, 80);
        assertThat(two).extracting(RagSource::source).containsExactly("a.md", "b.md");

        // 放不下 c（62 + 12 > 63）就停在这里
        List<RagSource> one = ContextPacker.pack(List.of(a, c), 63, 80);
        assertThat(one).extracting(RagSource::source).containsExactly("a.md");

        List<RagSource> stop = ContextPacker.pack(List.of(a, b, c), 70, 80);
        assertThat(stop).extracting(RagSource::source).containsExactly("a.md");

        List<RagSource> truncated = ContextPacker.pack(List.of(a), 20, 80);
        assertThat(truncated.getFirst().content()).hasSize(21).endsWith("…");
    }

    @Test
    void emptySelectionGivesNoSources() {
        assertThat(ContextPacker.pack(List.of(), 1500, 80)).isEmpty();
    }
}
