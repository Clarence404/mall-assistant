package io.github.clarence404.assistant.knowledge;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

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
 *
 * <p>文档汇总的缓存（审查修复）：{@link #documents()} 每次 RAG 检索要用两次（核对向量模型、判断关键词索引要不要重建）。原来每次都把
 * 整张表每一块的元数据拉回应用里再分组，等于每个问题多两次全表扫描。现在分组放在数据库里做（{@code GROUP BY}，一篇文档一行），
 * 结果缓存起来，三种情况才重新查：入库写完（{@link #invalidate()}）、删表重建（{@link #reset()}）、超过
 * {@link #DOCUMENTS_CACHE_TTL}。最后一种是给多实例兜底的：别的实例入了库，这个实例收不到通知，最多晚这么久看到（这期间关键词索引是旧的，
 * 向量检索不受影响——它每次都查数据库）。
 */
public class PgVectorKnowledgeStore implements KnowledgeStore {

    /** 表名只允许小写字母、数字、下划线，以字母或下划线开头，最长 57 个字符（加上 PgVectorStore 自动拼的 {@code _index} 不超过 63）。 */
    static final Pattern SAFE_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,56}");

    /** 文档汇总缓存多久：本实例入库会立刻失效，这个时长只影响「别的实例入了库，这里多久之后看到」。 */
    static final Duration DOCUMENTS_CACHE_TTL = Duration.ofSeconds(30);

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> METADATA = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final String schema;
    private final String table;
    private final KnowledgeEmbedding embedding;
    private final IntFunction<PgVectorStore> storeFactory;
    private final long cacheTtlNanos;
    private final LongSupplier nanoTime;
    private volatile PgVectorStore store;

    /** 缓存的文档汇总；null = 没有缓存（还没查过，或者刚入过库）。整个对象一起换，读的一方不用加锁。 */
    private volatile CachedDocuments cached;

    /** 一次汇总查询的结果和它的时间（单调时钟，纳秒）。 */
    private record CachedDocuments(List<StoredDocument> documents, long loadedAtNanos) {}

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
        this(jdbc, schema, table, embedding, storeFactory, DOCUMENTS_CACHE_TTL, System::nanoTime);
    }

    /**
     * 创建，指定文档汇总缓存的有效期和时钟（测试用假时钟验证过期）。
     *
     * @param jdbc 连到知识库数据库的 JdbcTemplate
     * @param schema schema 名
     * @param table 表名
     * @param embedding 当前向量模型
     * @param storeFactory 按维度创建（并初始化）PgVectorStore 的函数
     * @param documentsCacheTtl 文档汇总缓存多久
     * @param nanoTime 单调时钟（纳秒）
     * @throws IllegalArgumentException schema 或表名含有不安全的字符（它们会被拼进 SQL）
     */
    PgVectorKnowledgeStore(
            JdbcTemplate jdbc,
            String schema,
            String table,
            KnowledgeEmbedding embedding,
            IntFunction<PgVectorStore> storeFactory,
            Duration documentsCacheTtl,
            LongSupplier nanoTime) {
        if (!SAFE_NAME.matcher(schema).matches() || !SAFE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException("pgvector 的 schema / 表名只能用小写字母、数字和下划线：" + schema + "." + table);
        }
        this.jdbc = jdbc;
        this.schema = schema;
        this.table = table;
        this.embedding = embedding;
        this.storeFactory = storeFactory;
        this.cacheTtlNanos = documentsCacheTtl.toNanos();
        this.nanoTime = nanoTime;
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
     * 按 source 汇总，带缓存（见类说明）：缓存还新就直接返回，否则查一次数据库。表还没建时返回空列表，不触发建表（也就不用探测维度）。
     *
     * <p>两个线程同时发现缓存没了会各查一次，结果一样，后写的覆盖先写的，不值得为它加锁。
     */
    @Override
    public List<StoredDocument> documents() {
        CachedDocuments current = cached;
        long now = nanoTime.getAsLong();
        if (current != null && now - current.loadedAtNanos() < cacheTtlNanos) {
            return current.documents();
        }
        List<StoredDocument> loaded = loadDocuments();
        cached = new CachedDocuments(loaded, now);
        return loaded;
    }

    /**
     * 真正查数据库：在库里按 source 分组，一篇文档一行（metadata 列是 json 类型，{@code ->>} 取出字段的文字值）。
     *
     * <p>同一篇文档的每一块，标题、版本、哈希、模型名都一样（入库时由 {@link ChunkDocumentFactory} 统一写的），取 {@code min} 只是为了
     * 满足 {@code GROUP BY} 的语法。排序放在 Java 里做：数据库的排序规则（collation）不一定和 {@link String#compareTo} 一致，而
     * 关键词索引的签名、增量入库的比较都按这个顺序。
     */
    private List<StoredDocument> loadDocuments() {
        try {
            if (!tableExists()) {
                return List.of();
            }
            List<StoredDocument> documents = jdbc.query(
                    "SELECT metadata->>'source' AS source, min(metadata->>'title') AS title,"
                            + " min(metadata->>'docVersion') AS doc_version, min(metadata->>'docHash') AS doc_hash,"
                            + " min(metadata->>'embeddingModel') AS embedding_model, count(*) AS chunk_count FROM "
                            + location() + " GROUP BY metadata->>'source'",
                    (rs, i) -> new StoredDocument(
                            nullToEmpty(rs.getString("source")),
                            nullToEmpty(rs.getString("title")),
                            nullToEmpty(rs.getString("doc_version")),
                            nullToEmpty(rs.getString("doc_hash")),
                            nullToEmpty(rs.getString("embedding_model")),
                            rs.getInt("chunk_count")));
            return documents.stream()
                    .sorted(Comparator.comparing(StoredDocument::source))
                    .toList();
        } catch (DataAccessException e) {
            throw KnowledgeException.classify(e, embedding.modelName(), location());
        }
    }

    /** 入库写完后丢掉缓存的文档汇总，下一次 {@link #documents()} 重新查。 */
    @Override
    public void invalidate() {
        cached = null;
    }

    /**
     * 全部块（第 5 章关键词检索用）：只查 id、content、metadata 三列，不取向量列（一行 1024 个浮点数，用不上又占内存）。
     * metadata 是 json 列，转成文字后用 Jackson 解析成 Map。表还没建时返回空列表。
     */
    @Override
    public List<Document> chunks() {
        try {
            if (!tableExists()) {
                return List.of();
            }
            List<Document> chunks = jdbc.query(
                    "SELECT id::text AS id, content, metadata::text AS metadata FROM " + location(),
                    (rs, i) -> Document.builder()
                            .id(rs.getString("id"))
                            .text(nullToEmpty(rs.getString("content")))
                            .metadata(JSON.readValue(rs.getString("metadata"), METADATA))
                            .build());
            return chunks.stream().sorted(MemoryKnowledgeStore.CHUNK_ORDER).toList();
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
            cached = null;
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
