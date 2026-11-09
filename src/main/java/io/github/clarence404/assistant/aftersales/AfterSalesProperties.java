package io.github.clarence404.assistant.aftersales;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 售后申请单存在哪，前缀 {@code assistant.after-sales}（第 7 章引入）。
 *
 * <p>所在层：配置。由 {@code @ConfigurationPropertiesScan} 绑定，注入 {@link AfterSalesConfig}。
 *
 * @param store {@code memory}（默认，JVM 内存，重启就没了）或 {@code jdbc}（PostgreSQL 的 {@code after_sales_application} 表）
 * @param jdbc {@code store=jdbc} 时的连接；application.yml 里默认沿用第 6 章记忆的连接（也就是第 4 章 pgvector 的那个库）
 */
@ConfigurationProperties("assistant.after-sales")
public record AfterSalesProperties(
        @DefaultValue("memory") StoreType store,
        @DefaultValue Jdbc jdbc) {

    /** 存储类型。 */
    public enum StoreType {
        /** 内存。 */
        MEMORY,
        /** PostgreSQL。 */
        JDBC
    }

    /**
     * 数据库连接。
     *
     * @param url JDBC 地址
     * @param username 用户
     * @param password 密码；真实密码写进不入库的 config/application-local.yml 或环境变量
     * @param initializeSchema 第一次用到时是否建表（IF NOT EXISTS）
     * @param maxPoolSize 连接池最多几个连接
     */
    public record Jdbc(
            @DefaultValue("jdbc:postgresql://localhost:5432/mall_assistant")
            String url,

            @DefaultValue("mall") String username,
            @DefaultValue("") String password,
            @DefaultValue("true") boolean initializeSchema,
            @DefaultValue("2") int maxPoolSize) {}
}
