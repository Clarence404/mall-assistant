package io.github.clarence404.assistant.knowledge;

import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.regex.Pattern;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * pgvector 向量库：PostgreSQL 的一张表，每行一块（id、content 文字、metadata 元数据、embedding 向量），带 HNSW 索引（第 4 章引入）。
 *
 * <p>所在层：知识库的存储层，{@link KnowledgeStore} 的 pgvector 实现。由 {@link PgVectorConfig} 在 {@code store=pgvector} 时创建；
 * 真正的 {@link PgVectorStore} 由传进来的工厂函数在第一次用到时创建（要先知道维度）。
 *
 * <p>为什么懒创建：PgVectorStore 初始化时要建表 {@code embedding vector(维度)}，维度要调向量模型探测。Spring AI 2.0.2 的 PgVectorStore
 * 在探测失败时<b>不报错</b>，而是打一行警告、按 OpenAI 的 1536 维建表——模型临时连不上，就会建出一张维度错误的表。所以这里自己先探测
 * 维度，失败就报 EMBEDDING_UNAVAILABLE，成功了再把维度明确传给 PgVectorStore，并且不在启动时做这件事。
 *
 * <p>表名按模型区分（{@code knowledge_chunk_bge_m3}、{@code knowledge_chunk_nomic_embed_text}），换模型各用各的表。手动把表名指到了
 * 一张别的模型建的表时，建表前会读出已有表的维度，对不上就报 INDEX_MODEL_MISMATCH。
 *
 * <p>只读写自己这一张表（和它的索引），不碰库里的其他表。
 */
public class PgVectorKnowledgeStore implements KnowledgeStore {

    /** 表名只允许小写字母、数字、下划线，以字母或下划线开头，最长 57 个字符（加上 PgVectorStore 自动拼的 {@code _index} 不超过 63）。 */
    static final Pattern SAFE_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,56}");

    private final JdbcTemplate jdbc;
    private final String schema;
    private final String table;
    private final KnowledgeEmbedding embedding;
    private final IntFunction<PgVectorStore> storeFactory;
    private volatile PgVectorStore store;

    /**
     * 创建。
     *
     * @param jdbc 连到知识库数据库的 JdbcTemplate
     * @param schema schema 名
     * @param table 表名
     * @param embedding 当前向量模型
     * @param storeFactory 按维度创建（并初始化）PgVectorStore 的函数，见 {@link PgVectorConfig#createStore}
     * @throws IllegalArgumentException schema 或表名含有不安全的字符（它们会被拼进 SQL）
     */
    public PgVectorKnowledgeStore(
            JdbcTemplate jdbc,
            String schema,
            String table,
            KnowledgeEmbedding embedding,
            IntFunction<PgVectorStore> storeFactory) {
        if (!SAFE_NAME.matcher(schema).matches() || !SAFE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException("pgvector 的 schema / 表名只能用小写字母、数字和下划线：" + schema + "." + table);
        }
        this.jdbc = jdbc;
        this.schema = schema;
        this.table = table;
        this.embedding = embedding;
        this.storeFactory = storeFactory;
    }

    @Override
    public String type() {
        return "pgvector";
    }

    @Override
    public String location() {
        return schema + "." + table;
    }

    /** 表名（测试与日志用）。 */
    public String table() {
        return table;
    }

    @Override
    public synchronized VectorStore vectorStore() {
        if (store == null) {
            int dimensions = embedding.dimensions();
            Integer existing = existingDimensions();
            if (existing != null && existing != dimensions) {
                throw new KnowledgeException(
                        KnowledgeFailure.INDEX_MODEL_MISMATCH,
                        "表 " + location() + " 的向量列是 " + existing + " 维，当前向量模型 " + embedding.modelName() + " 输出 "
                                + dimensions + " 维：不同模型的向量不能存进同一张表，请换表名或重建"
                                + "（POST /api/knowledge/ingest?rebuild=true 会删表重建）。",
                        null);
            }
            try {
                store = storeFactory.apply(dimensions);
            } catch (RuntimeException e) {
                throw KnowledgeException.classify(e, embedding.modelName(), location());
            }
        }
        return store;
    }

    /**
     * 按 source 汇总：metadata 列是 json 类型，{@code ->>} 取出字段的文字值。表还没建时返回空列表，不触发建表（也就不用探测维度）。
     */
    @Override
    public List<StoredDocument> documents() {
        try {
            if (!tableExists()) {
                return List.of();
            }
            List<Map<String, Object>> metadata = jdbc.query(
                    "SELECT metadata->>'source' AS source, metadata->>'title' AS title,"
                            + " metadata->>'docVersion' AS doc_version, metadata->>'docHash' AS doc_hash,"
                            + " metadata->>'embeddingModel' AS embedding_model FROM " + location(),
                    (rs, i) -> Map.of(
                            ChunkMetadata.SOURCE, nullToEmpty(rs.getString("source")),
                            ChunkMetadata.TITLE, nullToEmpty(rs.getString("title")),
                            ChunkMetadata.DOC_VERSION, nullToEmpty(rs.getString("doc_version")),
                            ChunkMetadata.DOC_HASH, nullToEmpty(rs.getString("doc_hash")),
                            ChunkMetadata.EMBEDDING_MODEL, nullToEmpty(rs.getString("embedding_model"))));
            return StoredDocument.summarize(metadata);
        } catch (DataAccessException e) {
            throw KnowledgeException.classify(e, embedding.modelName(), location());
        }
    }

    /** 每次写入都已经提交到数据库，不需要额外持久化。 */
    @Override
    public void persist() {
        // 无事可做
    }

    /** 删表（索引随表一起删除），下次 {@link #vectorStore()} 时按当前模型的维度重新建。 */
    @Override
    public synchronized void reset() {
        try {
            jdbc.execute("DROP TABLE IF EXISTS " + location());
            store = null;
        } catch (DataAccessException e) {
            throw KnowledgeException.classify(e, embedding.modelName(), location());
        }
    }

    /** {@code to_regclass} 在表不存在时返回 NULL，不会报错。 */
    private boolean tableExists() {
        return jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, location());
    }

    /**
     * 已有表的向量维度：pgvector 把 {@code vector(1024)} 的 1024 存在列的类型修饰符 atttypmod 里。
     *
     * @return 维度；表不存在时为 null
     */
    Integer existingDimensions() {
        try {
            if (!tableExists()) {
                return null;
            }
            List<Integer> dims = jdbc.queryForList(
                    "SELECT atttypmod FROM pg_attribute WHERE attrelid = to_regclass(?) AND attname = 'embedding'",
                    Integer.class,
                    location());
            return dims.isEmpty() || dims.getFirst() <= 0 ? null : dims.getFirst();
        } catch (DataAccessException e) {
            throw KnowledgeException.classify(e, embedding.modelName(), location());
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
