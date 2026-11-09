package io.github.clarence404.assistant.knowledge;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 知识库配置，前缀 {@code assistant.knowledge}（第 4 章引入）。
 *
 * <p>所在层：知识库的配置。由 {@code @ConfigurationPropertiesScan} 绑定，注入 {@link KnowledgeConfig}、{@link PgVectorConfig}、
 * {@link KnowledgeIngestionService} 等。每一项在 {@code application.yml} 里都有注释。
 *
 * <p>本章的流水线：{@code knowledge/*.md} → 读取（{@link MarkdownKnowledgeReader}）→ 切块（{@link MarkdownChunker}）→ 每块加元数据
 * （{@link ChunkDocumentFactory}）→ 向量模型把每块文字变成一串数字（Embedding）→ 写进向量库（{@link KnowledgeStore}）。
 * 检索时把问题也变成向量，在库里找「方向最接近」的几块（{@link KnowledgeSearchService}）。
 *
 * @param store 向量库类型：{@code memory}（默认，Spring AI 的 SimpleVectorStore，不需要 Docker 和数据库）或 {@code pgvector}
 *     （PostgreSQL + pgvector 扩展）
 * @param location 知识文档在哪：Spring 资源路径通配符，默认 {@code classpath:knowledge/*.md}；也可以写 {@code file:/某个目录/*.md}
 * @param ingestOnStartup 启动后是否自动入库一次。入库是增量的，没变的文档直接跳过；向量模型或向量库不可用时只打警告，不影响启动和对话
 * @param embeddingModel 当前向量模型的名字（如 {@code bge-m3}）。各 profile 的 yml 里从对应厂商的配置引用过来，用于：选前缀、给表名 /
 *     文件名加后缀、写进每块的元数据。真正调用的模型由 Spring AI 自动配置的 {@code EmbeddingModel} 决定，这里只是「名字」
 * @param dimensions 向量维度：0 = 启动后第一次用到时调一次模型探测（推荐）；大于 0 = 按配置，不探测
 * @param chunk 切块参数
 * @param search 检索参数
 * @param memory 内存向量库的持久化
 * @param pgvector pgvector 的连接与表
 * @param modelProfiles 按模型区分的前缀（如 nomic-embed-text 要求加 {@code search_query: } / {@code search_document: }）
 */
@ConfigurationProperties("assistant.knowledge")
public record KnowledgeProperties(
        @DefaultValue("memory") StoreType store,
        @DefaultValue("classpath:knowledge/*.md") String location,
        @DefaultValue("true") boolean ingestOnStartup,
        @DefaultValue("") String embeddingModel,
        @DefaultValue("0") int dimensions,
        @DefaultValue Chunk chunk,
        @DefaultValue Search search,
        @DefaultValue Memory memory,
        @DefaultValue PgVector pgvector,
        List<ModelProfile> modelProfiles) {

    /** 没配 model-profiles 时给空列表，免得到处判空。 */
    public KnowledgeProperties {
        modelProfiles = modelProfiles == null ? List.of() : List.copyOf(modelProfiles);
    }

    /**
     * 找当前模型对应的前缀配置：模型名以 {@code match} 开头就算命中（{@code nomic-embed-text:v1.5} 也能命中 {@code nomic-embed-text}），
     * 都不命中时前缀为空（bge-m3 等不需要前缀的模型）。
     *
     * @param model 模型名
     * @return 命中的配置，或前缀都为空的默认配置；从不返回 null
     */
    public ModelProfile profileFor(String model) {
        String name = model == null ? "" : model.toLowerCase();
        return modelProfiles.stream()
                .filter(p -> p.match() != null && !p.match().isBlank())
                .filter(p -> name.startsWith(p.match().toLowerCase()))
                .findFirst()
                .orElse(new ModelProfile(name, "", ""));
    }

    /** 向量库类型。 */
    public enum StoreType {
        /** Spring AI 的 SimpleVectorStore：向量放在 JVM 内存里，可选存成 JSON 文件，零依赖，适合学习与小数据量。 */
        MEMORY,
        /** PostgreSQL + pgvector 扩展：向量存在表里，带 HNSW 索引，适合真实项目。 */
        PGVECTOR
    }

    /**
     * 切块参数。
     *
     * <p>为什么要切块：向量是对「一段文字整体意思」的压缩，一整篇文档压成一个向量，什么都像又什么都不像，检索不准；切成一小段
     * 讲一件事的块，问题才能和块对上。块也不能太小，太小会丢掉上下文（「需满足以下条件」——什么条件？）。
     *
     * <p>为什么要重叠：按长度硬切时，一句话可能正好被切成两半，两块都不完整。让下一块从上一块末尾往回 {@code overlap} 个字处开始，
     * 跨边界的内容在两块里都完整出现一次，代价是多存一点重复内容。
     *
     * @param maxChars 每块正文最多多少个字符（中文一个字算一个）。默认 300：本项目的规则文档一节大多一两百字，一节一块；
     *     超长的节才再切
     * @param overlap 相邻两块重叠多少个字符，默认 50；必须小于 {@code maxChars} 的一半，否则切出来的块大半是重复的
     */
    public record Chunk(
            @DefaultValue("300") int maxChars,
            @DefaultValue("50") int overlap) {

        /** 启动时就校验，配错了立刻报错，而不是入库时才发现切出一堆重复块。 */
        public Chunk {
            if (maxChars < 50) {
                throw new IllegalArgumentException("assistant.knowledge.chunk.max-chars 不能小于 50，当前 " + maxChars);
            }
            if (overlap < 0 || overlap * 2 >= maxChars) {
                throw new IllegalArgumentException(
                        "assistant.knowledge.chunk.overlap 必须在 0 到 max-chars 的一半之间，当前 " + overlap);
            }
        }
    }

    /**
     * 检索参数。
     *
     * @param defaultTopK 不传 topK 时返回几块
     * @param maxTopK topK 的上限，传得再大也只返回这么多（调试接口，防止一次拉出整个库）
     */
    public record Search(
            @DefaultValue("4") int defaultTopK,
            @DefaultValue("10") int maxTopK) {}

    /**
     * 内存向量库的持久化。
     *
     * @param dir 存 JSON 文件的目录，默认 {@code target/knowledge}（相对工作目录，{@code mvn clean} 会清掉）；留空 = 不持久化，
     *     每次启动都重新入库。文件名按模型区分：{@code knowledge_chunk_<模型>.json}
     */
    public record Memory(@DefaultValue("target/knowledge") String dir) {}

    /**
     * pgvector 配置，只在 {@code store=pgvector} 时使用。
     *
     * @param url JDBC 地址，如 {@code jdbc:postgresql://localhost:5432/mall_assistant}
     * @param username 数据库用户
     * @param password 数据库密码；真实密码写进不入库的 config/application-local.yml 或环境变量
     * @param schema 表所在的 schema，默认 public
     * @param table 表名；留空 = 按模型自动生成（{@code knowledge_chunk_bge_m3}、{@code knowledge_chunk_nomic_embed_text}），
     *     不同模型的向量各存一张表
     * @param initializeSchema 是否自动建扩展、建表、建 HNSW 索引（{@code CREATE ... IF NOT EXISTS}，已存在就不动）
     * @param maxPoolSize 连接池最多几个连接；入库和检索都是偶尔调用，几个就够
     */
    public record PgVector(
            @DefaultValue("jdbc:postgresql://localhost:5432/mall_assistant")
            String url,

            @DefaultValue("mall") String username,
            @DefaultValue("") String password,
            @DefaultValue("public") String schema,
            @DefaultValue("") String table,
            @DefaultValue("true") boolean initializeSchema,
            @DefaultValue("4") int maxPoolSize) {}

    /**
     * 某个向量模型要求的前缀。
     *
     * <p>有些向量模型训练时就约定「查询」和「文档」用不同的前缀，让模型知道这段文字是拿来问的还是拿来被找的：nomic-embed-text
     * 要求查询加 {@code search_query: }、文档加 {@code search_document: }，不加检索效果明显变差；bge-m3 不需要前缀。
     * 前缀只在调用模型时加上，不写进库里存的块文字。
     *
     * @param match 模型名前缀，如 {@code nomic-embed-text}
     * @param queryPrefix 检索时加在问题前面的文字
     * @param documentPrefix 入库时加在每块文字前面的文字
     */
    public record ModelProfile(
            String match,
            @DefaultValue("") String queryPrefix,
            @DefaultValue("") String documentPrefix) {

        /** 前缀为 null 时当成空串（yml 里写了 {@code query-prefix:} 却没写值）。 */
        public ModelProfile {
            queryPrefix = queryPrefix == null ? "" : queryPrefix;
            documentPrefix = documentPrefix == null ? "" : documentPrefix;
        }
    }
}
