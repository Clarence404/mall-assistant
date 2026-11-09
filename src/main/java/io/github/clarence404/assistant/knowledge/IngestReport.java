package io.github.clarence404.assistant.knowledge;

import java.util.List;

/**
 * 一次入库的报告：{@code POST /api/knowledge/ingest} 的响应，启动时自动入库也会打到日志里（第 4 章引入）。
 *
 * <p>所在层：接口层的数据。由 {@link KnowledgeIngestionService#ingest} 生成。
 *
 * @param embeddingModel 用的向量模型
 * @param store 向量库类型（memory / pgvector）
 * @param location 向量库位置（文件路径或 schema.表名）
 * @param rebuild 是否先清空再全部重新入库
 * @param documentsAdded 新增的文档数
 * @param documentsUpdated 内容变了、重新入库的文档数
 * @param documentsSkipped 没变、跳过的文档数
 * @param documentsRemoved 磁盘上已删除、从库里清掉的文档数
 * @param chunksAdded 写入的块数（新增 + 更新）
 * @param chunksRemoved 删除的旧块数（更新 + 删除）
 * @param totalDocuments 入库后库里共有几篇文档
 * @param totalChunks 入库后库里共有几块
 * @param durationMs 耗时，毫秒（主要花在调向量模型上）
 * @param documents 每篇文档的处理结果
 */
public record IngestReport(
        String embeddingModel,
        String store,
        String location,
        boolean rebuild,
        int documentsAdded,
        int documentsUpdated,
        int documentsSkipped,
        int documentsRemoved,
        int chunksAdded,
        int chunksRemoved,
        int totalDocuments,
        int totalChunks,
        long durationMs,
        List<DocumentChange> documents) {

    /** 一篇文档怎么处理的。 */
    public enum Action {
        /** 新增：库里原来没有。 */
        ADDED,
        /** 更新：内容哈希变了，删旧块、写新块。 */
        UPDATED,
        /** 跳过：内容哈希没变，不调向量模型。 */
        SKIPPED,
        /** 删除：磁盘上已经没有这篇文档。 */
        REMOVED
    }

    /**
     * 一篇文档的处理结果。
     *
     * @param source 文件名
     * @param action 怎么处理的
     * @param chunksAdded 写入了几块
     * @param chunksRemoved 删除了几块旧的
     */
    public record DocumentChange(String source, Action action, int chunksAdded, int chunksRemoved) {}
}
