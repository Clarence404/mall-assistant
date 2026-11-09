package io.github.clarence404.assistant.knowledge;

import java.util.List;

/**
 * 增量入库的判断：比对磁盘上的文档和库里已有的文档，产出 {@link IngestPlan}（第 4 章引入）。
 *
 * <p>所在层：入库流水线。上游是 {@link KnowledgeIngestionService}（传入读到的文档和 {@link KnowledgeStore#documents()}）；
 * 计划交回给它执行。
 *
 * <p>为什么要增量：调向量模型是整个入库里最慢、（用云端模型时）最花钱的一步。文档几十上百篇、一次只改一两篇时，没变的文档不该
 * 重新向量化。判断「变没变」用内容哈希（{@link ChunkDocumentFactory#docHash}），比比较修改时间可靠：复制、checkout 都会改时间，
 * 内容没变哈希就不变。
 */
public class IngestPlanner {

    private final ChunkDocumentFactory factory;

    /**
     * 创建判断器。
     *
     * @param factory 用它算每篇文档当前的内容哈希
     */
    public IngestPlanner(ChunkDocumentFactory factory) {
        this.factory = factory;
    }

    /**
     * 比对并产出计划，按 source（文件名）对应：
     *
     * <ul>
     *   <li>磁盘上有、库里没有 → {@code added}
     *   <li>两边都有、库里的 docHash 和 {@code factory.docHash(文档)} 不同 → {@code updated}（带上库里的旧汇总）
     *   <li>两边都有、docHash 相同 → {@code unchanged}
     *   <li>库里有、磁盘上没有 → {@code removed}
     * </ul>
     *
     * 各列表内保持输入的顺序（磁盘文档按 source 排序、库里的汇总也按 source 排序）。
     *
     * @param documents 磁盘上的全部文档
     * @param stored 库里已有的文档汇总
     * @return 入库计划
     */
    public IngestPlan plan(List<KnowledgeDocument> documents, List<StoredDocument> stored) {
        // TODO ch04-3 按 source 比对磁盘上的文档和库里的汇总，分成新增 / 更新 / 跳过 / 删除
        return new IngestPlan(List.of(), List.of(), List.of(), List.of());
    }
}
