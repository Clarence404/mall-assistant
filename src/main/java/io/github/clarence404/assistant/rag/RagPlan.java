package io.github.clarence404.assistant.rag;

import java.util.List;

/**
 * 检索做完、还没调模型时的「回答计划」：有资料就带着资料去问模型，没有就直接回固定话术（第 5 章引入）。
 *
 * <p>所在层：业务层的中间结果。{@link RagService#plan} 生成，{@code ChatService} 据它决定调不调模型。
 *
 * @param sources 编了号的引用来源；为空表示没有相关资料（或知识库不可用），这时<b>不调模型</b>
 * @param trace 调试信息
 * @param fallback 不调模型时直接回给用户的话术：没找到相关规定（{@code rag-not-found.st}），或知识库暂时不可用（{@code rag-unavailable.st}）
 */
public record RagPlan(List<RagSource> sources, RagTrace trace, String fallback) {

    /**
     * 是否短路（不调模型）。
     *
     * @return sources 为空时为 true
     */
    public boolean shortCircuited() {
        return sources.isEmpty();
    }
}
