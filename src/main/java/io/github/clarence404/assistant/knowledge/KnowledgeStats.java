package io.github.clarence404.assistant.knowledge;

import java.util.List;

/**
 * 知识库概况：{@code GET /api/knowledge/stats} 的响应（第 4 章引入）。
 *
 * <p>所在层：接口层的数据。由 {@link KnowledgeSearchService#stats} 生成。
 *
 * @param store 向量库类型（memory / pgvector）
 * @param location 向量库位置（文件路径或 schema.表名）
 * @param embeddingModel 当前向量模型
 * @param dimensions 向量维度；模型不可用、还没探测出来时为 null
 * @param queryPrefix 当前模型的查询前缀（空串表示不加）
 * @param documentPrefix 当前模型的文档前缀
 * @param documentCount 库里有几篇文档
 * @param chunkCount 库里有几块
 * @param documents 每篇文档的汇总
 * @param warning 需要注意的问题（模型不可用、库里有别的模型生成的块等），没有时为 null
 */
public record KnowledgeStats(
        String store,
        String location,
        String embeddingModel,
        Integer dimensions,
        String queryPrefix,
        String documentPrefix,
        int documentCount,
        int chunkCount,
        List<StoredDocument> documents,
        String warning) {}
