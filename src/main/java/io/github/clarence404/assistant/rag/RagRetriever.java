package io.github.clarence404.assistant.rag;

import io.github.clarence404.assistant.knowledge.KnowledgeException;
import io.github.clarence404.assistant.knowledge.KnowledgeHit;
import io.github.clarence404.assistant.knowledge.KnowledgeSearchService;
import io.github.clarence404.assistant.knowledge.KnowledgeStore;
import io.github.clarence404.assistant.knowledge.StoredDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * RAG 的「检索」一步：向量检索 + 关键词检索，RRF 融合，按门槛筛选，整理成编了号的引用来源（第 5 章引入）。
 *
 * <p>所在层：业务层。上游是 {@link RagService}；下游是第 4 章的 {@link KnowledgeSearchService}（向量检索，复用同一个向量库）、
 * {@link KnowledgeStore#chunks()}（关键词索引的原料）、{@link KeywordIndex}、{@link RrfFusion} 和 {@link ContextPacker}。
 *
 * <p>流程：
 *
 * <ol>
 *   <li>向量检索取前 {@code candidateK} 块（带余弦相似度）；开了混合检索时，关键词检索（BM25）也取前 {@code candidateK} 块
 *   <li>两份排名用 RRF 融合（{@link #rank}）；只用向量检索时就按相似度排
 *   <li>按门槛筛选、取前 topK（{@link #select}）：检索<b>总能</b>返回「最像的几块」，哪怕问题和知识库毫不相干，所以必须有门槛，
 *       把「最像但其实不相关」的块扔掉；一块都不剩，上层就不调模型、直接说没找到
 *   <li>去重、合并同一节的相邻块、按上下文预算截断、编号（{@link ContextPacker#pack}）
 * </ol>
 *
 * <p>关键词索引建在内存里：第一次用到时从库里取出全部块建好，之后每次检索先看库里的文档汇总（{@link KnowledgeStore#documents()}，
 * 不调模型）变没变，变了（重新入库过）才重建。几十到几千块时这样最简单；块更多时应改用数据库的全文检索。
 */
@Service
public class RagRetriever {

    /** 向量检索器的名字（{@link RagSource#foundBy()} 里用）。 */
    public static final String VECTOR = "vector";

    /** 关键词检索器的名字。 */
    public static final String KEYWORD = "keyword";

    private static final Logger log = LoggerFactory.getLogger(RagRetriever.class);

    private final KnowledgeSearchService searchService;
    private final KnowledgeStore store;
    private final RagProperties properties;
    private volatile CachedIndex cachedIndex;

    /**
     * 创建检索器。
     *
     * @param searchService 第 4 章的相似度检索
     * @param store 知识库存储（取全部块建关键词索引）
     * @param properties {@code assistant.rag.*}
     */
    public RagRetriever(KnowledgeSearchService searchService, KnowledgeStore store, RagProperties properties) {
        this.searchService = searchService;
        this.store = store;
        this.properties = properties;
    }

    /**
     * 当前配置。
     *
     * @return {@code assistant.rag.*}
     */
    public RagProperties properties() {
        return properties;
    }

    /**
     * 按配置的 topK 检索。
     *
     * @param query 检索用的问题
     * @return 检索结果
     * @throws KnowledgeException 向量模型 / 向量库不可用，或知识库需要重建
     */
    public Retrieval retrieve(String query) {
        return retrieve(query, query, properties.topK());
    }

    /**
     * 检索。向量检索和关键词检索可以用不同的问法：开了「问题改写」时，向量检索用改写后的问题，关键词检索用原话 + 改写后的问题（多一些字面线索）。
     *
     * @param vectorQuery 向量检索用的问题
     * @param keywordQuery 关键词检索用的问题
     * @param topK 最多留几块（评测时传不同的 k）
     * @return 检索结果
     * @throws KnowledgeException 向量模型 / 向量库不可用，或知识库需要重建
     */
    public Retrieval retrieve(String vectorQuery, String keywordQuery, int topK) {
        long start = System.nanoTime();
        List<KnowledgeHit> vectorHits = searchService.search(vectorQuery, properties.candidateK());
        List<KeywordIndex.KeywordHit> keywordHits =
                properties.hybrid() ? keywordIndex().search(keywordQuery, properties.candidateK()) : List.of();
        List<RagCandidate> ranked = rank(vectorHits, keywordHits);
        List<RagCandidate> selected = select(ranked, topK);
        List<RagSource> sources =
                ContextPacker.pack(selected, properties.contextBudgetChars(), properties.snippetChars());
        long ms = (System.nanoTime() - start) / 1_000_000;
        log.debug(
                "RAG 检索：向量 {} 块、关键词 {} 块，通过门槛 {} 块，交给模型 {} 条，{} ms",
                vectorHits.size(),
                keywordHits.size(),
                selected.size(),
                sources.size(),
                ms);
        return new Retrieval(sources, ranked, vectorHits.size(), keywordHits.size(), selected.size(), ms);
    }

    /**
     * 两路结果融合成一个排名。只用向量检索时按相似度排，fusedScore 就是相似度；混合检索时用 {@link RrfFusion}，两路都找到的块带上两个分数。
     *
     * @param vectorHits 向量检索结果，按相似度从高到低
     * @param keywordHits 关键词检索结果，按 BM25 从高到低（没开混合检索时为空）
     * @return 融合后的候选，按 fusedScore 从高到低
     */
    List<RagCandidate> rank(List<KnowledgeHit> vectorHits, List<KeywordIndex.KeywordHit> keywordHits) {
        Map<String, KnowledgeHit> byVector = new LinkedHashMap<>();
        vectorHits.forEach(h -> byVector.putIfAbsent(RagChunk.from(h).key(), h));
        Map<String, KeywordIndex.KeywordHit> byKeyword = new LinkedHashMap<>();
        keywordHits.forEach(h -> byKeyword.putIfAbsent(h.chunk().key(), h));

        if (!properties.hybrid()) {
            return byVector.values().stream()
                    .map(h -> new RagCandidate(RagChunk.from(h), h.score(), null, null, h.score(), List.of(VECTOR)))
                    .toList();
        }
        List<RrfFusion.Fused> fused = RrfFusion.fuse(
                List.of(List.copyOf(byVector.keySet()), List.copyOf(byKeyword.keySet())), properties.rrfK());
        List<RagCandidate> ranked = new ArrayList<>(fused.size());
        for (RrfFusion.Fused f : fused) {
            KnowledgeHit v = byVector.get(f.key());
            KeywordIndex.KeywordHit k = byKeyword.get(f.key());
            List<String> foundBy = new ArrayList<>();
            if (v != null) {
                foundBy.add(VECTOR);
            }
            if (k != null) {
                foundBy.add(KEYWORD);
            }
            ranked.add(new RagCandidate(
                    v != null ? RagChunk.from(v) : k.chunk(),
                    v != null ? v.score() : null,
                    k != null ? k.score() : null,
                    k != null ? k.coverage() : null,
                    f.score(),
                    List.copyOf(foundBy)));
        }
        return ranked;
    }

    /**
     * 按门槛筛选并取前 topK。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>候选算「相关」的条件（满足其一即可）：向量相似度不为 null 且 ≥ {@code min-score}；或覆盖率不为 null 且 ≥
     *       {@code keyword-min-coverage}
     *   <li>保持 ranked 的先后，取前 {@code topK} 个相关的
     * </ul>
     *
     * <p>为什么两个门槛：向量相似度和 BM25 不是一个尺度（见 {@link RrfFusion}），只被关键词检索找到的块没有相似度，用覆盖率判断
     * （「问题里的词有一半以上出现在这块里」）。门槛定多少要拿真实模型实测：太高，正常问题也说「没找到」；太低，八竿子打不着的问题也会拿几块
     * 不相关的资料去问模型，模型就可能硬凑一个答案。
     *
     * @param ranked 融合后的候选，按 fusedScore 从高到低
     * @param topK 最多留几个
     * @return 相关的候选，最多 topK 个；一个都不相关时为空列表
     */
    List<RagCandidate> select(List<RagCandidate> ranked, int topK) {
        // TODO ch05-3：向量相似度 ≥ min-score，或覆盖率 ≥ keyword-min-coverage，才算相关；保持先后，取前 topK 个
        return List.of();
    }

    /** 关键词索引：库里的文档汇总没变就复用，变了（重新入库过）就从全部块重建。 */
    KeywordIndex keywordIndex() {
        List<StoredDocument> signature = store.documents();
        CachedIndex current = cachedIndex;
        if (current == null || !current.signature().equals(signature)) {
            List<RagChunk> chunks = store.chunks().stream().map(RagChunk::from).toList();
            current = new CachedIndex(signature, KeywordIndex.build(chunks));
            cachedIndex = current;
            log.info("关键词索引已建好：{} 块", chunks.size());
        }
        return current.index();
    }

    /** 建好的关键词索引和建它时库里的文档汇总（用来判断要不要重建）。 */
    private record CachedIndex(List<StoredDocument> signature, KeywordIndex index) {}

    /**
     * 一次检索的结果。
     *
     * @param sources 编了号、交给模型的引用来源；为空表示没有相关的资料
     * @param ranked 融合后、筛选前的全部候选（评测与调试用）
     * @param vectorCandidates 向量检索返回了几块
     * @param keywordCandidates 关键词检索返回了几块（没开混合检索时为 0）
     * @param passed 通过门槛的候选数（取前 topK 之后）
     * @param durationMs 耗时，毫秒（含把问题向量化的一次模型调用）
     */
    public record Retrieval(
            List<RagSource> sources,
            List<RagCandidate> ranked,
            int vectorCandidates,
            int keywordCandidates,
            int passed,
            long durationMs) {}
}
