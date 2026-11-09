package io.github.clarence404.assistant.rag;

import java.util.List;

/**
 * 融合后的一个候选块，带着它在两路检索里各自的分数（第 5 章引入）。
 *
 * <p>所在层：RAG 的检索中间结果。{@link RagRetriever} 生成、按门槛筛选；{@link ContextPacker} 把选中的候选合并、编号成 {@link RagSource}。
 *
 * @param chunk 块
 * @param vectorScore 向量检索的余弦相似度；向量检索没找到它（不在前 candidateK 块里）时为 null
 * @param keywordScore 关键词检索的 BM25 分数；关键词检索没找到它时为 null
 * @param coverage 问题的词项有多大比例出现在这块里；关键词检索没找到它时为 null
 * @param fusedScore 排序用的分数：混合检索时是 RRF 总分，只用向量检索时等于 vectorScore
 * @param foundBy 被哪些检索器找到：{@code vector}、{@code keyword}
 */
public record RagCandidate(
        RagChunk chunk,
        Double vectorScore,
        Double keywordScore,
        Double coverage,
        double fusedScore,
        List<String> foundBy) {}
