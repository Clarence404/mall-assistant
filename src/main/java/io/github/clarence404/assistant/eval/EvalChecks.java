package io.github.clarence404.assistant.eval;

import io.github.clarence404.assistant.chat.ChatReply;
import io.github.clarence404.assistant.tool.ToolInvocation;
import java.util.List;

/**
 * 判一条用例过没过：把 {@link EvalCase.Expect} 里写了的每一项和 {@link ChatReply} 对一遍，没满足的记一句原因（第 9 章引入）。
 *
 * <p>所在层：评测。{@link EvalRunner} 每条用例调一次 {@link #judge}。
 *
 * <p>判的全是 {@link ChatReply} 里本来就有的调试字段——意图（第 2 章）、工具调用记录（第 3 章）、引用来源与引用校验（第 5 章）、记忆情况（第 6 章）、
 * 待确认操作（第 7 章）、被挡下的资料（第 9 章）。前八章把这些都放进响应里给调试面板看，到了这一章它们正好成了评测的依据：<b>可观测的系统才可评测</b>。
 */
public final class EvalChecks {

    private EvalChecks() {}

    /**
     * 判一条用例（ch09-9）。
     *
     * <p>要求（测试按这个写）：只判 {@code expect} 里写了的项（null / 空列表就跳过），每一项没满足就往结果里加一句原因（原因里带上期望和实际，方便看报告时
     * 不用再去翻日志）。各项的含义：
     *
     * <ul>
     *   <li>{@code intent}：{@code reply.intent().intent().name()} 相等（intent 为 null 算不满足）
     *   <li>{@code tool}：{@code reply.tools()} 里有这个名字的调用；{@code noTool=true}：{@code tools} 为空
     *   <li>{@code toolArgContains}：某次工具调用的 {@code arguments} 含这段文字
     *   <li>{@code source} / {@code heading}：{@code reply.sources()} 里有一条来源文件等于 source、标题路径含 heading（只写了其中一个就只比那一个）
     *   <li>{@code cited=true}：{@code reply.rag()} 不为 null、{@code cited} 不为空、{@code invalidCitations} 为空
     *   <li>{@code shortCircuit}：{@code reply.rag()} 不为 null 且 {@code shortCircuit} 相等
     *   <li>{@code condensedContains}：{@code reply.memory().condensedQuestion()} 不为 null 且含每一个词
     *   <li>{@code proposal}：{@code reply.confirmation()} 不为 null 且 {@code action} 相等
     *   <li>{@code blocked}：{@code reply.rag().blocked()} 里有这个来源文件
     *   <li>{@code mustContain} / {@code mustNotContain}：回答文字（null 按空串）含 / 不含每一段
     * </ul>
     *
     * @param c 用例
     * @param reply 被判定的那一轮的回答
     * @return 没满足的期望；全部满足时为空列表
     */
    public static List<String> judge(EvalCase c, ChatReply reply) {
        // TODO ch09-9：只判 c.expect() 里写了的项（见上面的要求），每一项没满足就加一句带「期望 / 实际」的原因；
        // 全满足返回空列表
        return List.of("TODO ch09-9：还没有判定");
    }

    private static List<String> toolNames(List<ToolInvocation> tools) {
        return tools.stream().map(ToolInvocation::name).toList();
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
