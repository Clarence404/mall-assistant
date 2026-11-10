package io.github.clarence404.assistant.guard;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 上线保护的配置，前缀 {@code assistant.guard}（第 9 章引入）。
 *
 * <p>所在层：上线保护的配置。{@link ChatGuard}（限流、并发）、{@link ModelCircuitBreaker}（熔断）、{@link InjectionFilter}（提示注入过滤）
 * 和 {@code ChatService}（降级）读它。默认值见 {@code application.yml}；测试里统一调宽（{@code src/test/resources/config/application.yml}），
 * 免得前八章共用一个 Spring 上下文的接口测试被限流、被熔断。
 *
 * @param enabled 限流与并发上限的总开关；关掉时对话接口不限次数、不限并发（熔断、注入过滤、预算各有自己的开关）
 * @param requestsPerMinute 每个调用方（登录用户按商城用户 ID，匿名按 IP）每分钟最多问几次
 * @param maxConcurrent 整个实例同时最多处理几轮对话（每轮要调一到几次模型）；模型服务一次只能算几个请求，排队太长大家一起超时
 * @param acquireTimeout 没有空位时最多等多久，等不到返回 TOO_BUSY（503）
 * @param circuit 熔断
 * @param injection 提示注入过滤
 * @param degrade 降级：模型不可用时规则类问题直接给检索到的原文
 */
@ConfigurationProperties("assistant.guard")
public record GuardProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("20") int requestsPerMinute,
        @DefaultValue("8") int maxConcurrent,
        @DefaultValue("2s") Duration acquireTimeout,
        @DefaultValue Circuit circuit,
        @DefaultValue Injection injection,
        @DefaultValue Degrade degrade) {

    /**
     * 熔断。
     *
     * @param enabled 开关
     * @param failureThreshold 连续失败几次就熔断（不再调模型，直接返回 MODEL_CIRCUIT_OPEN）
     * @param openDuration 熔断多久之后放一个请求去试（半开）
     */
    public record Circuit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5") int failureThreshold,
            @DefaultValue("30s") Duration openDuration) {}

    /**
     * 提示注入过滤。
     *
     * @param enabled 开关
     * @param extraPatterns 追加的正则（写在归一化之后的文字上：小写、半角、没有空格）
     */
    public record Injection(@DefaultValue("true") boolean enabled, List<String> extraPatterns) {

        /** 紧凑构造器：没配置时是空列表。 */
        public Injection {
            extraPatterns = extraPatterns == null ? List.of() : List.copyOf(extraPatterns);
        }
    }

    /**
     * 降级。
     *
     * @param enabled 开关；关掉时模型不可用就和第 5 章一样返回 503
     * @param excerptChars 降级回答里每段原文最多多少字
     */
    public record Degrade(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("200") int excerptChars) {}

    /**
     * 全部用默认值（测试里手工组装时用）。
     *
     * @return 配置
     */
    public static GuardProperties defaults() {
        return new GuardProperties(
                true,
                20,
                8,
                Duration.ofSeconds(2),
                new Circuit(true, 5, Duration.ofSeconds(30)),
                new Injection(true, List.of()),
                new Degrade(true, 200));
    }
}
