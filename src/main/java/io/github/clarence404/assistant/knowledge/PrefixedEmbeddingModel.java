package io.github.clarence404.assistant.knowledge;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 给向量模型加「查询前缀 / 文档前缀」的装饰器（第 4 章引入）。
 *
 * <p>所在层：模型接入。包住 Spring AI 自动配置的 {@code EmbeddingModel}（OllamaEmbeddingModel / OpenAiEmbeddingModel），
 * 交给向量库（SimpleVectorStore / PgVectorStore）使用；由 {@link KnowledgeEmbedding} 创建。
 *
 * <p><b>Embedding（向量化）是什么</b>：向量模型把一段文字变成一串固定长度的浮点数（bge-m3 是 1024 个，nomic-embed-text 是 768 个），
 * 这个长度叫<b>维度</b>。训练时让意思相近的文字得到方向相近的向量，于是「七天无理由退货要什么条件」和「七天无理由退货 > 适用条件」
 * 这一块的向量离得很近，哪怕字面不完全一样。检索就是比较问题向量和每块向量的夹角：<b>余弦相似度</b> = 两个向量夹角的余弦，
 * 1 表示方向完全相同，0 表示无关；<b>余弦距离</b> = 1 − 余弦相似度，越小越近。
 *
 * <p><b>为什么要前缀</b>：有的模型（nomic-embed-text、e5 系列）训练时约定，查询文字前加 {@code search_query: }、被检索的文档前加
 * {@code search_document: }，用来区分「问题」和「答案」这两种不对称的文字；不加也能跑，但检索效果明显变差。bge-m3 没有这个约定，
 * 两个前缀都是空串，这个装饰器就什么也不改。
 *
 * <p><b>怎么知道是查询还是文档</b>：靠向量库调用的方法区分（Spring AI 2.0.2 实测）——
 *
 * <ul>
 *   <li>检索：两种向量库都调 {@code embed(String 问题)}
 *   <li>入库：SimpleVectorStore 逐块调 {@code embed(Document)}；PgVectorStore 批量调 {@code embed(List<Document>, options, 分批策略)}，
 *       这个默认方法用 {@code getEmbeddingContent(Document)} 取每块的文字，再调 {@code call(EmbeddingRequest)}
 * </ul>
 *
 * 所以覆盖 {@code embed(String)} 加查询前缀，覆盖 {@code embed(Document)} 与 {@code getEmbeddingContent(Document)} 加文档前缀，
 * {@code call} 原样转发（它收到的文字已经带好前缀）。
 */
public class PrefixedEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final String queryPrefix;
    private final String documentPrefix;

    /**
     * 包装一个向量模型。
     *
     * @param delegate 真正的向量模型
     * @param queryPrefix 查询前缀，不需要时传空串
     * @param documentPrefix 文档前缀，不需要时传空串
     */
    public PrefixedEmbeddingModel(EmbeddingModel delegate, String queryPrefix, String documentPrefix) {
        this.delegate = delegate;
        this.queryPrefix = queryPrefix;
        this.documentPrefix = documentPrefix;
    }

    /** 被包装的向量模型。 */
    public EmbeddingModel delegate() {
        return delegate;
    }

    /**
     * 原样转发：批量入库时文字已经由 {@link #getEmbeddingContent} 加好了文档前缀。
     *
     * @param request 若干段文字
     * @return 每段文字一个向量，顺序与输入一致
     */
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return delegate.call(request);
    }

    /**
     * 检索时把问题变成向量：问题前加查询前缀，再交给真正的模型。
     *
     * @param text 用户的问题
     * @return 问题的向量
     */
    @Override
    public float[] embed(String text) {
        return delegate.embed(queryPrefix + text);
    }

    /**
     * 入库时把一块变成向量（SimpleVectorStore 走这里）：用 {@link #getEmbeddingContent} 取带文档前缀的文字，交给真正的模型。
     * 注意调的是 {@code delegate.embed(String)}，不能调本类的 {@link #embed(String)}，否则又被加上一次查询前缀。
     *
     * @param document 一块
     * @return 这块的向量
     */
    @Override
    public float[] embed(Document document) {
        return delegate.embed(getEmbeddingContent(document));
    }

    /**
     * 一块要拿去向量化的文字：文档前缀 + 块文字（PgVectorStore 批量入库走这里）。前缀只在这一刻加上，库里存的块文字不带前缀。
     *
     * @param document 一块
     * @return 带文档前缀的文字
     */
    @Override
    public String getEmbeddingContent(Document document) {
        String text = document.getText();
        return documentPrefix + (text == null ? "" : text);
    }

    /**
     * 维度交给真正的模型（Spring AI 的模型类会缓存探测结果）。
     *
     * @return 向量维度
     */
    @Override
    public int dimensions() {
        return delegate.dimensions();
    }
}
