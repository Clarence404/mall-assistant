package io.github.clarence404.assistant.aftersales;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 售后申请单存储的装配：内存（默认）或 PostgreSQL，按 {@code assistant.after-sales.store} 选（第 7 章引入）。
 *
 * <p>所在层：配置。产出的 {@link AfterSalesStore} 注入 {@code agent.WriteActionGuard}、{@code agent.ConfirmationService} 与 {@link AfterSalesController}。
 */
@Configuration(proxyBeanMethods = false)
public class AfterSalesConfig {

    /**
     * 内存存储（默认）。
     *
     * @return 内存存储
     */
    @Bean
    @ConditionalOnProperty(name = "assistant.after-sales.store", havingValue = "memory", matchIfMissing = true)
    public AfterSalesStore inMemoryAfterSalesStore() {
        return new InMemoryAfterSalesStore();
    }

    /**
     * PostgreSQL 存储：连接池和建表都推迟到第一次用到时。
     *
     * @param properties {@code assistant.after-sales.*}
     * @return JDBC 存储；应用关闭时关掉连接池
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "assistant.after-sales.store", havingValue = "jdbc")
    public JdbcAfterSalesStore jdbcAfterSalesStore(AfterSalesProperties properties) {
        return new JdbcAfterSalesStore(properties.jdbc());
    }
}
