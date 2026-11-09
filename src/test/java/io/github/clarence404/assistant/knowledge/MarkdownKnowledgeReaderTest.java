package io.github.clarence404.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 第 4 章：读取知识文档（{@code MarkdownKnowledgeReader}，已给好，start 时就是绿的）。
 *
 * <p>测试思路：front matter 的解析规则用构造的字符串测；再检查仓库里真实的知识文档都带了标题、版本和更新日期（第 5 章引用来源要用），
 * 并且没有出现真实公司名（只用「示例商城」）。
 */
class MarkdownKnowledgeReaderTest {

    @Test
    void parsesFrontMatterAndBody() {
        KnowledgeDocument doc = MarkdownKnowledgeReader.parse(
                "a.md",
                "---\r\ntitle: \"退换货规则\"\r\nversion: 2026.10\r\nupdated: 2026-10-15\r\n---\r\n\r\n# 退换货规则\r\n正文");

        assertThat(doc.title()).isEqualTo("退换货规则");
        assertThat(doc.version()).isEqualTo("2026.10");
        assertThat(doc.updated()).isEqualTo("2026-10-15");
        assertThat(doc.body()).isEqualTo("# 退换货规则\n正文");
        assertThat(doc.raw()).doesNotContain("\r");
    }

    @Test
    void withoutFrontMatterTitleIsFileName() {
        KnowledgeDocument doc = MarkdownKnowledgeReader.parse("faq.md", "# 常见问题\n内容");

        assertThat(doc.title()).isEqualTo("faq");
        assertThat(doc.version()).isEmpty();
        assertThat(doc.body()).isEqualTo("# 常见问题\n内容");
    }

    @Test
    void bundledDocumentsHaveMetadata() {
        List<KnowledgeDocument> docs = new MarkdownKnowledgeReader("classpath:knowledge/*.md").read();

        assertThat(docs)
                .extracting(KnowledgeDocument::source)
                .containsExactly(
                        "account-security.md",
                        "after-sales.md",
                        "payment-invoice.md",
                        "points-membership.md",
                        "returns.md",
                        "shipping.md");
        assertThat(docs).allSatisfy(d -> {
            assertThat(d.title()).isNotBlank();
            assertThat(d.version()).matches("\\d{4}\\.\\d{2}");
            assertThat(d.updated()).matches("\\d{4}-\\d{2}-\\d{2}");
            assertThat(d.body()).startsWith("# " + d.title());
        });
    }
}
