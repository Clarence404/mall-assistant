package io.github.clarence404.assistant.ops;

import io.github.clarence404.assistant.guard.CircuitBreakerAdvisor;
import io.github.clarence404.assistant.guard.GuardProperties;
import io.github.clarence404.assistant.guard.InjectionFilter;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 第 9 章的装配：把熔断和 Token 记账挂到共享 ChatClient 上，按配置创建提示注入过滤器。
 *
 * <p>所在层：配置。Spring AI 自动配置的 {@code ChatClient.Builder} 在交给 {@code chat.ChatClientConfig} 之前，会先经过容器里所有的
 * {@link ChatClientCustomizer}；这里加的两个默认 advisor 因此出现在<b>每一次</b>模型调用上（意图识别、追问改写、回答、RAG、Agent、摘要），
 * 业务代码不用改。前几章的测试手工 {@code ChatClient.builder(假模型)} 时没有它们，行为和以前一样。
 */
@Configuration(proxyBeanMethods = false)
public class OpsConfig {

    /**
     * 给共享 ChatClient 加上默认 advisor：外层熔断、内层记账（顺序由各自的 {@code getOrder()} 决定，和这里写的先后无关）。
     *
     * @param circuitBreaker 熔断
     * @param usage Token 记账
     * @return 定制器
     */
    @Bean
    public ChatClientCustomizer opsAdvisors(CircuitBreakerAdvisor circuitBreaker, UsageAdvisor usage) {
        return builder -> builder.defaultAdvisors(circuitBreaker, usage);
    }

    /**
     * 提示注入过滤器：内置规则 + {@code assistant.guard.injection.extra-patterns}。
     *
     * @param properties {@code assistant.guard.*}
     * @return 过滤器
     */
    @Bean
    public InjectionFilter injectionFilter(GuardProperties properties) {
        return InjectionFilter.of(properties.injection());
    }
}
