package io.github.clarence404.assistant.knowledge;

/**
 * 切出来的一块（第 4 章引入）。
 *
 * <p>所在层：知识库的数据。由 {@link MarkdownChunker} 产出；{@link ChunkDocumentFactory} 再给它加上元数据，变成 Spring AI 的
 * {@code Document} 写进向量库。
 *
 * @param index 在整篇文档里的序号，从 0 开始
 * @param headingPath 标题路径，如 {@code 退换货规则 > 七天无理由退货 > 适用条件}
 * @param body 正文（不含标题）
 */
public record KnowledgeChunk(int index, String headingPath, String body) {}
