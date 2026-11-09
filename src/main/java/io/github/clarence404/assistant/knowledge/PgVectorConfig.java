package io.github.clarence404.assistant.knowledge;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIdType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * pgvector 向量库的装配：自己建连接池和 {@link PgVectorStore}，只在 {@code assistant.knowledge.store=pgvector} 时生效（第 4 章引入）。
 *
 * <p>所在层：知识库的配置。产出 {@link PgVectorKnowledgeStore}（{@link KnowledgeStore} 的实现）。
 *
 * <p>为什么不用 {@code spring-ai-starter-vector-store-pgvector}：Starter 依赖 Spring Boot 的 JDBC 自动配置，类路径上一有它，应用启动就要求
 * 配好 {@code spring.datasource.*}，默认的 memory 模式（不装数据库）也会因为没有数据源而起不来。这里只引入不带自动配置的
 * {@code spring-ai-pgvector-store}，在选了 pgvector 时才自己建数据源，其余时候类路径上有这些类也不会生效。
 *
 * <p>数据库要求：PostgreSQL 装了 pgvector 扩展（Docker 镜像 {@code pgvector/pgvector:0.8.1-pg17} 自带）。{@code initialize-schema=true}
 * 时 PgVectorStore 会执行 {@code CREATE EXTENSION IF NOT EXISTS vector / hstore / "uuid-ossp"}，用户要有建扩展的权限（镜像里用
 * {@code POSTGRES_USER} 建的用户是超级用户，没问题）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "assistant.knowledge.store", havingValue = "pgvector")
public class PgVectorConfig {

    /** 自动生成的表名前缀，后面接模型后缀，如 {@code knowledge_chunk_bge_m3}。 */
    static final String TABLE_PREFIX = "knowledge_chunk_";

    /**
     * 知识库专用的连接池（HikariCP，和 Spring Boot 默认用的是同一个连接池）。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>url / username / password / 最大连接数取自 {@code assistant.knowledge.pgvector.*}，连接池名 {@code knowledge-pgvector}
     *   <li>连接超时 3 秒：数据库没启动时快速失败，接口返回 VECTOR_STORE_UNAVAILABLE，而不是卡 30 秒（Hikari 默认）
     *   <li>{@code initializationFailTimeout = -1}：<b>启动时不连数据库</b>。Hikari 默认在创建连接池时就连一次，连不上直接抛异常，
     *       数据库没启动整个应用就起不来；设成 -1 后第一次真正用到时才连，对话照常可用
     *   <li>{@code minimumIdle = 0}：不常驻空闲连接，入库和检索都是偶尔调用
     * </ul>
     *
     * @param properties 知识库配置
     * @return 连接池；应用关闭时由 Spring 调用 {@code close()} 关掉
     */
    @Bean(destroyMethod = "close")
    public HikariDataSource knowledgeDataSource(KnowledgeProperties properties) {
        KnowledgeProperties.PgVector pg = properties.pgvector();
        HikariConfig config = new HikariConfig();
        config.setPoolName("knowledge-pgvector");
        config.setJdbcUrl(pg.url());
        config.setUsername(pg.username());
        config.setPassword(pg.password());
        config.setMaximumPoolSize(pg.maxPoolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(3_000);
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    /**
     * pgvector 知识库：表名按模型生成，PgVectorStore 在第一次用到时才由 {@link #createStore} 创建。
     *
     * @param dataSource 上面的连接池
     * @param embedding 当前向量模型
     * @param properties 知识库配置
     * @return 知识库存储
     */
    @Bean
    public PgVectorKnowledgeStore pgVectorKnowledgeStore(
            HikariDataSource dataSource, KnowledgeEmbedding embedding, KnowledgeProperties properties) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        KnowledgeProperties.PgVector pg = properties.pgvector();
        String table = tableName(pg, embedding);
        return new PgVectorKnowledgeStore(
                jdbc, pg.schema(), table, embedding, dimensions -> createStore(jdbc, embedding, pg, table, dimensions));
    }

    /**
     * 表名：配置了 {@code assistant.knowledge.pgvector.table} 就用它，否则 {@code knowledge_chunk_<模型后缀>}。
     *
     * @param pg pgvector 配置
     * @param embedding 当前向量模型
     * @return 表名
     */
    static String tableName(KnowledgeProperties.PgVector pg, KnowledgeEmbedding embedding) {
        return pg.table() == null || pg.table().isBlank()
                ? TABLE_PREFIX + embedding.storeSuffix()
                : pg.table().strip();
    }

    /**
     * 创建并初始化 PgVectorStore。
     *
     * <p>要求（测试按这个写），用 {@code PgVectorStore.builder(jdbc, embedding.model())}：
     *
     * <ul>
     *   <li>{@code schemaName} / {@code vectorTableName}：传进来的 schema 与表名（默认表名是 {@code vector_store}，所有模型挤一张表）
     *   <li>{@code dimensions}：传进来的维度。不传时 PgVectorStore 会自己调模型探测，失败就悄悄按 1536 建表
     *   <li>{@code distanceType(COSINE_DISTANCE)}：<b>余弦距离</b>（pgvector 运算符 {@code <=>}）= 1 − 余弦相似度，只看方向不看长度，
     *       文本向量最常用；检索结果的 score 由 PgVectorStore 换算成 1 − 距离，和内存版的余弦相似度口径一致
     *   <li>{@code indexType(HNSW)}：<b>HNSW</b>（分层可导航小世界图）是近似最近邻索引：把向量连成多层的「邻居图」，检索时从顶层
     *       粗跳到底层细找，不用和每一行都算一遍距离，百万行也是毫秒级；代价是建索引慢、占内存，结果是「近似」最近（召回率通常 95% 以上）。
     *       另一个选项 IVFFLAT 建得快但查得慢，而且要先有数据再建索引才准
     *   <li>{@code idType(UUID)}：id 列用 uuid 类型，和 {@link ChunkDocumentFactory} 生成的确定性 UUID 对应
     *   <li>{@code initializeSchema}：取配置。true 时建扩展、建表、建索引（都带 IF NOT EXISTS，已存在不动）
     * </ul>
     *
     * 最后调 {@code afterPropertiesSet()}：PgVectorStore 是在这个方法里建表的，它不是 Spring Bean，没人替我们调。
     *
     * @param jdbc JdbcTemplate
     * @param embedding 当前向量模型（用包好前缀的 {@code embedding.model()}）
     * @param pg pgvector 配置
     * @param table 表名
     * @param dimensions 向量维度
     * @return 已初始化的 PgVectorStore
     */
    static PgVectorStore createStore(
            JdbcTemplate jdbc,
            KnowledgeEmbedding embedding,
            KnowledgeProperties.PgVector pg,
            String table,
            int dimensions) {
        PgVectorStore store = PgVectorStore.builder(jdbc, embedding.model())
                .schemaName(pg.schema())
                .vectorTableName(table)
                .dimensions(dimensions)
                .distanceType(PgDistanceType.COSINE_DISTANCE)
                .indexType(PgIndexType.HNSW)
                .idType(PgIdType.UUID)
                .initializeSchema(pg.initializeSchema())
                .build();
        store.afterPropertiesSet();
        return store;
    }
}
