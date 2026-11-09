package io.github.clarence404.assistant.rag;

import java.util.List;

/**
 * RRF（Reciprocal Rank Fusion，倒数排名融合）：把几个检索器各自的排名合成一个排名（第 5 章引入）。
 *
 * <p>所在层：RAG 的检索。{@link RagRetriever} 把向量检索、关键词检索的两份排名交给它。
 *
 * <p><b>为什么不直接把分数加起来</b>：两种分数根本不是一个尺度——余弦相似度在 0～1 之间、真实模型多半挤在 0.4～0.8；BM25 没有上限，
 * 问题越长分越高。硬加要先归一化，怎么归一都有点拍脑袋。RRF 干脆不看分数、只看<b>名次</b>：在每个检索器里排第 r 名的块得
 * {@code 1 / (k + r)} 分，几个检索器的得分相加。两边都排得靠前的块总分最高；只被一边找到的块也有分，不会被埋没。
 *
 * <p>k 的作用：k 越大，第 1 名和第 10 名的分差越小，越「民主」；k 越小越偏向各自的第一名。原论文（Cormack 等，2009）用 60，
 * Elasticsearch、OpenSearch 的混合检索默认也是 60。
 */
public final class RrfFusion {

    private RrfFusion() {}

    /**
     * 融合。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>每份排名里第 r 名（r 从 <b>1</b> 开始）的键得 {@code 1.0 / (k + r)} 分；同一个键出现在几份排名里，分数相加
     *   <li>同一份排名里重复出现的键只按第一次（名次最靠前的那次）算
     *   <li>按总分从高到低返回；总分相同时，按键第一次出现的先后（先看第一份排名，再看第二份……）
     * </ul>
     *
     * @param rankings 若干份排名，每份是按相关性从高到低的键列表（如 {@code returns.md#3}）
     * @param k 平滑常数，大于 0
     * @return 融合后的排名与分数
     */
    public static List<Fused> fuse(List<List<String>> rankings, int k) {
        // TODO ch05-2：每份排名第 r 名得 1 / (k + r)，同一个键的分数相加，按总分从高到低（同分按第一次出现的先后）
        return List.of();
    }

    /**
     * 融合后的一项。
     *
     * @param key 块的键
     * @param score RRF 总分；只用来排序，绝对值没有含义（两份排名都排第 1、k=60 时是 2/61 ≈ 0.0328）
     */
    public record Fused(String key, double score) {}
}
