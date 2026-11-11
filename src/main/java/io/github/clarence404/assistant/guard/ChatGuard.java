package io.github.clarence404.assistant.guard;

import io.github.clarence404.assistant.ops.ChatMetrics;
import io.github.clarence404.assistant.ops.UsageLedger;
import io.github.clarence404.assistant.state.StateUnavailableException;
import io.micrometer.core.instrument.Gauge;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 对话接口的「门」：每轮对话调模型之前，先过每日预算、每分钟次数、并发上限三关（第 9 章引入）。
 *
 * <p>所在层：上线保护。上游是 {@code ChatController}（阻塞、流式两个接口进门时调 {@link #admit}，一轮结束时关掉拿到的 {@link Permit}）；
 * 下游是 {@link SlidingWindowLimiter}（第 8 章的滑动窗口，推广到对话接口）、{@link UsageLedger}（今天用了多少 Token）和一个信号量（并发名额）。
 *
 * <p><b>三关各防什么</b>：
 *
 * <ul>
 *   <li><b>每日预算</b>：防「钱」——一个用户（或一个脚本）一天能花掉多少 Token 有上限。模型按 Token 计费，本地模型虽然不花钱，也占着 GPU / CPU
 *   <li><b>每分钟次数</b>：防「刷」——同一个人短时间内连发，多半是脚本或者页面 bug（重复提交），挡掉不影响正常用户
 *   <li><b>并发上限</b>：防「挤」——模型服务一次只能算几个请求（Ollama 默认并行 1～4），一百个请求同时压上去，每个都排队、每个都超时，
 *       谁也拿不到回答。不如只放 N 个进去，其余的等两秒、等不到就直接告诉他「忙，稍后再试」（快速失败比慢慢超时体验好）
 * </ul>
 *
 * <p>和第 8 章 MCP 的次数上限是两套：MCP 工具不调对话模型，按工具调用计；这里按「一轮对话」计。
 *
 * <p><b>第 10 章：多实例</b>。第 9 章的三关都只在一个进程里有效。现在次数和名额都通过接口拿（{@link RateLimiter}、{@link ConcurrencyLimiter}），
 * 预算本来就问 {@link UsageLedger}；{@code assistant.state.store=redis} 时三样都放进 Redis，几个实例合计（每分钟 20 次就是全部实例合起来 20 次）。
 * 这个类的判断逻辑一行没改——「规则」和「状态存在哪」分开，就是抽接口的意义。Redis 出错而策略是拒绝时，三关都返回
 * 503 {@link ChatRejection.Reason#STATE_UNAVAILABLE}。另一条路是交给网关（如 Nginx 的 {@code limit_req}、Spring Cloud Gateway 的
 * RequestRateLimiter，后者也是 Redis + Lua），代价是网关不知道「一轮对话用了多少 Token」，预算还得应用自己记。
 */
@Component
public class ChatGuard {

    private static final Logger log = LoggerFactory.getLogger(ChatGuard.class);

    /** TOO_BUSY 时建议多久之后再试。 */
    static final Duration BUSY_RETRY_AFTER = Duration.ofSeconds(5);

    /** STATE_UNAVAILABLE 时建议多久之后再试。 */
    static final Duration STATE_RETRY_AFTER = Duration.ofSeconds(5);

    private final GuardProperties properties;
    private final UsageLedger ledger;
    private final RateLimiter limiter;
    private final ConcurrencyLimiter slots;
    // 本实例正在处理几轮（指标）：名额放进 Redis 以后这里仍然只数本实例的，Grafana 里按实例相加就是全部
    private final AtomicInteger inflight = new AtomicInteger();

    /**
     * 创建：次数和名额都用进程内存（第 9 章的样子；测试里手工组装时用）。
     *
     * @param properties {@code assistant.guard.*}
     * @param ledger Token 总账（每日预算）
     * @param metrics 指标（注册「正在处理几轮」的仪表）
     * @param clock 时钟（测试里拨动它）
     */
    public ChatGuard(GuardProperties properties, UsageLedger ledger, ChatMetrics metrics, Clock clock) {
        this(
                properties,
                ledger,
                metrics,
                (name, limit, window) -> new SlidingWindowLimiter(limit, window, clock),
                new SemaphoreConcurrencyLimiter(properties.maxConcurrent()));
    }

    /**
     * 创建（Spring 注入，第 10 章）：次数和名额的实现由 {@code assistant.state.store} 决定。
     *
     * @param properties {@code assistant.guard.*}
     * @param ledger Token 总账（每日预算）
     * @param metrics 指标（注册「正在处理几轮」的仪表）
     * @param limiters 限流器工厂（内存或 Redis）
     * @param slots 并发名额（内存或 Redis）
     */
    @Autowired
    public ChatGuard(
            GuardProperties properties,
            UsageLedger ledger,
            ChatMetrics metrics,
            RateLimiterFactory limiters,
            ConcurrencyLimiter slots) {
        this.properties = properties;
        this.ledger = ledger;
        this.limiter = limiters.create("chat", properties::requestsPerMinute, Duration.ofMinutes(1));
        this.slots = slots;
        Gauge.builder("assistant.chat.inflight", inflight, AtomicInteger::get)
                .description("正在处理的对话轮数")
                .register(metrics.registry());
    }

    /**
     * 进门（ch09-6）。
     *
     * <p>要求（测试按这个写），按顺序：
     *
     * <ol>
     *   <li>每日预算：{@code ledger.dailyBudget() > 0} 且 {@code ledger.usedToday(调用方)} 已经不小于它 → 抛 {@link ChatRejection.Reason#TOKEN_BUDGET_EXCEEDED}，
     *       {@code retryAfter} 是 {@code ledger.untilReset()}（到零点）。预算有自己的开关（0 = 不限），{@code assistant.guard.enabled=false} 时照样检查
     *   <li>{@code assistant.guard.enabled=false}：后两关都不过，返回 {@link Permit#NONE}
     *   <li>每分钟次数：{@code limiter.tryAcquire(调用方)} 不放行 → 抛 {@link ChatRejection.Reason#RATE_LIMITED}，{@code retryAfter} 是
     *       {@code limiter.retryAfter(调用方)}
     *   <li>并发名额：{@code slots.tryAcquire(acquireTimeout)} 等不到（或等的时候线程被中断，要恢复中断标记）→ 抛
     *       {@link ChatRejection.Reason#TOO_BUSY}，{@code retryAfter} 是 {@link #BUSY_RETRY_AFTER}
     *   <li>拿到了：{@code inflight} 加一，返回一个 {@link Permit}：close 时 {@code inflight} 减一、还名额，<b>只释放一次</b>
     *       （流式接口在完成、出错、取消时都会 close，重复释放会让名额越来越多）
     * </ol>
     *
     * <p>第 10 章：任何一关的共享状态（Redis）出错、而策略是 reject 时，抛 {@link ChatRejection.Reason#STATE_UNAVAILABLE}（503）。
     *
     * @param caller 调用方（{@link CallerResolver#resolve}）
     * @return 名额，这一轮结束时 close
     * @throws ChatRejection 被挡下
     */
    public Permit admit(String caller) {
        try {
            return checkGates(caller);
        } catch (StateUnavailableException e) {
            log.warn("对话进门检查用不了共享状态（{}），按策略拒绝 {}", e.component(), caller);
            throw new ChatRejection(ChatRejection.Reason.STATE_UNAVAILABLE, STATE_RETRY_AFTER);
        }
    }

    /** 三关依次过（第 9 章 ch09-6 的答案；第 10 章只把信号量换成了接口）。 */
    private Permit checkGates(String caller) {
        long budget = ledger.dailyBudget();
        if (budget > 0 && ledger.usedToday(caller) >= budget) {
            throw new ChatRejection(ChatRejection.Reason.TOKEN_BUDGET_EXCEEDED, ledger.untilReset());
        }
        if (!properties.enabled()) {
            return Permit.NONE;
        }
        if (!limiter.tryAcquire(caller)) {
            log.info("对话限流：{} 一分钟内超过 {} 次", caller, properties.requestsPerMinute());
            throw new ChatRejection(ChatRejection.Reason.RATE_LIMITED, limiter.retryAfter(caller));
        }
        ConcurrencyLimiter.Slot slot;
        try {
            slot = slots.tryAcquire(properties.acquireTimeout());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            slot = null;
        }
        if (slot == null) {
            log.warn("对话并发已满（{}），{} 等了 {} 没有空位", properties.maxConcurrent(), caller, properties.acquireTimeout());
            throw new ChatRejection(ChatRejection.Reason.TOO_BUSY, BUSY_RETRY_AFTER);
        }
        inflight.incrementAndGet();
        AtomicBoolean released = new AtomicBoolean();
        ConcurrencyLimiter.Slot taken = slot;
        return () -> {
            if (released.compareAndSet(false, true)) {
                inflight.decrementAndGet();
                taken.release();
            }
        };
    }

    /** 正在处理几轮（测试与指标用）。 */
    public int inflight() {
        return inflight.get();
    }

    /** 每 10 分钟清掉一分钟内没来过的调用方（匿名 IP 很多，不清会一直占内存；Redis 实现什么都不做，键自己会过期）。 */
    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    void evictIdle() {
        limiter.evictIdle();
    }

    /** 一轮对话占着的名额：close 时归还。 */
    @FunctionalInterface
    public interface Permit extends AutoCloseable {

        /** 什么都不占（限流关掉时）。 */
        Permit NONE = () -> {};

        @Override
        void close();
    }
}
