package io.github.clarence404.assistant.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回答的引用校验（事后检查）：回答里的 [n] 有没有对应的资料（第 5 章引入）。
 *
 * <p>所在层：RAG 的「生成」之后。上游是 {@code ChatService} 的阻塞回答；结果写进 {@link RagTrace}，调试面板显示。
 *
 * <p>为什么要查：提示词要求「只能用资料里有的编号」，但模型（尤其小模型）会照着格式「编」引用——资料只有 3 条，它写出 [5]；
 * 或者干脆一个编号都不标。编号对不上，页面上点开是空的，用户会以为有依据。能用代码确定判断的错误，就不要只靠提示词（同第 3 章的
 * {@code ToolResultGuard}）。这里只做两件有把握的事：
 *
 * <ul>
 *   <li>不存在的编号从回答里<b>去掉</b>，记进 {@code invalid}
 *   <li>一个有效编号都没有时，标记 {@code uncited}（不改回答：可能是模型在说「资料里没有」，也可能是它没按要求标，留给人判断）
 * </ul>
 *
 * <p>流式回答已经推给用户了，收不回来，这里不处理；页面拿到全部 source 事件后，把对不上的编号标成灰色。
 */
public final class CitationChecker {

    /**
     * 引用的写法：{@code [1]}、{@code [1, 2]}、{@code [1][2]}，以及小模型常写的全角 {@code 【1】}、{@code [1、2]}。
     * 方括号里只能是数字（每个最多 6 位）和分隔符，免得把「[注意]」之类的文字当成引用。
     */
    static final Pattern CITATION = Pattern.compile("[\\[【]\\s*(\\d{1,6}(?:\\s*[,，、]\\s*\\d{1,6})*)\\s*[]】]");

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private CitationChecker() {}

    /**
     * 校验并清理回答里的引用。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>用 {@link #CITATION} 找出每一处引用，其中的每个数字 n：1 ≤ n ≤ sourceCount 算有效，否则无效
     *   <li>一处引用里有效的编号保留下来，统一写成 {@code [n]}（多个就是 {@code [1][2]}）；全部无效时这一处整个删掉
     *   <li>{@code cited}：出现过的有效编号，去重，按第一次出现的先后；{@code invalid}：出现过的无效编号，同样去重
     *   <li>{@code uncited}：cited 为空
     *   <li>删掉引用后不另外处理空白；不含引用的回答原样返回
     * </ul>
     *
     * @param reply 模型的回答
     * @param sourceCount 交给模型的资料条数（编号 1～sourceCount 有效）
     * @return 清理后的回答与校验结果
     */
    public static Check check(String reply, int sourceCount) {
        Set<Integer> cited = new LinkedHashSet<>();
        Set<Integer> invalid = new LinkedHashSet<>();
        Matcher matcher = CITATION.matcher(reply);
        StringBuilder cleaned = new StringBuilder();
        while (matcher.find()) {
            StringBuilder kept = new StringBuilder();
            Matcher numbers = NUMBER.matcher(matcher.group(1));
            while (numbers.find()) {
                int n = Integer.parseInt(numbers.group());
                if (n >= 1 && n <= sourceCount) {
                    cited.add(n);
                    kept.append('[').append(n).append(']');
                } else {
                    invalid.add(n);
                }
            }
            matcher.appendReplacement(cleaned, Matcher.quoteReplacement(kept.toString()));
        }
        matcher.appendTail(cleaned);
        return new Check(cleaned.toString(), new ArrayList<>(cited), new ArrayList<>(invalid), cited.isEmpty());
    }

    /**
     * 校验结果。
     *
     * @param reply 清理后的回答（去掉了不存在的编号，引用统一成 [n]）
     * @param cited 回答引用了哪些有效编号
     * @param invalid 回答里出现、但没有对应资料的编号（已从回答里去掉）
     * @param uncited 一个有效编号都没有
     */
    public record Check(String reply, List<Integer> cited, List<Integer> invalid, boolean uncited) {}
}
