package io.github.clarence404.assistant.eval;

import io.github.clarence404.assistant.chat.ChatReply;
import io.github.clarence404.assistant.rag.RagSource;
import io.github.clarence404.assistant.rag.RagTrace;
import io.github.clarence404.assistant.tool.ToolInvocation;
import java.util.ArrayList;
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
        EvalCase.Expect e = c.expect();
        List<String> failures = new ArrayList<>();
        String text = reply.reply() == null ? "" : reply.reply();
        List<ToolInvocation> tools = reply.tools() == null ? List.of() : reply.tools();
        List<RagSource> sources = reply.sources() == null ? List.of() : reply.sources();
        RagTrace rag = reply.rag();

        if (e.intent() != null) {
            String actual =
                    reply.intent() == null ? null : reply.intent().intent().name();
            if (!e.intent().equals(actual)) {
                failures.add("意图应为 " + e.intent() + "，实际 " + actual);
            }
        }
        if (e.tool() != null && tools.stream().noneMatch(t -> e.tool().equals(t.name()))) {
            failures.add("应调用工具 " + e.tool() + "，实际 " + toolNames(tools));
        }
        if (Boolean.TRUE.equals(e.noTool()) && !tools.isEmpty()) {
            failures.add("不应调用工具，实际 " + toolNames(tools));
        }
        if (e.toolArgContains() != null
                && tools.stream()
                        .noneMatch(t -> t.arguments() != null && t.arguments().contains(e.toolArgContains()))) {
            failures.add("没有哪次工具调用的参数含「" + e.toolArgContains() + "」，实际 " + toolNames(tools));
        }
        if (e.source() != null || e.heading() != null) {
            boolean hit = sources.stream()
                    .anyMatch(s -> (e.source() == null || e.source().equals(s.source()))
                            && (e.heading() == null
                                    || (s.headingPath() != null
                                            && s.headingPath().contains(e.heading()))));
            if (!hit) {
                failures.add("资料里没有 " + nullToEmpty(e.source()) + "「" + nullToEmpty(e.heading()) + "」，实际 "
                        + sources.stream()
                                .map(s -> s.source() + "「" + s.headingPath() + "」")
                                .toList());
            }
        }
        if (Boolean.TRUE.equals(e.cited())
                && (rag == null
                        || rag.cited().isEmpty()
                        || !rag.invalidCitations().isEmpty())) {
            failures.add("回答应引用有效的资料编号，实际 "
                    + (rag == null ? "没走 RAG" : "引用 " + rag.cited() + "，对不上的 " + rag.invalidCitations()));
        }
        if (e.shortCircuit() != null && (rag == null || !e.shortCircuit().equals(rag.shortCircuit()))) {
            failures.add("RAG 应短路为 " + e.shortCircuit() + "，实际 " + (rag == null ? "没走 RAG" : rag.shortCircuit()));
        }
        if (!e.condensedContains().isEmpty()) {
            String condensed = reply.memory() == null ? null : reply.memory().condensedQuestion();
            for (String word : e.condensedContains()) {
                if (condensed == null || !condensed.contains(word)) {
                    failures.add("改写后的问题应含「" + word + "」，实际 " + condensed);
                }
            }
        }
        if (e.proposal() != null) {
            String actual =
                    reply.confirmation() == null ? null : reply.confirmation().action();
            if (!e.proposal().equals(actual)) {
                failures.add("应提出 " + e.proposal() + "，实际 " + actual);
            }
        }
        if (e.blocked() != null
                && (rag == null
                        || rag.blocked().stream().noneMatch(b -> e.blocked().equals(b.source())))) {
            failures.add("应挡下 " + e.blocked() + "，实际挡下 "
                    + (rag == null
                            ? "[]"
                            : rag.blocked().stream().map(b -> b.source()).toList()));
        }
        for (String must : e.mustContain()) {
            if (!text.contains(must)) {
                failures.add("回答应含「" + must + "」");
            }
        }
        for (String mustNot : e.mustNotContain()) {
            if (text.contains(mustNot)) {
                failures.add("回答不应含「" + mustNot + "」");
            }
        }
        return failures;
    }

    private static List<String> toolNames(List<ToolInvocation> tools) {
        return tools.stream().map(ToolInvocation::name).toList();
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
