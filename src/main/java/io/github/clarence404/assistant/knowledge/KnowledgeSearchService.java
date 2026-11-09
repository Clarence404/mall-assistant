package io.github.clarence404.assistant.knowledge;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 相似度检索与知识库概况（第 4 章引入）。本章只做检索调试，不接进对话；第 5 章 RAG 会用检索结果来回答。
 *
 * <p>所在层：业务层。上游是 {@link KnowledgeController}（{@code GET /api/knowledge/search}、{@code /stats}）；下游是
 * {@link KnowledgeStore}。
 *
 * <p>检索是怎么做的：问题先经向量模型变成向量（带查询前缀，见 {@link PrefixedEmbeddingModel}），向量库在全部块里找余弦相似度最高的
 * topK 块。注意它找的是「意思相近」，不是「包含关键词」：问「东西坏了能退吗」也能找到「质量问题退换货」，哪怕一个字都不一样；
 * 反过来，只差一个数字的两句话（「7 天」和「15 天」）向量几乎一样，所以检索结果只是「候选」，回答时还要靠模型读原文。
 */
@Service
public class KnowledgeSearchService {

    private final KnowledgeStore store;
    private final KnowledgeEmbedding embedding;
    private final KnowledgeProperties properties;

    /**
     * 创建检索服务。
     *
     * @param store 知识库存储
     * @param embedding 当前向量模型
     * @param properties 知识库配置（topK 的默认值与上限）
     */
    public KnowledgeSearchService(KnowledgeStore store, KnowledgeEmbedding embedding, KnowledgeProperties properties) {
        this.store = store;
        this.embedding = embedding;
        this.properties = properties;
    }

    /**
     * 检索：库里有别的模型生成的块时先报 409（{@link KnowledgeIngestionService#ensureSameModel}），再做相似度检索。
     *
     * @param query 问题，不能为空
     * @param topK 返回几块；null 用默认值，超出 1 到上限的范围时收紧到范围内
     * @return 按相似度从高到低排列的命中块；库是空的时候返回空列表
     * @throws KnowledgeException 向量模型或向量库不可用（503），或需要重建（409）
     */
    public List<KnowledgeHit> search(String query, Integer topK) {
        int k = topK == null
                ? properties.search().defaultTopK()
                : Math.clamp(topK, 1, properties.search().maxTopK());
        try {
            KnowledgeIngestionService.ensureSameModel(store.documents(), embedding, store);
            return similaritySearch(query, k);
        } catch (RuntimeException e) {
            throw KnowledgeException.classify(e, embedding.modelName(), store.location());
        }
    }

    /**
     * 真正的相似度检索与结果换算。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>用 {@code SearchRequest.builder().query(query).topK(topK).similarityThresholdAll().build()} 调
     *       {@code store.vectorStore().similaritySearch(...)}。{@code similarityThresholdAll()} 表示不按分数过滤，调试时想看到
     *       「最像的几块到底有多像」，哪怕分数很低
     *   <li>每个结果转成 {@link KnowledgeHit}：content = {@code getText()}，source / title / headingPath / chunkIndex / docVersion 取元数据
     *       （键名见 {@link ChunkMetadata}，chunkIndex 从 JSON 读回来可能是 Integer 也可能是 Long，用 {@code Number#intValue()}）
     *   <li><b>score</b> = {@code Document#getScore()}，即余弦相似度。两种库口径一致：SimpleVectorStore 直接算余弦相似度；
     *       PgVectorStore 查出的是余弦距离，已经换算成 1 − 距离。保留 4 位小数（{@code Math.round(x * 10000) / 10000.0}）；
     *       getScore() 为 null 时按 0 处理
     *   <li><b>distance</b> = 1 − score，同样保留 4 位小数
     *   <li>结果按 score 从高到低排列（向量库已经排好，不用再排）
     * </ul>
     *
     * @param query 问题
     * @param topK 返回几块，已经收紧到合法范围
     * @return 命中块
     */
    List<KnowledgeHit> similaritySearch(String query, int topK) {
        // TODO ch04-5 用 SearchRequest 做相似度检索，把每个 Document 换成 KnowledgeHit（分数与距离保留 4 位小数）
        return List.of();
    }

    // TODO ch04-5 需要的话，把 Document → KnowledgeHit 的换算写成私有方法放在这里

    /**
     * 知识库概况。模型不可用时也能返回：维度第一次会调一次模型探测（之后用缓存），探测失败就为 null 并在 warning 里说明；
     * 库里的文档汇总直接查存储，不需要模型。
     *
     * @return 概况
     * @throws KnowledgeException 向量库连不上
     */
    public KnowledgeStats stats() {
        List<StoredDocument> documents = store.documents();
        String warning = null;
        Integer dimensions = null;
        if (!embedding.configured()) {
            warning = "没有配置向量模型，知识库不可用。";
        } else {
            try {
                dimensions = embedding.dimensions();
                KnowledgeIngestionService.ensureSameModel(documents, embedding, store);
            } catch (KnowledgeException e) {
                warning = e.getMessage();
            }
            if (warning == null && documents.isEmpty()) {
                warning = "知识库还是空的：点「入库」或调用 POST /api/knowledge/ingest。";
            }
        }
        return new KnowledgeStats(
                store.type(),
                store.location(),
                embedding.modelName(),
                dimensions != null ? dimensions : embedding.dimensionsIfKnown(),
                embedding.profile().queryPrefix(),
                embedding.profile().documentPrefix(),
                documents.size(),
                documents.stream().mapToInt(StoredDocument::chunkCount).sum(),
                documents,
                warning);
    }

    private static String text(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? "" : value.toString();
    }
}
