package io.github.clarence404.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * pgvector 向量库的「文档汇总」缓存（审查修复）：不连数据库，用 Mockito 假的 JdbcTemplate 数一数发了几条 SQL。
 *
 * <p>为什么要测这个：每次 RAG 检索都要用 {@link PgVectorKnowledgeStore#documents()} 两次（核对向量模型、判断关键词索引要不要重建），
 * 修复前每次都把整张表的元数据拉回应用里分组——知识库越大，每个问题白白多出两次全表扫描。修复后：数据库里 {@code GROUP BY}，结果缓存，
 * 入库（{@link KnowledgeStore#invalidate()}）、删表重建（{@link KnowledgeStore#reset()}）之后才重新查；另有一个兜底的有效期，
 * 给「别的实例入了库」的情况用。真实 SQL 能不能跑由 {@code PgVectorStoreIT}（Testcontainers）验证。
 */
class PgVectorKnowledgeStoreTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AtomicLong nanos = new AtomicLong();
    private PgVectorKnowledgeStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        KnowledgeEmbedding embedding = new KnowledgeEmbedding(
                new FakeEmbeddingModel(8), KnowledgeFixtures.properties("classpath:knowledge/*.md", "bge-m3"));
        store = new PgVectorKnowledgeStore(
                jdbc,
                "public",
                "knowledge_chunk_test",
                embedding,
                dimensions -> {
                    throw new AssertionError("这个测试不应该建真正的 PgVectorStore");
                },
                Duration.ofSeconds(30),
                nanos::get);
        // 表存在；汇总查询返回两篇文档
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
                .thenReturn(true);
        when(jdbc.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of(
                        new StoredDocument("returns.md", "退换货", "v1", "h1", "bge-m3", 7),
                        new StoredDocument("shipping.md", "物流", "v1", "h2", "bge-m3", 5)));
    }

    /** 连着问两次：只查一次数据库（修复前这里是 2 次，而且每次都是全表）。 */
    @Test
    @SuppressWarnings("unchecked")
    void documentsAreCachedBetweenSearches() {
        List<StoredDocument> first = store.documents();
        List<StoredDocument> second = store.documents();

        assertThat(first).hasSize(2);
        assertThat(second).isSameAs(first);
        verify(jdbc, times(1)).query(anyString(), any(RowMapper.class));
    }

    /** 汇总放在数据库里做：一篇文档一行，而不是把每一块的元数据都拉回来。 */
    @Test
    @SuppressWarnings("unchecked")
    void documentsAreGroupedInTheDatabase() {
        store.documents();

        verify(jdbc).query(contains("GROUP BY"), any(RowMapper.class));
    }

    /** 入库之后（KnowledgeIngestionService 调 invalidate）下一次要重新查，否则关键词索引永远是旧的。 */
    @Test
    @SuppressWarnings("unchecked")
    void invalidateForcesReload() {
        store.documents();
        store.invalidate();
        store.documents();

        verify(jdbc, times(2)).query(anyString(), any(RowMapper.class));
    }

    /** 删表重建同样让缓存失效。 */
    @Test
    @SuppressWarnings("unchecked")
    void resetForcesReload() {
        store.documents();
        store.reset();
        store.documents();

        verify(jdbc, times(2)).query(anyString(), any(RowMapper.class));
    }

    /** 兜底的有效期：多实例时别的实例入了库，这个实例收不到通知，过了有效期自己重新查一次。 */
    @Test
    @SuppressWarnings("unchecked")
    void cacheExpiresSoOtherInstancesIngestIsEventuallySeen() {
        store.documents();
        nanos.addAndGet(Duration.ofSeconds(29).toNanos());
        store.documents();
        verify(jdbc, times(1)).query(anyString(), any(RowMapper.class));

        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        store.documents();
        verify(jdbc, times(2)).query(anyString(), any(RowMapper.class));
    }
}
