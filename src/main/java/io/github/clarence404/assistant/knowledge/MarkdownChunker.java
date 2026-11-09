package io.github.clarence404.assistant.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 切块：先按标题分节，再把超长的节按长度切开，相邻两块带重叠（第 4 章引入）。
 *
 * <p>所在层：入库流水线的第二步。上游是 {@link MarkdownKnowledgeReader} 读出的 {@link KnowledgeDocument}；下游是
 * {@link ChunkDocumentFactory}（加元数据）。
 *
 * <p>两步切法的理由：
 *
 * <ol>
 *   <li><b>按标题分节</b>：规则文档天然按标题组织，一节讲一件事（「七天无理由退货 > 适用条件」），按节切最符合语义，块的边界不会
 *       把两件事搅在一起。每节记下完整的标题路径，后面写进块文字和元数据
 *   <li><b>超长的节再按长度切</b>：节太长时向量会把好几件事平均掉，检索不准，所以超过 {@code maxChars} 的节再切开。尽量在句号、
 *       分号、换行处断开，并让相邻两块重叠 {@code overlap} 个字，跨边界的句子在两块里都完整出现
 * </ol>
 *
 * <p>局限：只认 ATX 标题（行首 1～6 个 {@code #}），不处理代码块里的 {@code #}、表格等，对本项目的规则文档够用。
 */
public class MarkdownChunker {

    /** 行首 1～6 个 # 加空格就是标题：# 的个数是级别，后面是标题文字。 */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    /** 优先在这些字符之后断开：中文句号、问号、叹号、分号，英文的同类标点，以及换行。 */
    private static final String BOUNDARIES = "。！？；.!?;\n";

    /** 标题路径各级之间的分隔符。 */
    public static final String PATH_SEPARATOR = " > ";

    private final int maxChars;
    private final int overlap;

    /**
     * 创建切块器。
     *
     * @param maxChars 每块正文最多多少个字符
     * @param overlap 相邻两块重叠多少个字符，须小于 {@code maxChars} 的一半
     */
    public MarkdownChunker(int maxChars, int overlap) {
        this.maxChars = maxChars;
        this.overlap = overlap;
    }

    /**
     * 切块参数的「签名」：参数一变签名就变。它会算进文档的内容哈希（见 {@link ChunkDocumentFactory#docHash}），
     * 改了切块参数后再入库，所有文档都会按新参数重切，而不是因为「文档没变」被跳过。
     *
     * @return 如 {@code md-v1:300:50}
     */
    public String signature() {
        return "md-v1:" + maxChars + ":" + overlap;
    }

    /**
     * 切一篇文档：每节先按长度切开，再统一编号。
     *
     * @param document 文档
     * @return 按出现顺序排列的块，序号从 0 连续编号；正文为空的节（标题下直接是下一级标题）不产生块
     */
    public List<KnowledgeChunk> chunk(KnowledgeDocument document) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (Section section : sections(document)) {
            for (String piece : split(section.body())) {
                chunks.add(new KnowledgeChunk(chunks.size(), section.headingPath(), piece));
            }
        }
        return chunks;
    }

    /**
     * 按标题分节：逐行扫描，遇到标题就结束上一节、更新标题栈。标题路径的第一级是文档标题（front matter 的 title），
     * 和它同名的一级标题（# 退换货规则）不重复计入。
     *
     * @param document 文档
     * @return 各节（含正文为空的节，由调用方跳过）
     */
    public List<Section> sections(KnowledgeDocument document) {
        List<Section> sections = new ArrayList<>();
        // headings[i] 是第 i+1 级标题；遇到第 n 级标题时，n 级及更深的都要清掉
        String[] headings = new String[6];
        StringBuilder body = new StringBuilder();
        String currentPath = document.title();
        for (String line : document.body().split("\n", -1)) {
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                sections.add(new Section(currentPath, body.toString().strip()));
                body.setLength(0);
                int level = heading.group(1).length();
                headings[level - 1] = heading.group(2).strip();
                for (int i = level; i < headings.length; i++) {
                    headings[i] = null;
                }
                currentPath = path(document.title(), headings);
            } else {
                body.append(line).append('\n');
            }
        }
        sections.add(new Section(currentPath, body.toString().strip()));
        return sections.stream().filter(s -> !s.body().isEmpty()).toList();
    }

    /** 拼标题路径：文档标题 > 各级标题，跳过空级别和与文档标题同名的标题。 */
    private static String path(String title, String[] headings) {
        List<String> parts = new ArrayList<>();
        parts.add(title);
        for (String h : headings) {
            if (h != null && !h.equals(title)) {
                parts.add(h);
            }
        }
        return String.join(PATH_SEPARATOR, parts);
    }

    /**
     * 把一节正文按长度切开，相邻两块重叠。
     *
     * <p>规则（测试按这个写）：
     *
     * <ol>
     *   <li>先去掉首尾空白；空串返回空列表；长度不超过 {@code maxChars} 原样作为一块
     *   <li>否则从 {@code start = 0} 开始，每块最多取 {@code maxChars} 个字符，即候选终点 {@code end = start + maxChars}
     *   <li>不是最后一块时，在 {@code [start + maxChars / 2, end)} 范围内从后往前找断句字符（{@link #BOUNDARIES}：。！？；.!?; 和换行），
     *       找到就在它<b>之后</b>断开（标点留在前一块）；找不到就在 {@code end} 硬切
     *   <li>每块去掉首尾空白后加入结果；切到末尾就结束
     *   <li>下一块从 {@code 本块终点 - overlap} 开始（往回退 overlap 个字，这就是「重叠」），但至少前进 1 个字符，防止死循环
     * </ol>
     *
     * @param text 一节的正文
     * @return 切好的若干块，每块长度不超过 {@code maxChars}
     */
    public List<String> split(String text) {
        // TODO ch04-1 按上面的规则切块：不超过 maxChars、尽量在句末断开、相邻两块重叠 overlap 个字
        return List.of();
    }

    /**
     * 在 {@code [from, to)} 里从后往前找断句字符。
     *
     * @return 断句字符之后的位置（作为切分点）；找不到返回 -1
     */
    static int lastBoundary(String text, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            if (BOUNDARIES.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * 一节：标题路径 + 正文。
     *
     * @param headingPath 标题路径
     * @param body 正文，已去掉首尾空白
     */
    public record Section(String headingPath, String body) {}
}
