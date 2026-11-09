package io.github.clarence404.assistant.rag;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * 检索评测：一小份「口语化问题 → 应该命中哪一节」的评测集，算 hit@k（第 5 章引入）。
 *
 * <p><b>hit@k</b>：最终交给模型的前 k 条资料里，有没有一条是「正确答案所在的那一节」；有就算命中。整份评测集的命中比例就是 hit@k。
 * 对 RAG 来说这是最朴素也最要紧的指标：正确的资料都没进提示词，模型再聪明也答不对（或者只能说不知道）。
 *
 * <p>评测集在 {@code src/test/resources/rag/eval-cases.json}：20 个问题，故意用口语、和原文用词不同的说法（「东西坏了能退吗」
 * 对应原文「质量问题退换货」）；期望命中写成「来源文件 + 标题路径里包含的一段」，同一节被切成几块时哪块都算对。
 *
 * <p>单元测试（{@code RetrievalEvalTest}，假向量模型）和集成测试（{@code OllamaEmbeddingIT}，真实的 nomic-embed-text / bge-m3）
 * 都用它，分别对比「只用向量检索」和「混合检索」。
 */
public final class RetrievalEval {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * 知识库里<b>没有</b>答案的问题（第 5 章定 bge-m3 门槛时用的 6 道反例，第 6 章定 nomic-embed-text 门槛时再用一次）：门槛合适的话，
     * 它们一块资料都不该留下（短路，不调模型）。
     */
    public static final List<String> OUT_OF_KNOWLEDGE =
            List.of("能用花呗分期付款吗", "你们有线下门店吗", "买空调送安装吗", "能以旧换新吗", "买完降价了能补差价吗", "礼品卡能转赠给别人吗");

    private RetrievalEval() {}

    /**
     * 一条评测用例。
     *
     * @param question 用户会怎么问
     * @param source 答案所在的文件
     * @param heading 答案所在那一节的标题（标题路径里包含它就算命中）
     */
    public record Case(String question, String source, String heading) {}

    /**
     * 一条用例的结果。
     *
     * @param question 问题
     * @param rank 正确的那一节排第几（1 开始）；前 k 条里没有时为 0
     * @param top1 实际排第一的标题路径（没检索到为空串），方便看错在哪
     */
    public record CaseResult(String question, int rank, String top1) {}

    /**
     * 整份评测集的结果。
     *
     * @param label 配置名，如「混合检索」
     * @param k 看前几条
     * @param hits 命中几条
     * @param results 每条用例的结果
     */
    public record Result(String label, int k, int hits, List<CaseResult> results) {

        /** 命中比例。 */
        public double hitRate() {
            return results.isEmpty() ? 0 : (double) hits / results.size();
        }

        /** 没命中的问题。 */
        public List<String> misses() {
            return results.stream()
                    .filter(r -> r.rank() == 0)
                    .map(CaseResult::question)
                    .toList();
        }

        /** 一行摘要，打进测试日志。 */
        public String summary() {
            return label + " hit@" + k + " = " + hits + "/" + results.size() + "，没命中：" + misses();
        }

        /** 每条用例排第几（0 = 没进前 k）。 */
        public String ranks() {
            return results.stream().map(r -> r.question() + "=" + r.rank()).collect(Collectors.joining("，"));
        }
    }

    /**
     * 读评测集。
     *
     * @return 用例
     */
    public static List<Case> cases() {
        try (InputStream in = RetrievalEval.class.getClassLoader().getResourceAsStream("rag/eval-cases.json")) {
            if (in == null) {
                throw new IllegalStateException("classpath 上没有 rag/eval-cases.json");
            }
            return List.of(JSON.readValue(in, Case[].class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 跑一遍评测：每个问题检索一次，看前 k 条资料里有没有正确的那一节。
     *
     * @param label 配置名
     * @param retriever 检索器（决定是否混合检索、门槛）
     * @param k 看前几条
     * @return 结果
     */
    public static Result evaluate(String label, RagRetriever retriever, int k) {
        List<CaseResult> results = new ArrayList<>();
        int hits = 0;
        for (Case c : cases()) {
            List<RagSource> sources =
                    retriever.retrieve(c.question(), c.question(), k).sources();
            int rank = 0;
            for (RagSource s : sources) {
                if (s.source().equals(c.source()) && s.headingPath().contains(c.heading())) {
                    rank = s.index();
                    break;
                }
            }
            if (rank > 0) {
                hits++;
            }
            results.add(new CaseResult(
                    c.question(),
                    rank,
                    sources.isEmpty() ? "" : sources.getFirst().headingPath()));
        }
        return new Result(label, k, hits, results);
    }
}
