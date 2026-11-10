package io.github.clarence404.assistant.guard;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 模型调用的熔断器：连续失败到一定次数就「断开」，一段时间内不再调模型、直接失败；时间到了放一个请求去试，成功就恢复（第 9 章引入）。
 *
 * <p>所在层：上线保护。{@link CircuitBreakerAdvisor} 在每次模型调用前问 {@link #tryAcquire}、调用后报告 {@link #onSuccess} / {@link #onFailure}；
 * 它挂在共享 ChatClient 的默认 advisor 上，意图识别、追问改写、回答、RAG、Agent 的每一步都经过它。
 *
 * <p><b>为什么要熔断</b>：模型服务挂了的时候，每个请求都要等到超时（连接 3 秒、读 60 秒）才失败——用户白等一分钟，虚拟线程、并发名额也被占满，
 * 后面的人连「排队」都排不上。熔断之后同样是失败，但是<b>立刻</b>失败（MODEL_CIRCUIT_OPEN），规则类问题还能降级成直接给原文（{@code ChatService}，ch09-8）。
 *
 * <p>三个状态（经典的断路器模式，Resilience4j 等库也是这个模型）：
 *
 * <pre>
 * CLOSED（正常） --连续失败 N 次--&gt; OPEN（熔断，直接拒绝）
 * OPEN --过了 open-duration--&gt; HALF_OPEN（只放一个请求去试）
 * HALF_OPEN --试的那个成功--&gt; CLOSED；--失败--&gt; OPEN（重新计时）
 * </pre>
 *
 * <p>为什么自己写而不用 Resilience4j：三个状态几十行代码，读得懂比少写几行重要；生产上用成熟的库（滑动窗口按失败率算、慢调用也算失败、指标现成）。
 * 只在一个进程里有效：每个实例各自判断，这对熔断恰好是对的（每个实例到模型服务的网络可能不一样）。
 */
@Component
public class ModelCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(ModelCircuitBreaker.class);

    /** 状态。 */
    public enum State {
        /** 正常：放行，数连续失败次数。 */
        CLOSED,
        /** 熔断：直接拒绝，等 open-duration 过去。 */
        OPEN,
        /** 半开：放一个请求去试，其余照样拒绝。 */
        HALF_OPEN
    }

    private final GuardProperties.Circuit properties;
    private final Clock clock;

    // 下面四个字段只在 synchronized 方法里读写：状态转换要「看一眼再改」，不加锁两个线程会同时把 OPEN 改成 HALF_OPEN、各放一个请求
    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;
    private Instant probeStartedAt;

    /**
     * 创建（Spring 注入）。
     *
     * @param properties {@code assistant.guard.*}
     * @param clock 时钟
     * @param registry 指标注册表（有 Actuator 时才有）：注册一个表示当前状态的 gauge
     */
    @Autowired
    public ModelCircuitBreaker(GuardProperties properties, Clock clock, ObjectProvider<MeterRegistry> registry) {
        this(properties.circuit(), clock);
        registry.ifAvailable(r -> Gauge.builder(
                        "assistant.model.circuit.state", this, b -> b.state().ordinal())
                .description("模型熔断器状态：0 正常，1 熔断，2 半开")
                .register(r));
    }

    /**
     * 测试用。
     *
     * @param properties 熔断配置
     * @param clock 时钟（测试里拨动它）
     */
    public ModelCircuitBreaker(GuardProperties.Circuit properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 这次能不能调模型（ch09-7 之一）。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>熔断关着（{@code enabled=false}）：永远 true
     *   <li>CLOSED：true
     *   <li>OPEN：从 {@code openedAt} 起还没过 {@code openDuration}：false；过了：转成 HALF_OPEN、记下 {@code probeStartedAt = 现在}，放这一个（true）
     *   <li>HALF_OPEN：已经有一个在试了，其余一律 false；但如果那个试探的请求过了 {@code openDuration} 还没有结果（被取消了、卡住了），当它没了，
     *       放这一个重新试（同样记下 {@code probeStartedAt}）
     * </ul>
     *
     * @return 放行时为 true
     */
    public synchronized boolean tryAcquire() {
        // TODO ch09-7：没开 → true；CLOSED → true；
        // OPEN：还没到 openedAt + openDuration → false；到了就转 HALF_OPEN、probeStartedAt = 现在、true；
        // HALF_OPEN：probeStartedAt + openDuration 之前 → false（已经有一个在试）；之后当它丢了，
        // probeStartedAt = 现在、true
        return true;
    }

    /**
     * 一次模型调用成功（ch09-7 之二）：连续失败清零；半开时试成了，回到 CLOSED。
     */
    public synchronized void onSuccess() {
        // TODO ch09-7：回到 CLOSED，连续失败次数清零
    }

    /**
     * 一次模型调用失败（ch09-7 之三）。
     *
     * <p>要求（测试按这个写）：半开时试的那个失败了 → 直接回到 OPEN，{@code openedAt = 现在}（重新计时）；CLOSED 时连续失败次数加一，到了
     * {@code failureThreshold} 就转成 OPEN（{@code openedAt = 现在}）并记一条 warn 日志；已经是 OPEN 时（熔断前就发出去的请求陆续失败）不用管。
     */
    public synchronized void onFailure() {
        // TODO ch09-7：HALF_OPEN → OPEN（openedAt = 现在）；
        // CLOSED → 连续失败次数加一，到了 failureThreshold 就 OPEN（openedAt = 现在）并记 warn 日志；OPEN 时不用管
    }

    /**
     * 当前状态（只读，指标和调试用；OPEN 到期之后要等下一次 {@link #tryAcquire} 才会变成 HALF_OPEN）。
     *
     * @return 状态
     */
    public synchronized State state() {
        return state;
    }

    /**
     * 距离下一次试探还有多久（填 Retry-After 用）。
     *
     * @return 时长；不在 OPEN 时为 0
     */
    public synchronized Duration remaining() {
        if (state != State.OPEN) {
            return Duration.ZERO;
        }
        Duration left = Duration.between(clock.instant(), openedAt.plus(properties.openDuration()));
        return left.isNegative() ? Duration.ZERO : left;
    }
}
