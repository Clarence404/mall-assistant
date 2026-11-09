package io.github.clarence404.assistant.knowledge;

/**
 * 检索命中的一块：{@code GET /api/knowledge/search} 返回的列表元素（第 4 章引入）。
 *
 * <p>所在层：接口层的数据。由 {@link KnowledgeSearchService#search} 生成；第 5 章做 RAG 时，这些块会被拼进提示词并作为引用来源。
 *
 * @param content 块文字（标题路径 + 正文，不含模型前缀）
 * @param source 来源文件名
 * @param title 文档标题
 * @param headingPath 标题路径
 * @param chunkIndex 块序号
 * @param docVersion 文档版本
 * @param score 余弦相似度，保留 4 位小数：越接近 1 越相似；结果按它从高到低排列
 * @param distance 余弦距离 = 1 − score，pgvector 的 {@code <=>} 运算符算的就是它，越小越近
 */
public record KnowledgeHit(
        String content,
        String source,
        String title,
        String headingPath,
        Integer chunkIndex,
        String docVersion,
        double score,
        double distance) {}
