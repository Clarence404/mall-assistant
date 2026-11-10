package io.github.clarence404.assistant.eval;

import java.util.List;

/**
 * 回归评测集里的一条用例：问什么（可以带前几轮）、要不要登录、期望看到什么（第 9 章引入）。
 *
 * <p>所在层：评测（{@code eval} 包）。{@link EvalSet} 从 JSONL 文件读出来，{@link EvalRunner} 跑，{@link EvalChecks} 判。一行一条，例如：
 *
 * <pre>
 * {"id":"rag-02","category":"rag","message":"七天无理由退货要什么条件","expect":{"source":"returns.md","heading":"适用条件","cited":true}}
 * {"id":"multi-01","category":"rewrite","history":["七天无理由退货要什么条件"],"message":"那运费谁出","expect":{"condensedContains":["运费"]}}
 * </pre>
 *
 * <p>为什么判「看到了什么」而不是「回答是不是这句话」：模型每次措辞都不一样，逐字比对没有意义；但意图、调了哪个工具、检索到了哪一节、引用编号对不对、
 * 提出了什么写操作、有没有说出不该说的话，这些是确定的、代码能判的——它们恰好也是前八章各自的「验收标准」。回答写得好不好这种主观的，交给可选的
 * LLM 裁判（{@link EvalJudge}），只做参考、不进门禁。
 *
 * @param id 用例编号，整份评测集里唯一（基线对比按它对应）
 * @param category 类别：前八章的哪一块能力
 * @param message 这一轮问什么
 * @param history 前几轮（按顺序先问，同一个会话），只有多轮用例才有；没有时为空列表
 * @param login 是否以演示账号登录（查订单、Agent 要登录）
 * @param expect 期望
 * @param note 说明（为什么有这条、看什么），不参与判定
 */
public record EvalCase(
        String id, Category category, String message, List<String> history, boolean login, Expect expect, String note) {

    /** 紧凑构造器：没写 history 时是空列表。 */
    public EvalCase {
        history = history == null ? List.of() : List.copyOf(history);
    }

    /** 类别：对应前八章的能力，报告里按它分别算通过率。 */
    public enum Category {
        /** 第 2 章：意图识别。 */
        INTENT,
        /** 第 3 章：工具选择（查订单、列订单、查商品，或者不该调工具）。 */
        TOOL,
        /** 第 5 章：RAG（检索命中 hit@k、引用正确、检索不到不编）。 */
        RAG,
        /** 第 6 章：多轮追问改写与订单号沿用。 */
        REWRITE,
        /** 第 7 章：Agent 提出正确的写操作。 */
        AGENT,
        /** 第 5 / 9 章：提示注入（恶意资料被挡下、回答里没有被带偏的话）。 */
        INJECTION,
        /** 第 2 章：超出范围的拒答。 */
        REFUSAL
    }

    /**
     * 期望：每一项都是可选的，写了才判，全部满足才算通过。
     *
     * @param intent 意图名，如 {@code ORDER_STATUS}
     * @param tool 调用过这个工具（第 3 章的 tools 字段）
     * @param noTool 为 true 时一个工具都不该调
     * @param toolArgContains 某次工具调用的参数里含这段文字（如订单号：第 6 章的沿用）
     * @param source 检索结果（交给模型的资料）里有这个来源文件……
     * @param heading ……且标题路径含这一段（和 source 一起判，就是第 5 章的 hit@k，k = top-k）
     * @param cited 为 true 时回答至少引用了一个有效编号、没有对不上的编号（第 5 章的引用校验）
     * @param shortCircuit RAG 没调模型的原因，如 {@code NO_MATCH}（检索不到就不编）
     * @param condensedContains 追问改写后的问题含这些词（第 6 章）
     * @param proposal Agent 提出的写操作，如 {@code cancelOrder}（第 7 章）
     * @param blocked 被提示注入过滤挡下的资料里有这个来源文件（第 9 章）
     * @param mustContain 回答必须含这些文字
     * @param mustNotContain 回答不能含这些文字（如被注入带偏的「全额退款」）
     */
    public record Expect(
            String intent,
            String tool,
            Boolean noTool,
            String toolArgContains,
            String source,
            String heading,
            Boolean cited,
            String shortCircuit,
            List<String> condensedContains,
            String proposal,
            String blocked,
            List<String> mustContain,
            List<String> mustNotContain) {

        /** 紧凑构造器：没写的列表是空列表。 */
        public Expect {
            condensedContains = condensedContains == null ? List.of() : List.copyOf(condensedContains);
            mustContain = mustContain == null ? List.of() : List.copyOf(mustContain);
            mustNotContain = mustNotContain == null ? List.of() : List.copyOf(mustNotContain);
        }

        /**
         * 一项都没写（评测集校验时拒绝这种用例：什么都不判的用例永远通过，没有意义）。
         *
         * @return 什么期望都没有时为 true
         */
        public boolean isEmpty() {
            return intent == null
                    && tool == null
                    && noTool == null
                    && toolArgContains == null
                    && source == null
                    && heading == null
                    && cited == null
                    && shortCircuit == null
                    && condensedContains.isEmpty()
                    && proposal == null
                    && blocked == null
                    && mustContain.isEmpty()
                    && mustNotContain.isEmpty();
        }
    }
}
