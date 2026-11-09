package io.github.clarence404.assistant.knowledge;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

/**
 * 知识入库：读文档 → 判断增量 → 切块、向量化、写入向量库 → 出报告（第 4 章引入）。
 *
 * <p>所在层：业务层。上游是 {@link KnowledgeController}（{@code POST /api/knowledge/ingest}）和 {@link KnowledgeStartupIngest}
 * （启动时自动入库）；下游是 {@link MarkdownKnowledgeReader}、{@link IngestPlanner}、{@link ChunkDocumentFactory} 和
 * {@link KnowledgeStore}（向量化在 {@code VectorStore#add} 里发生：它调向量模型，把文字、元数据和向量一起存下来）。
 *
 * <p><b>幂等</b>：同样的文档入库几次，库里的内容都一样——没变的跳过；变了的先删旧块再写新块；块 ID 是确定性的，重复写入只会覆盖。
 * 中途失败（比如删了旧块、写新块时模型挂了）也不怕：库里这篇文档的块没了或不全，下次入库时 docHash 对不上，会重新写一遍。
 *
 * <p>方法加了 {@code synchronized}：页面上连点两次「入库」、或者启动时的自动入库还没跑完又手动触发，两次入库交错执行会互相删对方
 * 刚写的块，所以一次只跑一个。
 */
