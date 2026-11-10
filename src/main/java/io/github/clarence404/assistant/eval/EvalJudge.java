package io.github.clarence404.assistant.eval;

import io.github.clarence404.assistant.rag.RagSource;
import java.util.List;

/**
 * 可选的 LLM 裁判（LLM-as-judge）：让另一个（通常更大的）模型给 RAG 回答打分（第 9 章引入）。
 *
 * <p>所在层：评测。{@link EvalRunner} 在规则类用例调了模型、拿到回答之后问它一次，分数写进报告，<b>不参与通过与否、不进门禁</b>。
 *
 * <p><b>为什么需要它</b>：{@link EvalChecks} 只能判「确定的东西」——检索到了哪一节、引用编号对不对、有没有出现「全额退款」。回答是不是忠于资料、
 * 有没有漏掉要点、有没有编出资料里没有的规则，这种要「读懂」才能判的，只能交给模型。
 *
 * <p><b>局限（讲义里有实测）</b>：裁判自己也会错，而且有偏好（偏爱长回答、偏爱和自己说话风格像的、对第一个选项有位置偏好）；同一份回答两次打分可能不同；
 * 要钱、要时间。所以它的分只用来「看趋势、找可疑的用例人工复核」，不能当成门禁的硬指标。业界常见做法是先用一小批人工标注校准裁判（看它和人的一致率），再放心用。
 */
@FunctionalInterface
public interface EvalJudge {

    /**
     * 给一个回答打分。
     *
     * @param question 用户的问题
     * @param sources 交给模型的资料
     * @param answer 模型的回答
     * @return 分数与理由；裁判失败时为 null
     */
    Verdict judge(String question, List<RagSource> sources, String answer);

    /**
     * 裁判的结论。
     *
     * @param score 1～5：5 每句话都有依据且回答了问题，1 编造或答非所问
     * @param reason 一句话理由
     */
    record Verdict(int score, String reason) {}
}
