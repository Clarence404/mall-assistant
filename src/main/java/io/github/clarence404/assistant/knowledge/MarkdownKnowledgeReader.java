package io.github.clarence404.assistant.knowledge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * 读取知识文档：按 {@code assistant.knowledge.location} 找到所有 Markdown 文件，拆出 front matter 和正文（第 4 章引入）。
 *
 * <p>所在层：入库流水线的第一步。上游是 {@code src/main/resources/knowledge/*.md}；下游是 {@link KnowledgeIngestionService}。
 *
 * <p>为什么自己解析而不用 Spring AI 的 {@code MarkdownDocumentReader}：它按标题或分隔线切文档，但只记住「当前这一级标题」，
 * 拿不到「退换货规则 > 七天无理由退货 > 适用条件」这样的完整标题路径，也不认 front matter。规则文档结构简单，自己解析几十行就够，
 * 还能看清每一步在做什么。
 *
 * <p>front matter 是文件开头两行 {@code ---} 之间的 {@code 键: 值}，这里只认最简单的一行一个键值（不支持 YAML 的嵌套与多行）：
 *
 * <pre>
 * ---
 * title: 退换货规则
 * version: 2026.10
 * updated: 2026-10-15
 * ---
 * </pre>
 */
public class MarkdownKnowledgeReader {

    private static final String FENCE = "---";

    private final String location;
    private final ResourcePatternResolver resolver;

    /**
     * 创建读取器。
     *
     * @param location 资源路径通配符，如 {@code classpath:knowledge/*.md}、{@code file:/tmp/kb/*.md}
     */
    public MarkdownKnowledgeReader(String location) {
        this.location = location;
        this.resolver = new PathMatchingResourcePatternResolver();
    }

    /** 读取的位置（入库报告与日志里显示）。 */
    public String location() {
        return location;
    }

    /**
     * 读出全部文档，按文件名排序（入库顺序固定，报告和日志每次一样）。
     *
     * @return 文档列表；目录里一篇都没有时是空列表（这时入库会把库里的旧块全部清掉）
     * @throws UncheckedIOException 位置写错（如 classpath 前缀拼错）或文件读不出来
     */
    public List<KnowledgeDocument> read() {
        try {
            Resource[] resources = resolver.getResources(location);
            List<KnowledgeDocument> documents = new ArrayList<>();
            for (Resource resource : resources) {
                if (resource.isReadable() && resource.getFilename() != null) {
                    String raw = resource.getContentAsString(StandardCharsets.UTF_8);
                    documents.add(parse(resource.getFilename(), raw));
                }
            }
            documents.sort(Comparator.comparing(KnowledgeDocument::source));
            return documents;
        } catch (IOException e) {
            throw new UncheckedIOException("读取知识文档失败：" + location, e);
        }
    }

    /**
     * 解析一篇文档：拆出 front matter 与正文。没有 front matter 时标题用文件名（去掉扩展名），版本与日期为空串。
     *
     * @param source 文件名
     * @param raw 文件原文
     * @return 解析好的文档
     */
    public static KnowledgeDocument parse(String source, String raw) {
        // Windows 上编辑过的文件可能是 \r\n 换行，统一成 \n，后面按行处理才不会多出 \r
        String text = raw.replace("\r\n", "\n");
        Map<String, String> front = new LinkedHashMap<>();
        String body = text;
        if (text.startsWith(FENCE + "\n")) {
            int end = text.indexOf("\n" + FENCE, FENCE.length());
            if (end > 0) {
                for (String line : text.substring(FENCE.length() + 1, end).split("\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        front.put(
                                line.substring(0, colon).strip(),
                                unquote(line.substring(colon + 1).strip()));
                    }
                }
                int bodyStart = text.indexOf('\n', end + 1);
                body = bodyStart < 0 ? "" : text.substring(bodyStart + 1);
            }
        }
        String fallbackTitle = source.endsWith(".md") ? source.substring(0, source.length() - 3) : source;
        return new KnowledgeDocument(
                source,
                front.getOrDefault("title", fallbackTitle),
                front.getOrDefault("version", ""),
                front.getOrDefault("updated", ""),
                body.strip(),
                text);
    }

    /** 去掉值两边的引号：{@code title: "退换货规则"} 和 {@code title: 退换货规则} 一样。 */
    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
