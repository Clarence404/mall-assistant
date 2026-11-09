package io.github.clarence404.assistant.knowledge;

import java.util.List;

/**
 * 一次增量入库的计划：每篇文档该新增、更新、跳过还是删除（第 4 章引入）。
 *
 * <p>所在层：入库流水线。{@link IngestPlanner} 产出，{@link KnowledgeIngestionService} 按计划执行。把「决定做什么」和「真的去做」
 * 分开，决定的逻辑不碰向量库，单独测试很方便。
 *
 * @param added 库里没有的文档：切块后写入
 * @param updated 库里有、但内容哈希变了的文档：先删旧块，再写新块
 * @param unchanged 库里有、内容哈希也一样的文档：跳过，不调向量模型
 * @param removed 库里有、磁盘上已经删掉的文档：删掉它的全部块
 */
public record IngestPlan(
        List<KnowledgeDocument> added,
        List<Replacement> updated,
        List<KnowledgeDocument> unchanged,
        List<StoredDocument> removed) {

    /**
     * 要更新的文档：新内容 + 库里的旧汇总（报告里要写删了几块旧的）。
     *
     * @param document 磁盘上的新内容
     * @param previous 库里的旧汇总
     */
    public record Replacement(KnowledgeDocument document, StoredDocument previous) {}
}
