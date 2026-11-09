package io.github.clarence404.assistant.rag;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 把选中的候选块整理成交给模型的「编了号的资料」：去重、合并同一节的相邻块、按上下文预算截断、编号（第 5 章引入）。
 *
 * <p>所在层：RAG 的「增强」一步。上游是 {@link RagRetriever}（按门槛选好的候选，已按相关性排好）；下游是 {@link RagService}（拼进提示词）
 * 和前端（引用来源）。
 *
 * <p><b>为什么要合并相邻块</b>：第 4 章切块时，超过 300 字的一节会被切成几块、相邻两块重叠 50 字。两块都被检索到时，原样放进去就有
 * 50 字重复、还占两个编号；合成一条，模型读到的是完整的一节，引用也只有一个编号。
 *
 * <p><b>为什么要上下文预算</b>：模型的上下文窗口（一次能读多少 token）有上限，而且资料越长，调用越慢、越贵，小模型还越容易被无关内容带偏
 * （「迷失在中间」：长上下文中间的内容最容易被忽略）。所以给资料定一个总字数，按相关性从高到低放，放不下的就不放。
 */
public final class ContextPacker {

    /** 合并相邻块时，至少重叠这么多个字才认为是切块时留的重叠，否则直接拼接。 */
    static final int MIN_OVERLAP = 5;

    private ContextPacker() {}

    /**
     * 整理成引用来源。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li><b>去重</b>：同一个键（{@link RagChunk#key()}）只保留第一次出现的
     *   <li><b>合并</b>：和已经保留的某条来源<b>同一个文件、同一个标题路径</b>、块序号紧挨着（比已有的最小序号小 1 或比最大序号大 1）的块，
     *       并进那一条，不单独占编号；合并后按块序号排列正文，相邻两块的重叠部分只保留一份（{@link #join}）。合并后的分数取各块的最大值
     *       （没有的为 null），{@code foundBy} 取并集（vector 在前）
     *   <li><b>预算</b>：按顺序累加每条来源的 {@code content} 长度，加上这条会超过 {@code budgetChars} 就停止，后面的都不要；
     *       但第一条无论多长都保留，超长时截成前 {@code budgetChars} 个字再加「…」
     *   <li><b>编号</b>：按最终顺序从 1 开始
     *   <li><b>片段</b>：正文（不含标题路径）的前 {@code snippetChars} 个字，截断了就加「…」
     *   <li>分数原样带过去，fusedScore 保留 4 位小数
     * </ul>
     *
     * @param selected 选中的候选，已按相关性从高到低排好
     * @param budgetChars 资料总字数上限
     * @param snippetChars 片段长度
     * @return 编了号的引用来源；selected 为空时是空列表
     */
    public static List<RagSource> pack(List<RagCandidate> selected, int budgetChars, int snippetChars) {
        // TODO ch05-4：去重 → 合并同一节的相邻块（重叠只留一份，可以用 join）→ 上下文预算 → 编号与片段（规则见上面的 Javadoc）
        return List.of();
    }

    /** 候选块和这一组是不是同一节里紧挨着的块。 */
    private static boolean adjacent(List<RagCandidate> group, RagChunk chunk) {
        RagChunk first = group.getFirst().chunk();
        if (!first.source().equals(chunk.source()) || !first.headingPath().equals(chunk.headingPath())) {
            return false;
        }
        int min = group.stream().mapToInt(c -> c.chunk().chunkIndex()).min().orElseThrow();
        int max = group.stream().mapToInt(c -> c.chunk().chunkIndex()).max().orElseThrow();
        return chunk.chunkIndex() == min - 1 || chunk.chunkIndex() == max + 1;
    }

    /**
     * 拼接相邻两块的正文：前一块的结尾和后一块的开头重叠（切块时故意留的）的部分只保留一份。
     *
     * @param before 前一块的正文
     * @param after 后一块的正文
     * @return 拼好的正文；找不到至少 {@link #MIN_OVERLAP} 个字的重叠时，中间加一个换行直接拼
     */
    static String join(String before, String after) {
        for (int len = Math.min(before.length(), after.length()); len >= MIN_OVERLAP; len--) {
            if (before.endsWith(after.substring(0, len))) {
                return before + after.substring(len);
            }
        }
        return before + "\n" + after;
    }

    private static Double max(List<Double> values) {
        return values.stream().filter(Objects::nonNull).max(Double::compare).orElse(null);
    }

    private static List<String> foundBy(List<RagCandidate> group) {
        Set<String> by = new LinkedHashSet<>();
        for (String name : List.of(RagRetriever.VECTOR, RagRetriever.KEYWORD)) {
            if (group.stream().anyMatch(c -> c.foundBy().contains(name))) {
                by.add(name);
            }
        }
        return List.copyOf(by);
    }

    private static String snippet(String body, int chars) {
        String flat = body.replace('\n', ' ').strip();
        return flat.length() <= chars ? flat : flat.substring(0, chars) + "…";
    }

    private static double round(double value) {
        return Math.round(value * 10_000) / 10_000.0;
    }
}
