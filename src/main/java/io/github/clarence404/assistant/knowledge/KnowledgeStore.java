package io.github.clarence404.assistant.knowledge;

import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * 知识库的存储：Spring AI 的 {@link VectorStore}，再加上本项目需要、而 VectorStore 接口没有的几样能力（第 4 章引入）。
 *
 * <p>所在层：知识库的存储层。实现有两个：{@link MemoryKnowledgeStore}（默认，SimpleVectorStore）和 {@link PgVectorKnowledgeStore}
 * （PgVectorStore），由 {@code assistant.knowledge.store} 选择。上游是 {@link KnowledgeIngestionService} 与
 * {@link KnowledgeSearchService}。
 *
 * <p>为什么要包一层：VectorStore 只有「写入、按 ID 或条件删除、相似度检索」，没有「列出库里有哪些文档、各有几块」——增量入库要知道
 * 库里每篇文档上次的内容哈希，stats 接口要知道块数，这些只能按各自的存储方式去查（内存版遍历 Map，pgvector 查 SQL）。
 *
 * <p><b>不同模型的向量不能混存</b>：向量只有在同一个模型生成时才能比较——bge-m3 的第 1 维和 nomic-embed-text 的第 1 维含义完全不同，
 * 维度也不一样（1024 / 768）。本项目的做法是<b>每个模型一张表 / 一个文件</b>（名字带模型后缀），切换模型时各用各的、互不覆盖，
 * 切回来还能直接用；万一手动把两个模型指到了同一张表，入库和检索时会发现维度或元数据里的模型名对不上，报 INDEX_MODEL_MISMATCH，
 * 提示重建。
 */
public interface KnowledgeStore {

    /**
     * 库类型，如 {@code memory} / {@code pgvector}。
     *
     * @return 显示在 stats 与入库报告里
     */
    String type();

    /**
     * 存在哪：内存版是 JSON 文件路径（或「仅内存」），pgvector 是 {@code schema.表名}。
     *
     * @return 显示在 stats 与报错信息里
     */
    String location();

    /**
     * 底层的 Spring AI 向量库，用来写入、删除和检索。第一次调用时才真正创建（要先探测向量维度，模型不可用就在这里报错）；
     * 创建时检查已有数据的维度和当前模型是否一致。
     *
     * @return 向量库
     * @throws KnowledgeException 向量模型或向量库不可用，或已有数据的维度和当前模型对不上
     */
    VectorStore vectorStore();

    /**
     * 库里已有的文档汇总（按块的元数据归并）。不需要调向量模型，库还没建（第一次入库前）时返回空列表。
     *
     * @return 每篇文档一条，按 source 排序
     * @throws KnowledgeException 向量库连不上
     */
    List<StoredDocument> documents();

    /**
     * 库里的全部块：ID、文字（标题路径 + 正文）和元数据，<b>不含向量</b>（第 5 章引入）。不调向量模型；库还没建时返回空列表。
     *
     * <p>为什么要它：第 5 章的关键词检索（{@code rag.KeywordIndex}）要在内存里给每块建「词 → 出现在哪些块」的倒排索引，
     * 而 {@link VectorStore} 只能按向量找相似的块，列不出全部。块只有几十到几千块时，整个拿到内存里建索引最简单；
     * 再大就该换成数据库自带的全文检索（PostgreSQL 的 tsvector、Elasticsearch 等）。
     *
     * @return 按 source、块序号排好的全部块
     * @throws KnowledgeException 向量库连不上
     */
    List<Document> chunks();

    /**
     * 库里的内容变了（入库写完之后由 {@link KnowledgeIngestionService} 调用）：实现里缓存了 {@link #documents()} 的，在这里丢掉缓存。
     *
     * <p>审查修复时加的：每次检索都要用到文档汇总，pgvector 版把它缓存起来，不再每次查库（见 {@link PgVectorKnowledgeStore}）。
     * 内存版自己盯着写入（见 {@link MemoryKnowledgeStore}），不靠这个通知，所以默认什么也不做。
     */
    default void invalidate() {}

    /** 写完之后持久化：内存版存成 JSON 文件（配置了目录时），pgvector 每次写入都已经落库，什么也不做。 */
    void persist();

    /**
     * 清空重建：内存版清空并删除 JSON 文件，pgvector 删表（下次用到时按当前模型的维度重新建表建索引）。换了模型又指到同一张表时用。
     *
     * @throws KnowledgeException 向量库连不上
     */
    void reset();
}
