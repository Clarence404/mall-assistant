package io.github.clarence404.assistant.knowledge;

/**
 * 读进来的一篇知识文档（第 4 章引入）。
 *
 * <p>所在层：知识库的数据。由 {@link MarkdownKnowledgeReader} 从 {@code knowledge/*.md} 读出；下游是 {@link MarkdownChunker}（切块）
 * 和 {@link IngestPlanner}（按内容哈希判断要不要重新入库）。
 *
 * @param source 文件名，如 {@code returns.md}：同一篇文档的所有块都带着它，增量入库时按它删除旧块
 * @param title front matter 里的 {@code title}，也是标题路径的第一级
 * @param version front matter 里的 {@code version}（规则的版本号），写进每块的元数据，第 5 章回答时可以说明「依据哪一版规则」
 * @param updated front matter 里的 {@code updated}（更新日期）
 * @param body 去掉 front matter 之后的 Markdown 正文
 * @param raw 文件原文（含 front matter），算内容哈希用：front matter 改了（比如升了版本号）也算文档变了
 */
public record KnowledgeDocument(String source, String title, String version, String updated, String body, String raw) {}
