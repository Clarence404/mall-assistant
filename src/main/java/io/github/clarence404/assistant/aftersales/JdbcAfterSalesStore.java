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
import org.springframework.dao.DataAccessException;
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

    /**
     * 部分唯一索引（审查修复）：同一个用户的同一笔订单，状态是「待人工审核」的申请最多一张。
     *
     * <p>「部分」（{@code WHERE status = ...}）的意思是只有待审核的行参与唯一性：以后审核通过 / 驳回的申请状态变了，就不再占着位置，同一笔订单
     * 可以再提一次。普通的唯一约束做不到这一点。有了它，两张确认单并发确认时由数据库保证只有一次插入成功。
     */
    static final String UNIQUE_OPEN_INDEX = "CREATE UNIQUE INDEX IF NOT EXISTS uq_after_sales_open"
            + " ON after_sales_application (owner_key, order_no) WHERE status = 'PENDING_REVIEW'";

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
                    createUniqueOpenIndex(template);
                }
                jdbc = template;
                log.info("售后申请单库已就绪：PostgreSQL 表 after_sales_application");
            }
            return jdbc;
        }
    }

    /**
     * 建部分唯一索引。表里已经有重复的待审核申请（这个索引是后来加的，旧数据可能有）时建不成：只打一行警告、照常工作——这时「一笔订单一张」
     * 退回到调用方先查后存的老办法，等人工把重复的清掉、重启后索引就建上了。不能因为它让整个售后申请功能起不来。
     *
     * @param template 连接
     */
    private static void createUniqueOpenIndex(JdbcTemplate template) {
        try {
            template.execute(UNIQUE_OPEN_INDEX);
        } catch (DataAccessException e) {
            log.warn(
                    "售后申请单的唯一索引 uq_after_sales_open 没建成（表里可能已经有同一笔订单的多张待审核申请，清理后重启即可）：{}",
                    e.getMostSpecificCause().getMessage());
        }
    }

    /**
     * 存一张申请单（ch07-7）。
     *
     * <p>要求：{@code INSERT ... ON CONFLICT DO NOTHING}（PostgreSQL 的「有就跳过」，并发的两次插入也只有一次生效），然后查回来返回
     * （可能是之前那张）。时间存成 {@link Timestamp}。
     *
     * <p>审查修复：{@code ON CONFLICT} 后面不再写 {@code (confirmation_id)}——不指定哪个约束，就是「撞上任何一个唯一约束都跳过」，这样
     * 「同一个确认 ID」和「这笔订单已有待审核的申请」（{@link #UNIQUE_OPEN_INDEX}）两种情况都不会报错。查回来时先按确认 ID 找；找不到说明
     * 是被第二种情况挡下的，返回那张已有的待审核申请。
     */
    @Override
    public AfterSalesApplication create(AfterSalesApplication a) {
        JdbcTemplate template = jdbc();
        template.update(
                "INSERT INTO after_sales_application (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT DO NOTHING",
                a.id(),
                a.ownerKey(),
                a.orderNo(),
                a.type().name(),
                a.reason(),
                a.status().name(),
                a.confirmationId(),
                Timestamp.from(a.createdAt()));
        List<AfterSalesApplication> mine = template.query(
                "SELECT " + COLUMNS + " FROM after_sales_application WHERE confirmation_id = ?",
                JdbcAfterSalesStore::map,
                a.confirmationId());
        if (!mine.isEmpty()) {
            return mine.getFirst();
        }
        return findOpen(a.ownerKey(), a.orderNo())
                .orElseThrow(() -> new IllegalStateException("售后申请单没有存进去，也找不到挡住它的那一张：" + a.confirmationId()));
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
