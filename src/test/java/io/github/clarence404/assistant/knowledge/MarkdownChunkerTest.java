package io.github.clarence404.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 第 4 章 ch04-1：切块。按标题分节（已给好）+ 超长的节按长度切开、相邻两块重叠（{@code MarkdownChunker#split}，要写的）。
 *
 * <p>测试思路：切块是纯函数，不需要 Spring 和模型。用人工构造的文字检查「规则」而不是具体切点：每块不超长、能在句末断就在句末断、
 * 相邻两块正好重叠 overlap 个字、去掉重叠后拼回去和原文一模一样（不丢字、不多字）。为了让「重叠」可以精确比较，构造的文字里没有空白
 * （块首尾的空白会被去掉）。
 */
class MarkdownChunkerTest {

    private final MarkdownChunker chunker = new MarkdownChunker(100, 20);

    @Test
    void shortTextIsOneChunk() {
        assertThat(chunker.split("  七天内可以无理由退货。  ")).containsExactly("七天内可以无理由退货。");
        assertThat(chunker.split("   ")).isEmpty();
    }

    /** 没有标点时在 maxChars 处硬切，下一块从往回 overlap 个字处开始。 */
    @Test
    void hardCutWithExactOverlapWhenNoBoundary() {
        String text = "甲".repeat(30) + "乙".repeat(70) + "丙".repeat(100);

        List<String> pieces = chunker.split(text);

        assertThat(pieces).hasSize(3);
        assertThat(pieces.get(0)).hasSize(100).isEqualTo(text.substring(0, 100));
        assertThat(pieces.get(1)).isEqualTo(text.substring(80, 180));
        assertThat(pieces.get(2)).isEqualTo(text.substring(160));
        assertOverlapAndCoverage(text, pieces, 20);
    }

    /** 在 [start + maxChars/2, end) 里有句号时，在句号之后断开，句号留在前一块。 */
    @Test
    void prefersCuttingAfterSentenceEnd() {
        String sentence = "签收后十五天内发现质量问题可以选择退货或换货。"; // 23 个字
        String text = sentence.repeat(10);

        List<String> pieces = chunker.split(text);

        assertThat(pieces).hasSizeGreaterThan(2);
        for (String piece : pieces) {
            assertThat(piece.length()).isLessThanOrEqualTo(100);
        }
        for (String piece : pieces.subList(0, pieces.size() - 1)) {
            assertThat(piece).endsWith("。");
        }
        assertOverlapAndCoverage(text, pieces, 20);
    }

    /** 句号离起点太近（不到 maxChars 的一半）时不在那里断，免得切出很短的块。 */
    @Test
    void ignoresBoundaryInFirstHalf() {
        String text = "短句。" + "长".repeat(150);

        List<String> pieces = chunker.split(text);

        assertThat(pieces.getFirst()).hasSize(100);
        assertOverlapAndCoverage(text, pieces, 20);
    }

    private static final KnowledgeDocument DOC = MarkdownKnowledgeReader.parse("returns.md", """
            ---
            title: 退换货规则
            version: 2026.10
            ---
            # 退换货规则

            开头的说明。

            ## 七天无理由退货

            ### 适用条件

            签收后 7 天内。

            ### 退货运费

            买家承担。

            ## 退款时效

            1 个工作日内发起退款。
            """);

    /** 分节（已给好）：标题路径以文档标题开头，同名的一级标题不重复，下级标题随上级变化，空节（「七天无理由退货」下直接是三级标题）不算。 */
    @Test
    void sectionsFollowHeadingPaths() {
        assertThat(chunker.sections(DOC))
                .extracting(MarkdownChunker.Section::headingPath)
                .containsExactly("退换货规则", "退换货规则 > 七天无理由退货 > 适用条件", "退换货规则 > 七天无理由退货 > 退货运费", "退换货规则 > 退款时效");
    }

    /** 每节切出的块按出现顺序连续编号，标题路径跟着所在的节。 */
    @Test
    void chunksAreNumberedContinuously() {
        List<KnowledgeChunk> chunks = chunker.chunk(DOC);

        assertThat(chunks).extracting(KnowledgeChunk::index).containsExactly(0, 1, 2, 3);
        assertThat(chunks.get(1).headingPath()).isEqualTo("退换货规则 > 七天无理由退货 > 适用条件");
        assertThat(chunks.get(1).body()).isEqualTo("签收后 7 天内。");
    }

    /** 真实的知识文档：每块都不超过 maxChars，每篇至少切出 3 块。 */
    @Test
    void realDocumentsRespectMaxChars() throws Exception {
        MarkdownChunker real = new MarkdownChunker(300, 50);
        for (KnowledgeDocument doc : new MarkdownKnowledgeReader("classpath:knowledge/*.md").read()) {
            List<KnowledgeChunk> chunks = real.chunk(doc);
            assertThat(chunks).as(doc.source()).hasSizeGreaterThanOrEqualTo(3);
            assertThat(chunks).allSatisfy(c -> assertThat(c.body().length()).isLessThanOrEqualTo(300));
        }
    }

    /** 相邻两块重叠 overlap 个字；把每块（第一块除外）开头的重叠去掉后依次拼起来，等于原文。 */
    private static void assertOverlapAndCoverage(String text, List<String> pieces, int overlap) {
        StringBuilder rebuilt = new StringBuilder(pieces.getFirst());
        for (int i = 1; i < pieces.size(); i++) {
            String previous = pieces.get(i - 1);
            String current = pieces.get(i);
            assertThat(current).startsWith(previous.substring(previous.length() - overlap));
            rebuilt.append(current.substring(overlap));
        }
        assertThat(rebuilt.toString()).isEqualTo(text);
    }
}
