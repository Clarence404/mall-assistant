package io.github.clarence404.assistant.aftersales;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试：售后申请单存进真实的 PostgreSQL（第 7 章 ch07-7 的验收测试）。
 *
 * <p>测什么：{@link JdbcAfterSalesStore} 第一次用到时建表；存进去的读得回来（时间、类型、状态都对）；同一个确认 ID 存两次（包括并发）只有一张；
 * 按用户查只看得到自己的、从新到旧；待审核的申请按订单查得到；再建一个实例（建表再执行一次）无害。
 *
 * <p>数据库从哪来：同第 6 章 {@code JdbcChatMemoryIT}——有 Docker 时 Testcontainers 起 {@code pgvector/pgvector:0.8.1-pg17}（CI 上就是这样）；
 * 没有 Docker 时设置环境变量 {@code PGVECTOR_IT_URL} / {@code PGVECTOR_IT_USERNAME} / {@code PGVECTOR_IT_PASSWORD} 指向一个已有的 PostgreSQL
 * （只读写 {@code after_sales_application} 表里本测试随机生成的用户的数据，结束时删掉）；两者都没有就跳过。
 */
class JdbcAfterSalesStoreIT {

    static final String IMAGE = "pgvector/pgvector:0.8.1-pg17";

    static PostgreSQLContainer container;
    static AfterSalesProperties.Jdbc jdbc;
    static JdbcAfterSalesStore store;

    /** 本测试用的用户（随机，连共享数据库时不和别的数据混在一起）。 */
    static final String OWNER = "user:it-" + UUID.randomUUID();

    static final String OTHER_OWNER = "user:it-" + UUID.randomUUID();

    @BeforeAll
    static void startDatabase() {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            container = new PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("mall_assistant")
                    .withUsername("mall")
                    .withPassword("mall");
            container.start();
            jdbc = new AfterSalesProperties.Jdbc(container.getJdbcUrl(), "mall", "mall", true, 2);
        } else {
            String url = System.getenv("PGVECTOR_IT_URL");
            Assumptions.assumeTrue(url != null && !url.isBlank(), "没有 Docker，也没有设置 PGVECTOR_IT_URL，跳过售后申请单的集成测试");
            jdbc = new AfterSalesProperties.Jdbc(
                    url,
                    System.getenv().getOrDefault("PGVECTOR_IT_USERNAME", "mall"),
                    System.getenv().getOrDefault("PGVECTOR_IT_PASSWORD", ""),
                    true,
                    2);
        }
        store = new JdbcAfterSalesStore(jdbc);
    }

    @AfterAll
    static void cleanUp() {
        if (store != null) {
            raw().update("DELETE FROM after_sales_application WHERE owner_key IN (?, ?)", OWNER, OTHER_OWNER);
            store.close();
        }
        if (container != null) {
            container.stop();
        }
    }

    private static JdbcTemplate raw() {
        return new JdbcTemplate(new DriverManagerDataSource(jdbc.url(), jdbc.username(), jdbc.password()));
    }

    private static AfterSalesApplication application(String owner, String orderNo, Instant at) {
        return new AfterSalesApplication(
                "AS-IT-" + UUID.randomUUID().toString().substring(0, 8),
                owner,
                orderNo,
                AfterSalesApplication.Type.RETURN_REFUND,
                "东西坏了",
                AfterSalesApplication.Status.PENDING_REVIEW,
                UUID.randomUUID().toString(),
                at.truncatedTo(ChronoUnit.MILLIS));
    }

    /** 存进去读得回来：字段一个不差；表是第一次用到时自动建的。 */
    @Test
    void savedApplicationReadsBack() {
        AfterSalesApplication saved = store.create(application(OWNER, "21182516707889029140785", Instant.now()));

        assertThat(store.findByOwner(OWNER)).contains(saved);
        assertThat(store.findOpen(OWNER, "21182516707889029140785")).isPresent();
        assertThat(store.type()).isEqualTo("jdbc");
    }

    /** 幂等：同一个确认 ID 存两次（包括两个线程同时存），表里只有一张，返回的都是第一张。 */
    @Test
    void sameConfirmationIsStoredOnce() throws Exception {
        AfterSalesApplication first = application(OWNER, "21182516707889029150785", Instant.now());
        AfterSalesApplication retry = new AfterSalesApplication(
                "AS-IT-RETRY-" + UUID.randomUUID().toString().substring(0, 4),
                first.ownerKey(),
                first.orderNo(),
                first.type(),
                first.reason(),
                first.status(),
                first.confirmationId(),
                first.createdAt());

        CompletableFuture<AfterSalesApplication> a = CompletableFuture.supplyAsync(() -> store.create(first));
        CompletableFuture<AfterSalesApplication> b = CompletableFuture.supplyAsync(() -> store.create(retry));

        assertThat(a.get()).isEqualTo(b.get());
        assertThat(raw().queryForObject(
                                "SELECT count(*) FROM after_sales_application WHERE confirmation_id = ?",
                                Integer.class,
                                first.confirmationId()))
                .isEqualTo(1);
    }

    /**
     * 审查修复：同一笔订单、两张不同的确认单同时来存（各自都以为「还没有待审核的申请」）——部分唯一索引保证表里只有一张，
     * 两边拿到的都是它。修复前（只有 confirmation_id 唯一）会存出两张。
     */
    @Test
    void sameOrderGetsOnlyOneOpenApplicationEvenConcurrently() throws Exception {
        String orderNo = "21182516707889029180785";
        AfterSalesApplication first = application(OWNER, orderNo, Instant.now());
        AfterSalesApplication second = application(OWNER, orderNo, Instant.now());

        CompletableFuture<AfterSalesApplication> a = CompletableFuture.supplyAsync(() -> store.create(first));
        CompletableFuture<AfterSalesApplication> b = CompletableFuture.supplyAsync(() -> store.create(second));

        assertThat(a.get()).isEqualTo(b.get());
        assertThat(raw().queryForObject(
                                "SELECT count(*) FROM after_sales_application WHERE owner_key = ? AND order_no = ?",
                                Integer.class,
                                OWNER,
                                orderNo))
                .isEqualTo(1);
        assertThat(raw().queryForObject(
                                "SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_after_sales_open'",
                                Integer.class))
                .as("部分唯一索引建上了")
                .isEqualTo(1);
    }

    /** 按用户查：只看得到自己的，从新到旧；别人的订单查不到待审核的申请。 */
    @Test
    void applicationsAreScopedToOwnerAndSorted() {
        Instant now = Instant.now();
        AfterSalesApplication older =
                store.create(application(OTHER_OWNER, "21182516707889029160785", now.minusSeconds(60)));
        AfterSalesApplication newer = store.create(application(OTHER_OWNER, "21182516707889029170785", now));

        List<AfterSalesApplication> mine = store.findByOwner(OTHER_OWNER);

        assertThat(mine).containsExactly(newer, older);
        assertThat(store.findOpen(OWNER, "21182516707889029160785")).isEmpty();
    }

    /** 再建一个实例（建表脚本再执行一次）无害，读得到之前的数据。 */
    @Test
    void secondInstanceIsHarmless() {
        AfterSalesApplication saved = store.create(application(OWNER, "21182516707889029180785", Instant.now()));
        try (JdbcAfterSalesStore second = new JdbcAfterSalesStore(jdbc)) {
            assertThat(second.findByOwner(OWNER)).contains(saved);
        }
    }
}
