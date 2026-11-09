package io.github.clarence404.assistant.aftersales;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 售后申请单存进 PostgreSQL 的 {@code after_sales_application} 表（{@code assistant.after-sales.store=jdbc}，第 7 章引入）。
 *
 * <p>所在层：售后申请的存储。由 {@link AfterSalesConfig} 创建。和第 6 章 {@code LazyJdbcChatMemoryRepository} 同一个套路：连接池启动时不连数据库，
 * 第一次读写时才建表（{@code CREATE TABLE IF NOT EXISTS}）——数据库没启动也不影响应用启动，只是到了提交售后申请那一步执行失败（结果里写明）。
 *
 * <p>表结构：
 *
 * <pre>
 * after_sales_application(
 *   id              VARCHAR(40) PRIMARY KEY,   -- 申请单号
 *   owner_key       VARCHAR(64) NOT NULL,      -- 谁的（user:10001）
 *   order_no        VARCHAR(32) NOT NULL,
 *   type            VARCHAR(20) NOT NULL,      -- REFUND_ONLY / RETURN_REFUND
 *   reason          VARCHAR(400) NOT NULL,
 *   status          VARCHAR(20) NOT NULL,      -- PENDING_REVIEW
 *   confirmation_id VARCHAR(36) NOT NULL UNIQUE,  -- 同一个确认只能生成一张：幂等的最后一道防线
 *   created_at      TIMESTAMPTZ NOT NULL)
 * </pre>
 *
 * 用的是 Spring 的 {@link JdbcTemplate}（普通 SQL），不引入 JPA：一张表、三条 SQL，够用也看得清。
 */
public final class JdbcAfterSalesStore implements AfterSalesStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JdbcAfterSalesStore.class);

    /** 建表与索引：都是 IF NOT EXISTS，重复执行无害。 */
    static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS after_sales_application (
                id VARCHAR(40) PRIMARY KEY,
                owner_key VARCHAR(64) NOT NULL,
                order_no VARCHAR(32) NOT NULL,
                type VARCHAR(20) NOT NULL,
                reason VARCHAR(400) NOT NULL,
                status VARCHAR(20) NOT NULL,
                confirmation_id VARCHAR(36) NOT NULL UNIQUE,
                created_at TIMESTAMPTZ NOT NULL
            )""", "CREATE INDEX IF NOT EXISTS idx_after_sales_owner ON after_sales_application (owner_key, created_at)");

    private static final String COLUMNS = "id, owner_key, order_no, type, reason, status, confirmation_id, created_at";

    private final HikariDataSource dataSource;
    private final boolean initializeSchema;
    private volatile JdbcTemplate jdbc;

    /**
     * 创建：只建连接池（不连数据库）。
     *
     * @param properties 连接配置
     */
    public JdbcAfterSalesStore(AfterSalesProperties.Jdbc properties) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("after-sales-jdbc");
        config.setJdbcUrl(properties.url());
        config.setUsername(properties.username());
        config.setPassword(properties.password());
        config.setMaximumPoolSize(properties.maxPoolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(3_000);
        // -1：启动时不试连接，数据库没开也能起来（同第 6 章）
        config.setInitializationFailTimeout(-1);
        this.dataSource = new HikariDataSource(config);
        this.initializeSchema = properties.initializeSchema();
    }

    /**
     * 第一次用到时（按配置）建表，再创建 {@link JdbcTemplate}（ch07-7）。
     *
     * <p>要求（测试按这个写）：双重检查（{@code volatile} 字段 + {@code synchronized}），并发的第一次只初始化一次；{@code initialize-schema=true}
     * 时逐条执行 {@link #SCHEMA}；任何一步失败都直接抛出、不缓存失败，下次再试。
     *
     * @return 可以用的 JdbcTemplate
     */
    JdbcTemplate jdbc() {
        JdbcTemplate current = jdbc;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (jdbc == null) {
                JdbcTemplate template = new JdbcTemplate(dataSource);
                if (initializeSchema) {
                    SCHEMA.forEach(template::execute);
                }
                jdbc = template;
                log.info("售后申请单库已就绪：PostgreSQL 表 after_sales_application");
            }
            return jdbc;
        }
    }

    /**
     * 存一张申请单（ch07-7）。
     *
     * <p>要求：{@code INSERT ... ON CONFLICT (confirmation_id) DO NOTHING}（PostgreSQL 的「有就跳过」，并发的两次插入也只有一次生效），
     * 然后按 confirmation_id 查回来返回（可能是之前那张）。时间存成 {@link Timestamp}。
     */
    @Override
    public AfterSalesApplication create(AfterSalesApplication a) {
        JdbcTemplate template = jdbc();
        template.update(
                "INSERT INTO after_sales_application (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (confirmation_id) DO NOTHING",
                a.id(),
                a.ownerKey(),
                a.orderNo(),
                a.type().name(),
                a.reason(),
                a.status().name(),
                a.confirmationId(),
                Timestamp.from(a.createdAt()));
        return template.query(
                        "SELECT " + COLUMNS + " FROM after_sales_application WHERE confirmation_id = ?",
                        JdbcAfterSalesStore::map,
                        a.confirmationId())
                .getFirst();
    }

    /**
     * 某个用户的全部申请单（ch07-7）：按 created_at 从新到旧。
     */
    @Override
    public List<AfterSalesApplication> findByOwner(String ownerKey) {
        return jdbc().query(
                        "SELECT " + COLUMNS
                                + " FROM after_sales_application WHERE owner_key = ? ORDER BY created_at DESC",
                        JdbcAfterSalesStore::map,
                        ownerKey);
    }

    @Override
    public Optional<AfterSalesApplication> findOpen(String ownerKey, String orderNo) {
        return jdbc()
                .query(
                        "SELECT " + COLUMNS + " FROM after_sales_application WHERE owner_key = ? AND order_no = ?"
                                + " AND status = ? ORDER BY created_at DESC",
                        JdbcAfterSalesStore::map,
                        ownerKey,
                        orderNo,
                        AfterSalesApplication.Status.PENDING_REVIEW.name())
                .stream()
                .findFirst();
    }

    @Override
    public String type() {
        return "jdbc";
    }

    /** 一行 → 申请单。 */
    private static AfterSalesApplication map(ResultSet rs, int row) throws SQLException {
        return new AfterSalesApplication(
                rs.getString("id"),
                rs.getString("owner_key"),
                rs.getString("order_no"),
                AfterSalesApplication.Type.valueOf(rs.getString("type")),
                rs.getString("reason"),
                AfterSalesApplication.Status.valueOf(rs.getString("status")),
                rs.getString("confirmation_id"),
                rs.getTimestamp("created_at").toInstant());
    }

    /** 应用关闭时关掉连接池。 */
    @Override
    public void close() {
        dataSource.close();
    }
}