@Service
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    private final MarkdownKnowledgeReader reader;
    private final KnowledgeStore store;
    private final KnowledgeEmbedding embedding;
    private final KnowledgeProperties properties;

    /**
     * 创建入库服务。
     *
     * @param reader 文档读取器
     * @param store 知识库存储（memory 或 pgvector）
     * @param embedding 当前向量模型
     * @param properties 知识库配置（切块参数）
     */
    public KnowledgeIngestionService(
            MarkdownKnowledgeReader reader,
            KnowledgeStore store,
            KnowledgeEmbedding embedding,
            KnowledgeProperties properties) {
        this.reader = reader;
        this.store = store;
        this.embedding = embedding;
        this.properties = properties;
    }

    /**
     * 入库一次。
     *
     * @param rebuild true = 先清空库（内存版清空，pgvector 删表），再全部重新入库；换了模型又指到同一张表、或怀疑库里数据乱了时用
     * @return 入库报告
     * @throws KnowledgeException 向量模型或向量库不可用（503），或库里是别的模型生成的向量（409，提示重建）
     */
    public synchronized IngestReport ingest(boolean rebuild) {
        long start = System.nanoTime();
        List<KnowledgeDocument> documents = reader.read();
        ChunkDocumentFactory factory = new ChunkDocumentFactory(
                new MarkdownChunker(
                        properties.chunk().maxChars(), properties.chunk().overlap()),
                embedding.modelName(),
                embedding.profile().documentPrefix());
        try {
            if (rebuild) {
                store.reset();
            }
            List<StoredDocument> stored = store.documents();
            ensureSameModel(stored, embedding, store);
            IngestPlan plan = new IngestPlanner(factory).plan(documents, stored);
            List<IngestReport.DocumentChange> changes = apply(plan, factory);
            store.persist();
            List<StoredDocument> after = store.documents();
            IngestReport report = report(rebuild, changes, after, (System.nanoTime() - start) / 1_000_000);
            log.info(
                    "知识入库完成：新增 {} / 更新 {} / 跳过 {} / 删除 {} 篇，写入 {} 块、删除 {} 块，库里共 {} 篇 {} 块，耗时 {} ms（{}，{}）",
                    report.documentsAdded(),
                    report.documentsUpdated(),
                    report.documentsSkipped(),
                    report.documentsRemoved(),
                    report.chunksAdded(),
                    report.chunksRemoved(),
                    report.totalDocuments(),
                    report.totalChunks(),
                    report.durationMs(),
                    embedding.modelName(),
                    store.location());
            return report;
        } catch (RuntimeException e) {
            throw KnowledgeException.classify(e, embedding.modelName(), store.location());
        }
    }

    /**
     * 按计划执行，返回每篇文档的处理结果（顺序：新增、更新、跳过、删除，各自保持计划里的顺序）。
     *
     * <p>要求（测试按这个写），向量库用 {@code store.vectorStore()}，并且只在真有要写、要删的文档时才取：取的时候要探测维度、
     * 检查已有数据，全部跳过时不该去连向量模型（重启后文档都没变，模型没启动也能入库成功）：
     *
     * <ul>
     *   <li><b>新增</b>：{@code factory.toDocuments(文档)}，非空就 {@code add} 进向量库；结果 ADDED，chunksAdded = 块数
     *   <li><b>更新</b>：先按 source 删除旧块（{@link #bySource}），再 {@code add} 新块；结果 UPDATED，chunksAdded = 新块数，
     *       chunksRemoved = 旧汇总里的块数。为什么不能只 add：新版本切出的块可能比旧版少，按 ID 覆盖会留下多出来的旧块
     *   <li><b>跳过</b>：什么都不做，不调向量模型；结果 SKIPPED，两个块数都是 0
     *   <li><b>删除</b>：按 source 删除；结果 REMOVED，chunksRemoved = 旧汇总里的块数
     * </ul>
     *
     * 注意 {@code VectorStore#add} 传空列表时 SimpleVectorStore 会抛异常（正文为空的文档切不出块），要先判断。
     *
     * @param plan 入库计划
     * @param factory 切块与元数据
     * @return 每篇文档的处理结果
     */
    List<IngestReport.DocumentChange> apply(IngestPlan plan, ChunkDocumentFactory factory) {
        // TODO ch04-4 按计划写库：新增直接写，更新先按 source 删再写，删除按 source 删；只在有活干时才取 store.vectorStore()
        return List.of();
    }

    /**
     * 「source 等于某个文件名」的过滤条件。Spring AI 的过滤表达式与具体向量库无关：内存版在 Java 里逐块判断，pgvector 翻译成
     * {@code metadata::jsonb @@ '$.source == "returns.md"'::jsonpath}。
     *
     * @param source 文件名
     * @return 过滤表达式，传给 {@code VectorStore#delete(Filter.Expression)}
     */
    static Filter.Expression bySource(String source) {
        return new FilterExpressionBuilder().eq(ChunkMetadata.SOURCE, source).build();
    }

    /**
     * 库里有别的模型生成的块时拒绝继续（入库和检索都调用）。不同模型的向量不在同一个「坐标系」里，混在一起比较相似度毫无意义，
     * 维度不同时甚至直接报错；与其悄悄返回乱七八糟的结果，不如明确报 409 让人重建。
     *
     * @param stored 库里的文档汇总
     * @param embedding 当前向量模型
     * @param store 知识库存储（报错信息里写位置）
     * @throws KnowledgeException INDEX_MODEL_MISMATCH
     */
    static void ensureSameModel(List<StoredDocument> stored, KnowledgeEmbedding embedding, KnowledgeStore store) {
        List<String> others = stored.stream()
                .map(StoredDocument::embeddingModel)
                .filter(m -> !m.isEmpty() && !m.equals(embedding.modelName()))
                .distinct()
                .toList();
        if (!others.isEmpty()) {
            throw new KnowledgeException(
                    KnowledgeFailure.INDEX_MODEL_MISMATCH,
                    "向量库 " + store.location() + " 里的知识块是用 " + String.join("、", others) + " 生成的，当前向量模型是 "
                            + embedding.modelName()
                            + "：不同模型的向量不能混在一起比较，请重建（POST /api/knowledge/ingest?rebuild=true）。",
                    null);
        }
    }

    private IngestReport report(
            boolean rebuild, List<IngestReport.DocumentChange> changes, List<StoredDocument> after, long durationMs) {
        return new IngestReport(
                embedding.modelName(),
                store.type(),
                store.location(),
                rebuild,
                count(changes, IngestReport.Action.ADDED),
                count(changes, IngestReport.Action.UPDATED),
                count(changes, IngestReport.Action.SKIPPED),
                count(changes, IngestReport.Action.REMOVED),
                changes.stream()
                        .mapToInt(IngestReport.DocumentChange::chunksAdded)
                        .sum(),
                changes.stream()
                        .mapToInt(IngestReport.DocumentChange::chunksRemoved)
                        .sum(),
                after.size(),
                after.stream().mapToInt(StoredDocument::chunkCount).sum(),
                durationMs,
                List.copyOf(changes));
    }

    private static int count(List<IngestReport.DocumentChange> changes, IngestReport.Action action) {
        return (int) changes.stream().filter(c -> c.action() == action).count();
    }
}
