package io.github.clarence404.assistant.state;

import io.github.clarence404.assistant.ops.ChatMetrics;
import io.micrometer.core.instrument.Gauge;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 共享状态两种存储都要的东西：出错时的策略（{@link StateFailures}），和一个说明「现在用的是哪种存储」的指标（第 10 章引入）。
 *
 * <p>所在层：共享状态的装配。具体实现在 {@link MemoryStateConfig} 与 {@link RedisStateConfig} 里，按 {@code assistant.state.store} 二选一。
 */
@Configuration(proxyBeanMethods = false)
public class StateConfig {

    /**
     * 出错时的策略。注册一个仪表 {@code assistant_state_store_info{store="redis"} 1}：Grafana 上一眼看出每个实例用的是哪种存储
     * （一个实例忘了配 Redis，它的限流就是自己算的，指标上看得出来）。
     *
     * @param properties {@code assistant.state.*}
     * @param metrics 指标
     * @return 策略
     */
    @Bean
    public StateFailures stateFailures(StateProperties properties, ChatMetrics metrics) {
        Gauge.builder("assistant.state.store.info", () -> 1)
                .description("共享状态存在哪（标签 store）")
                .tag("store", properties.store().name().toLowerCase(Locale.ROOT))
                .register(metrics.registry());
        return new StateFailures(properties.onRedisError(), properties.redis().cooldown(), metrics.registry());
    }
}
