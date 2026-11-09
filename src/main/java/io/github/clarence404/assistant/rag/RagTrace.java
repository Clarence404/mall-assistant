package io.github.clarence404.assistant.rag;

import java.util.List;

/**
 * 一次 RAG 回答的调试信息：用什么问题检索的、两路各找到几块、几块过了门槛、资料多长、有没有短路、引用校验的结果（第 5 章引入）。
 *
 * <p>所在层：接口层的数据。{@link RagService#plan} 生成检索部分，{@code ChatService} 回答后用 {@link #withCitations} 补上引用校验；
 * 阻塞接口放在响应的 {@code rag} 字段（不走 RAG 时为 null），页面调试面板「引用来源」一栏显示。
 *
 * @param query 用户原话
 * @param rewrittenQuery 改写后的问题（{@code assistant.rag.rewrite-query=true} 时）；没有改写时为 null
 * @param mode 检索方式：{@code hybrid}（向量 + 关键词，RRF 融合）或 {@code vector}（只用向量）
 * @param vectorCandidates 向量检索返回了几块
 * @param keywordCandidates 关键词检索返回了几块
 * @param passed 通过门槛的块数
 * @param contextChars 拼进提示词的资料一共多少个字
 * @param retrievalMs 检索耗时（含改写问题、把问题向量化），毫秒
 * @param shortCircuit 没调模型的原因：{@code NO_MATCH}（没有相关资料）、{@code KNOWLEDGE_UNAVAILABLE}（向量模型或向量库不可用）；调了模型时为 null
 * @param warning 给人看的说明（如知识库不可用的原因、改写失败），没有时为 null
 * @param cited 回答引用了哪些编号（只有阻塞回答做引用校验；流式与短路时为空列表）
 * @param invalidCitations 回答里出现、但没有对应资料的编号，已从回答里去掉
 * @param uncited 阻塞回答一个有效编号都没引用时为 true；没做引用校验（流式、短路）时为 null
 */
public record RagTrace(
        String query,
        String rewrittenQuery,
        String mode,
        int vectorCandidates,
        int keywordCandidates,
        int passed,
        int contextChars,
        long retrievalMs,
        String shortCircuit,
        String warning,
        List<Integer> cited,
        List<Integer> invalidCitations,
        Boolean uncited) {

    /** 检索不到相关资料：不调模型，直接回固定话术。 */
    public static final String NO_MATCH = "NO_MATCH";

    /** 向量模型或向量库不可用：同样不调模型，回固定话术。 */
    public static final String KNOWLEDGE_UNAVAILABLE = "KNOWLEDGE_UNAVAILABLE";

    /**
     * 补上引用校验的结果。
     *
     * @param check {@link CitationChecker#check} 的结果
     * @return 新的调试信息
     */
    public RagTrace withCitations(CitationChecker.Check check) {
        return new RagTrace(
                query,
                rewrittenQuery,
                mode,
                vectorCandidates,
                keywordCandidates,
                passed,
                contextChars,
                retrievalMs,
                shortCircuit,
                warning,
                List.copyOf(check.cited()),
                List.copyOf(check.invalid()),
                check.uncited());
    }
}
