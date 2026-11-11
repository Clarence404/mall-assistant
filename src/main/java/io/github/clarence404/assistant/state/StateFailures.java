package io.github.clarence404.assistant.state;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Redis 出错时怎么办：记一次指标、打一行日志，再按 {@code assistant.state.on-redis-error} 决定放行还是拒绝（第 10 章引入）。
 *
 * <p>所在层：共享状态的工具。每个 Redis 实现（{@link RedisRateLimiter}、{@link RedisConcurrencyLimiter}、{@link RedisUsageStore}、
 * {@link RedisConversationLocks}……）在 catch 到 Redis 异常时调它，所以「出错时的策略」只写在这一个地方，不散落在每个实现里。
 *
 * <p><b>为什么要事先想好</b>：把状态从进程内存挪进 Redis，等于多了一个会挂的依赖。Redis 挂了的那一刻，每个用到它的地方都要回答
 * 「现在放不放行」，不想好就是一个个 500。两种答案各有道理：
 *
 * <ul>
 *   <li><b>放行（allow，默认）</b>：限流和预算是「保护」，不是业务本身。为了保护而让所有用户都问不了，等于自己把自己限流到零。
 *       本地模型不按量花钱，短时间不限也出不了大事；放行期间每一次都计进指标 {@code assistant_state_errors_total}，有告警
 *   <li><b>拒绝（reject）</b>：按 Token 付费的模型，预算失效可能是真金白银；被刷的时候 Redis 恰好挂了，放行就是不设防。宁可暂停服务（503）
 * </ul>
 *
 * <p>不是所有状态都听这个开关：
 *
 * <ul>
 *   <li>待确认操作永远拒绝（{@link #fail}）：写操作「出错就放行」可能执行两次，宁可这次办不了
 *   <li>归属缓存永远当作「没缓存」（多问一次商城，结果一样，只是慢一点）
 *   <li>记账（{@code commit}）出错只能记日志：回答已经给出去了，没法事后拒绝
 * </ul>
 *
 * <p><b>冷却</b>：Redis 挂了的时候，每条命令都要等到超时（连接 1 秒、命令 0.5 秒）才失败，一轮对话进门要碰三次 Redis（预算、次数、名额），
 * 每个用户白等好几秒。所以出错一次之后 {@code cooldown}（默认 5 秒）之内不再碰 Redis，{@link #ensureAvailable} 直接抛
 * {@link CoolingDown}，按出错处理——和第 9 章的熔断是同一个思路，只是简化成「冷却一段时间再试」。
 */
public class StateFailures {

    private static final Logger log = LoggerFactory.getLogger(StateFailures.class);

    private final StateProperties.OnError policy;
    private final MeterRegistry registry;
    private final long cooldownNanos;
    /** 冷却到什么时候（System.nanoTime）；0 = 没在冷却。 */
    private final AtomicLong coolingUntil = new AtomicLong();

    /**
     * 创建。
     *
     * @param policy 出错时的策略
     * @param cooldown 出错一次后多久不碰 Redis；0 = 不冷却
     * @param registry 指标注册表（{@code assistant.state.errors}）
     */
    public StateFailures(StateProperties.OnError policy, Duration cooldown, MeterRegistry registry) {
        this.policy = policy;
        this.cooldownNanos = cooldown.toNanos();
        this.registry = registry;
    }

    /**
     * 测试用：不冷却、指标记在一个临时注册表里。
     *
     * @param policy 策略
     * @return 实例
     */
    public static StateFailures of(StateProperties.OnError policy) {
        return new StateFailures(policy, Duration.ZERO, new SimpleMeterRegistry());
    }

    /** 当前策略。 */
    public StateProperties.OnError policy() {
        return policy;
    }

    /** 指标注册表（测试读指标用）。 */
    public MeterRegistry registry() {
        return registry;
    }

    /**
     * 碰 Redis 之前先问一句：在冷却期里就直接抛 {@link CoolingDown}（它是 Spring 的 {@code DataAccessException}，和真的 Redis 异常走同一个 catch）。
     *
     * @throws CoolingDown 还在冷却
     */
    public void ensureAvailable() {
        long until = coolingUntil.get();
        if (until != 0 && System.nanoTime() - until < 0) {
            throw new CoolingDown();
        }
    }

    /**
     * Redis 出错了：按策略决定放行还是拒绝（ch10-6）。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>不管哪种策略，先 {@link #record}：指标 {@code assistant.state.errors}（标签 {@code component}）加一、打日志、进入冷却
     *   <li>{@link StateProperties.OnError#ALLOW}：返回 true（调用方据此放行）
     *   <li>{@link StateProperties.OnError#REJECT}：抛 {@link StateUnavailableException}（带上 component 和原异常）
     * </ul>
     *
     * @param component 哪一样状态，如 {@code rate:chat}
     * @param error Redis 异常
     * @return 放行时为 true（拒绝时不返回，直接抛）
     * @throws StateUnavailableException 策略是 reject
     */
    public boolean allowOrThrow(String component, RuntimeException error) {
        // TODO ch10-6：record(component, error)；policy 是 REJECT 就 throw new StateUnavailableException(component,
        // error)；否则返回 true
        record(component, error);
        return true;
    }

    /**
     * 不听策略、一律拒绝（待确认操作）：记指标和日志，然后抛 {@link StateUnavailableException}。
     *
     * @param component 哪一样状态
     * @param error Redis 异常
     * @return 不会返回；声明成返回异常，是为了调用处能写成 {@code throw failures.fail(...)}，让编译器知道这里结束了
     * @throws StateUnavailableException 总是
     */
    public StateUnavailableException fail(String component, RuntimeException error) {
        record(component, error);
        throw new StateUnavailableException(component, error);
    }

    /**
     * 只记录、不决定（归属缓存、记账这类「出错了也只能继续」的地方）：指标加一；真的出错（不是冷却期里跳过的）时打一行 warn 日志并开始冷却。
     *
     * <p>日志只打异常的类名和消息，不打堆栈：Redis 挂了的时候每个请求都会走到这里，堆栈会刷满日志；冷却期里跳过的不打日志，同样是为了不刷屏。
     *
     * @param component 哪一样状态
     * @param error Redis 异常
     */
    public void record(String component, RuntimeException error) {
        Counter.builder("assistant.state.errors")
                .description("共享状态（Redis）出错或在冷却期里被跳过的次数，按哪一样状态分")
                .tag("component", component)
                .register(registry)
                .increment();
        if (error instanceof CoolingDown) {
            return;
        }
        if (cooldownNanos > 0) {
            coolingUntil.set(System.nanoTime() + cooldownNanos);
        }
        log.warn(
                "共享状态 {} 出错（策略 {}，{} 毫秒内不再访问 Redis）：{}",
                component,
                policy,
                cooldownNanos / 1_000_000,
                error.toString());
    }

    /** 冷却期里，没有真的去碰 Redis。 */
    public static final class CoolingDown extends DataAccessResourceFailureException {

        /** 创建。 */
        public CoolingDown() {
            super("Redis 刚出过错，冷却中，这次不访问");
        }
    }
}
