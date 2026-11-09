package io.github.clarence404.assistant.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 第 5 章 TODO ch05-1：BM25 关键词检索。
 *
 * <p>手法：几块手写的短文本，结果可以手算；最后一个用例用 6 篇真实的规则文档，验证「东西坏了能退吗」这种和原文用词不同的问题，
 * 关键词检索至少能把「质量问题退换货」排进前几（它和向量检索互补，见 {@link RetrievalEvalTest}）。
 */
class KeywordIndexTest {

    private static RagChunk chunk(String source, int index, String headingPath, String body) {
        return new RagChunk(RagChunk.key(source, index), headingPath + "\n" + body, source, "标题", headingPath, index);
    }

    private static final List<RagChunk> CHUNKS = List.of(
            chunk("a.md", 0, "支付与发票 > 开票时间", "电子普通发票在订单完成后 1 个工作日内开具。"),
            chunk("a.md", 1, "支付与发票 > 支付方式", "支持微信支付、支付宝和银行卡。"),
            chunk("b.md", 0, "积分与会员 > 积分有效期", "积分有效期为 12 个月，到期自动清零。"),
            chunk("b.md", 1, "积分与会员 > 积分使用", "100 积分抵 1 元。"));

    @Test
    void ranksChunkContainingRareTermsFirst() {
        KeywordIndex index = KeywordIndex.build(CHUNKS);

        List<KeywordIndex.KeywordHit> hits = index.search("发票什么时候能开", 10);

        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().chunk().key()).isEqualTo("a.md#0");
        assertThat(hits).isSortedAccordingTo((x, y) -> Double.compare(y.score(), x.score()));
    }

    /** 手算一个最简单的情况：两块里只有一块含「清零」，问题只有「清零」三个词项（清、清零、零）。 */
    @Test
    void scoreFollowsBm25Formula() {
        KeywordIndex index = KeywordIndex.build(CHUNKS);

        List<KeywordIndex.KeywordHit> hits = index.search("清零", 10);

        assertThat(hits).singleElement().satisfies(h -> {
            assertThat(h.chunk().key()).isEqualTo("b.md#0");
            assertThat(h.coverage()).isEqualTo(1.0);
            assertThat(h.score()).isCloseTo(expectedScore("清零", CHUNKS.get(2), CHUNKS), within(0.0002));
        });
    }

    @Test
    void coverageIsShareOfQueryTermsFoundInChunk() {
        KeywordIndex index = KeywordIndex.build(CHUNKS);

        // 词项：积、积分、分、分多、多、多久、久、久清、清、清零、零 共 11 个 —— 积分有效期那块有 积、积分、分、清、清零、零 6 个
        List<KeywordIndex.KeywordHit> hits = index.search("积分多久清零", 10);

        KeywordIndex.KeywordHit first = hits.getFirst();
        assertThat(first.chunk().key()).isEqualTo("b.md#0");
        assertThat(first.coverage()).isEqualTo(Math.round(6.0 / 11 * 10_000) / 10_000.0);
    }

    @Test
    void repeatedQueryTermsCountOnce() {
        KeywordIndex index = KeywordIndex.build(CHUNKS);

        assertThat(index.search("发票发票发票", 10).getFirst().score())
                .isEqualTo(index.search("发票", 10).getFirst().score());
    }

    @Test
    void noMatchingTermsReturnsEmpty() {
        KeywordIndex index = KeywordIndex.build(CHUNKS);

        assertThat(index.search("天气预报", 10)).isEmpty();
        assertThat(index.search("的了吗", 10)).isEmpty();
        assertThat(KeywordIndex.build(List.of()).search("发票", 10)).isEmpty();
    }

    @Test
    void topKLimitsResults() {
        KeywordIndex index = KeywordIndex.build(CHUNKS);

        assertThat(index.search("支付 发票 积分", 2)).hasSize(2);
    }

    /** 真实文档：口语化的「东西坏了能退吗」，关键词检索把「质量问题退换货」那一节排进前 3。 */
    @Test
    void realDocumentsFindQualityIssueSection() {
        RagFixtures.Kb kb = RagFixtures.fakeKnowledgeBase();
        KeywordIndex index = KeywordIndex.build(
                kb.store().chunks().stream().map(RagChunk::from).toList());

        List<KeywordIndex.KeywordHit> hits = index.search("东西坏了能退吗", 3);

        assertThat(index.size()).isGreaterThan(30);
        assertThat(hits).anySatisfy(h -> assertThat(h.chunk().headingPath()).contains("质量问题退换货"));
    }

    /** 按类注释里的公式独立算一遍（测试自己的实现，不调被测代码）。 */
    private static double expectedScore(String query, RagChunk target, List<RagChunk> all) {
        List<String> queryTerms = Terms.of(query).stream().distinct().toList();
        double avg = all.stream()
                .mapToInt(c -> Terms.of(c.content()).size())
                .average()
                .orElse(0);
        List<String> terms = Terms.of(target.content());
        double score = 0;
        for (String t : queryTerms) {
            long tf = terms.stream().filter(t::equals).count();
            long df =
                    all.stream().filter(c -> Terms.of(c.content()).contains(t)).count();
            if (tf == 0 || df == 0) {
                continue;
            }
            double idf = Math.log(1 + (all.size() - df + 0.5) / (df + 0.5));
            score += idf
                    * tf
                    * (KeywordIndex.K1 + 1)
                    / (tf + KeywordIndex.K1 * (1 - KeywordIndex.B + KeywordIndex.B * terms.size() / avg));
        }
        return score;
    }
}
