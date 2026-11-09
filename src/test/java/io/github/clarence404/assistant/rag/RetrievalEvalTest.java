package io.github.clarence404.assistant.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 第 5 章：检索评测（hit@k），对比「只用向量检索」和「混合检索」。依赖 ch05-1～ch05-4 全部做完。
 *
 * <p>这里的向量模型是假的（字符 n-gram，只认字面），数字本身没有参考意义，真实模型的对比看 {@code OllamaEmbeddingIT} 的日志和
 * docs/ch05.md 的实测表。这个测试守住的是两件事：评测流程能跑通；混合检索不比只用向量差，而且救回了第 4 章留下的「东西坏了能退吗」。
 *
 * <p>门槛设成 -1（不筛）：评测看的是「排序」好不好，门槛另外有 {@link RagRetrieverTest} 管。
 */
class RetrievalEvalTest {

    private static final Logger log = LoggerFactory.getLogger(RetrievalEvalTest.class);

    @Test
    void hybridRetrievalIsAtLeastAsGoodAsVectorOnly() {
        RagFixtures.Kb kb = RagFixtures.fakeKnowledgeBase();
        RagRetriever vector = RagFixtures.retriever(kb, RagFixtures.properties(-1, false));
        RagRetriever hybrid = RagFixtures.retriever(kb, RagFixtures.properties(-1, true));

        for (int k : new int[] {1, 3, 4}) {
            RetrievalEval.Result v = RetrievalEval.evaluate("只用向量（假模型）", vector, k);
            RetrievalEval.Result h = RetrievalEval.evaluate("混合检索（假模型）", hybrid, k);
            log.info(v.summary());
            log.info(h.summary());
            assertThat(h.hits()).as("hit@" + k + "：混合检索不比只用向量差").isGreaterThanOrEqualTo(v.hits());
        }
        RetrievalEval.Result hybridAt4 = RetrievalEval.evaluate("混合检索（假模型）", hybrid, 4);
        assertThat(hybridAt4.hitRate()).as("混合检索 hit@4").isGreaterThanOrEqualTo(0.6);
        assertThat(hybridAt4.misses()).doesNotContain("东西坏了能退吗");
    }

    @Test
    void evalSetHasColloquialQuestionsForEveryDocument() {
        assertThat(RetrievalEval.cases()).hasSizeBetween(15, 30);
        assertThat(RetrievalEval.cases())
                .extracting(RetrievalEval.Case::source)
                .contains(
                        "returns.md",
                        "after-sales.md",
                        "payment-invoice.md",
                        "points-membership.md",
                        "shipping.md",
                        "account-security.md");
    }
}
