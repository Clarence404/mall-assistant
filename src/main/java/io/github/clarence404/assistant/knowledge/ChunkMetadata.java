package io.github.clarence404.assistant.knowledge;

/**
 * 每块元数据的键名（第 4 章引入）。
 *
 * <p>所在层：知识库的数据约定。{@link ChunkDocumentFactory} 写入，{@link KnowledgeStore} 统计、{@link KnowledgeSearchService} 展示、
 * {@link KnowledgeIngestionService} 按 {@link #SOURCE} 删除时读取。
 *
 * <p>为什么每块都要带元数据：向量库只按「向量离得近不近」找块，找到之后还得知道这块是哪来的：
 *
 * <ul>
 *   <li><b>回答引用来源</b>（第 5 章）：「依据《退换货规则》七天无理由退货 > 适用条件」，靠 source / title / headingPath
 *   <li><b>增量入库</b>：文档变了要删掉它的旧块，按 source 过滤删除；判断变没变靠 docHash
 *   <li><b>防止混用模型</b>：不同向量模型生成的向量不能放在一起比较，每块记下 embeddingModel，发现不一致就报错
 *   <li><b>过滤检索</b>：以后可以只在某篇文档、某个版本里找（向量库支持按元数据过滤）
 * </ul>
 *
 * 值只用字符串和整数：向量库要把元数据存成 JSON（pgvector 存在 json 列里，内存版存成 JSON 文件），简单类型往返不会变样。
 */
public final class ChunkMetadata {

    /** 来源文件名，如 {@code returns.md}。 */
    public static final String SOURCE = "source";

    /** 文档标题（front matter 的 title）。 */
    public static final String TITLE = "title";

    /** 标题路径，如 {@code 退换货规则 > 七天无理由退货 > 适用条件}。 */
    public static final String HEADING_PATH = "headingPath";

    /** 块在整篇文档里的序号，从 0 开始。 */
    public static final String CHUNK_INDEX = "chunkIndex";

    /** 这篇文档一共切成几块。 */
    public static final String CHUNK_COUNT = "chunkCount";

    /** 文档的内容哈希（含切块参数），增量入库靠它判断文档变没变。 */
    public static final String DOC_HASH = "docHash";

    /** 这一块文字的哈希，排查「同一段话是不是被重复入库」时用。 */
    public static final String CHUNK_HASH = "chunkHash";

    /** 文档版本（front matter 的 version）。 */
    public static final String DOC_VERSION = "docVersion";

    /** 文档更新日期（front matter 的 updated）。 */
    public static final String DOC_UPDATED = "docUpdated";

    /** 生成这块向量的 embedding 模型名。 */
    public static final String EMBEDDING_MODEL = "embeddingModel";

    private ChunkMetadata() {}
}
