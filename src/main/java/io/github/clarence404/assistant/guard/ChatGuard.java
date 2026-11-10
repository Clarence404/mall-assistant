package io.github.clarence404.assistant.guard;

import io.github.clarence404.assistant.ops.ChatMetrics;
import io.github.clarence404.assistant.ops.UsageLedger;
import io.micrometer.core.instrument.Gauge;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>和第 8 章 MCP 的次数上限是两套：MCP 工具不调对话模型，按工具调用计；这里按「一轮对话」计。都只在一个进程里有效，多个实例要放进 Redis
 * 或交给网关（如 Nginx 的 {@code limit_req}、Spring Cloud Gateway 的 RequestRateLimiter）。
 */
@Component
public class ChatGuard {

    private static final Logger log = LoggerFactory.getLogger(ChatGuard.class);

    /** TOO_BUSY 时建议多久之后再试。 */
    static final Duration BUSY_RETRY_AFTER = Duration.ofSeconds(5);

    private final GuardProperties properties;
    private final UsageLedger ledger;
    private final SlidingWindowLimiter limiter;
    private final Semaphore slots;
    private final AtomicInteger inflight = new AtomicInteger();

    /**
     * 创建。
     *
     * @param properties {@code assistant.guard.*}
     * @param ledger Token 总账（每日预算）
     * @param metrics 指标（注册「正在处理几轮」的仪表）
     * @param clock 时钟（测试里拨动它）
     */
    public ChatGuard(GuardProperties properties, UsageLedger ledger, ChatMetrics metrics, Clock clock) {
        this.properties = properties;
        this.ledger = ledger;
        this.limiter = new SlidingWindowLimiter(properties::requestsPerMinute, Duration.ofMinutes(1), clock);
        // 公平模式：先来的先拿到名额，等着的人不会被后来的插队
        this.slots = new Semaphore(Math.max(1, properties.maxConcurrent()), true);
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
     *   <li>拿到了：{@code inflight} 加一，返回一个 {@link Permit}：close 时 {@code inflight} 减一、{@code slots.release()}，<b>只释放一次</b>
     *       （流式接口在完成、出错、取消时都会 close，重复释放会让名额越来越多）
     * </ol>
     *
     * @param caller 调用方（{@link CallerResolver#resolve}）
     * @return 名额，这一轮结束时 close
     * @throws ChatRejection 被挡下
     */
    public Permit admit(String caller) {
        // TODO ch09-6：
        // ① 预算：ledger.dailyBudget() > 0 且 ledger.usedToday(caller) 不小于它
        //    → 抛 TOKEN_BUDGET_EXCEEDED（retryAfter = ledger.untilReset()）
        // ② properties.enabled() 为 false → 返回 Permit.NONE
        // ③ limiter.tryAcquire(caller) 不放行 → 抛 RATE_LIMITED（retryAfter = limiter.retryAfter(caller)）
        // ④ slots.tryAcquire(acquireTimeout 的毫秒数, TimeUnit.MILLISECONDS) 等不到（被中断要恢复中断标记）
        //    → 抛 TOO_BUSY（retryAfter = BUSY_RETRY_AFTER）
        // ⑤ inflight 加一，返回一个只释放一次的 Permit（AtomicBoolean）：close 时 inflight 减一、slots.release()
        return Permit.NONE;
    }

    /** 正在处理几轮（测试与指标用）。 */
    public int inflight() {
        return inflight.get();
    }

    /** 每 10 分钟清掉一分钟内没来过的调用方（匿名 IP 很多，不清会一直占内存）。 */
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
