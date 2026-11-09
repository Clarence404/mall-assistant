package io.github.clarence404.assistant.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内存里的关键词索引，用 BM25 给块打分（第 5 章引入）。
 *
 * <p>所在层：RAG 的检索器之一。{@link RagRetriever} 用库里的全部块建一个（块变了才重建），每次提问调 {@link #search}；它的排名再和向量检索的
 * 排名用 RRF 融合（{@link RrfFusion}）。
 *
 * <p><b>为什么向量检索之外还要关键词检索</b>：向量检索比的是「整体意思像不像」，擅长同义改写（「东西坏了」≈「质量问题」）；但它把一段话
 * 压成一个向量，具体的字眼会被冲淡——问「发票什么时候能开」，向量可能更像「配送 > 签收」那块（都在讲「什么时候」）。关键词检索正好相反：
 * 只认字面，「发票」两个字在哪块里出现就给哪块加分，数字、专有名词（「七天无理由」「增值税专用发票」）尤其准。两者互补，合起来叫混合检索。
 *
 * <p><b>BM25 是什么</b>：搜索引擎用了几十年的经典打分公式。对问题里的每个词项 t，块 d 得分
 *
 * <pre>
 *   idf(t) × tf × (k1 + 1) / (tf + k1 × (1 − b + b × 块长 / 平均块长))
 *   idf(t) = ln(1 + (N − df + 0.5) / (df + 0.5))
 * </pre>
 *
 * 再把所有词项的分加起来。直观理解：
 *
 * <ul>
 *   <li><b>idf</b>（逆文档频率）：词项出现的块越少越值钱。N 是总块数，df 是含有这个词项的块数；「发票」只在几块里出现，idf 高；「商品」到处都有，idf 低
 *   <li><b>tf</b>（词频）：词项在这块里出现几次。出现多当然更相关，但有上限——{@code k1} 控制饱和速度，出现 10 次不会是 1 次的 10 倍分
 *   <li><b>长度归一</b>：长块天然包含更多词，{@code b} 控制按块长打多少折扣，免得长块总排前面
 * </ul>
 *
 * <p>另外算一个 <b>覆盖率</b>（coverage）：问题里的词项有多大比例在这块里出现过，0～1。BM25 的分数没有固定的范围（问题越长分越高），
 * 不好拿来定「够不够相关」的门槛；覆盖率有，{@link RagRetriever} 用它判断「只被关键词检索找到的块」算不算相关。
 *
 * <p>线程：建好以后只读，可以多个请求同时检索。
 */
public final class KeywordIndex {

    /** 词频饱和参数，常用 1.2～2.0。 */
    static final double K1 = 1.2;

    /** 长度归一的力度，0 = 不管块长，1 = 完全按块长归一；常用 0.75。 */
    static final double B = 0.75;

    private final List<RagChunk> chunks;
    private final List<Map<String, Integer>> termFrequencies;
    private final int[] lengths;
    private final Map<String, Integer> documentFrequencies;
    private final double averageLength;

    private KeywordIndex(
            List<RagChunk> chunks,
            List<Map<String, Integer>> termFrequencies,
            int[] lengths,
            Map<String, Integer> documentFrequencies,
            double averageLength) {
        this.chunks = chunks;
        this.termFrequencies = termFrequencies;
        this.lengths = lengths;
        this.documentFrequencies = documentFrequencies;
        this.averageLength = averageLength;
    }

    /**
     * 建索引：每块切词项、数词频，统计每个词项出现在几块里（df）和平均块长。
     *
     * @param chunks 全部块
     * @return 索引
     */
    public static KeywordIndex build(List<RagChunk> chunks) {
        List<Map<String, Integer>> tfs = new ArrayList<>(chunks.size());
        int[] lengths = new int[chunks.size()];
        Map<String, Integer> df = new HashMap<>();
        long total = 0;
        for (int i = 0; i < chunks.size(); i++) {
            List<String> terms = Terms.of(chunks.get(i).content());
            Map<String, Integer> tf = new HashMap<>();
            for (String term : terms) {
                tf.merge(term, 1, Integer::sum);
            }
            tf.keySet().forEach(term -> df.merge(term, 1, Integer::sum));
            tfs.add(tf);
            lengths[i] = terms.size();
            total += terms.size();
        }
        double average = chunks.isEmpty() ? 0 : (double) total / chunks.size();
        return new KeywordIndex(List.copyOf(chunks), tfs, lengths, df, average);
    }

    /**
     * 索引里有几块。
     *
     * @return 块数
     */
    public int size() {
        return chunks.size();
    }

    /**
     * BM25 检索。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>问题用 {@link Terms#of} 切词项后<b>去重</b>（同一个词项问两遍不多算分）；一个词项都没有时返回空列表
     *   <li>每块的分数 = 问题里每个词项按类注释里的 BM25 公式算分再相加（{@link #K1}、{@link #B}；词项在这块里没出现就是 0 分，
     *       df 为 0 的词项跳过）
     *   <li>覆盖率 = 这块里出现过的问题词项数 / 问题的词项数（都按去重后的算）
     *   <li>只返回分数大于 0 的块，按分数从高到低排，分数相同时按块在索引里的顺序；最多 {@code topK} 块
     *   <li>分数、覆盖率都保留 4 位小数（{@code Math.round(x * 10000) / 10000.0}）
     * </ul>
     *
     * @param query 问题
     * @param topK 最多返回几块
     * @return 命中的块，按 BM25 分数从高到低
     */
    public List<KeywordHit> search(String query, int topK) {
        Set<String> queryTerms = new LinkedHashSet<>(Terms.of(query));
        if (queryTerms.isEmpty() || chunks.isEmpty()) {
            return List.of();
        }
        int n = chunks.size();
        List<KeywordHit> hits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Integer> tf = termFrequencies.get(i);
            double score = 0;
            int matched = 0;
            for (String term : queryTerms) {
                int df = documentFrequencies.getOrDefault(term, 0);
                int freq = tf.getOrDefault(term, 0);
                if (df == 0 || freq == 0) {
                    continue;
                }
                matched++;
                // idf：词项越少见越值钱；加 1 再取对数，保证常见词也不会得负分
                double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
                // 长度归一：块比平均长，分母变大，同样的词频得分更少
                double norm = K1 * (1 - B + B * lengths[i] / averageLength);
                score += idf * freq * (K1 + 1) / (freq + norm);
            }
            if (score > 0) {
                hits.add(new KeywordHit(chunks.get(i), round(score), round((double) matched / queryTerms.size())));
            }
        }
        // List.sort 是稳定排序：分数相同时保持块在索引里的顺序
        hits.sort(Comparator.comparingDouble(KeywordHit::score).reversed());
        return hits.size() > topK ? List.copyOf(hits.subList(0, topK)) : List.copyOf(hits);
    }

    private static double round(double value) {
        return Math.round(value * 10_000) / 10_000.0;
    }

    /**
     * 关键词检索命中的一块。
     *
     * @param chunk 块
     * @param score BM25 分数，没有固定上限，只能在同一个问题的结果之间比较
     * @param coverage 问题的词项有多大比例出现在这块里，0～1
     */
    public record KeywordHit(RagChunk chunk, double score, double coverage) {}
}
