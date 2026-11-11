package io.github.clarence404.assistant.state;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 共享状态存在哪，前缀 {@code assistant.state}（第 10 章引入）。
 *
 * <p>所在层：配置。由 {@code @ConfigurationPropertiesScan} 绑定，{@link MemoryStateConfig} / {@link RedisStateConfig}（按 {@link #store} 二选一）、
 * {@link StateFailures}（Redis 出错时怎么办）读它。
 *
 * <p><b>「共享状态」指哪些</b>：前九章里只在一个进程里有效的东西——第 9 章的 Token 账本（每日预算）、对话接口的每分钟次数与并发名额，
 * 第 8 章 MCP 的每分钟次数，第 7 章的待确认操作（5 分钟过期、只执行一次），第 6 章「同一会话同时只答一个」的标记和「令牌是谁」的缓存。
 * 部署一个实例时放在内存里最简单；部署多个实例时，每个实例各算各的：每分钟 20 次变成「每个实例 20 次」，A 实例生成的确认单 B 实例找不到。
 *
 * <p><b>为什么一个开关管全部</b>（而不是像第 4、6、7 章那样每样东西一个 {@code store}）：这几样要么一起共享、要么一起不共享才说得清——
 * 只把确认放进 Redis、限流还在内存里，多实例时一半规则是全局的、一半是每实例的，排查问题时谁也说不清「现在到底限的是多少」。
 * 对话记忆、售后申请单仍然各自用第 6、7 章的 {@code store}（它们是「数据」，放 PostgreSQL；这里是「协调用的状态」，放 Redis）。
 *
 * <p>熔断器<b>不在这里</b>：它仍然每个实例一份（见 {@code guard.ModelCircuitBreaker} 和 docs/ch10.md「熔断要不要共享」）。
 *
 * @param store {@code memory}（默认，进程内，重启就没了，多实例各算各的）或 {@code redis}
 * @param onRedisError Redis 连不上、超时的时候，限流与预算怎么办：{@code allow}（默认，放行）或 {@code reject}（拒绝，503 STATE_UNAVAILABLE）；
 *     待确认操作永远是拒绝（写操作宁可办不了，也不能办两次）
 * @param redis {@code store=redis} 时的连接与键
 * @param lease 放进 Redis 的「占用」最长占多久：并发名额、「这个会话正在回答」的标记。拿着它的实例挂了、没来得及还，过了租期自动回收；
 *     要比一轮对话的最长时间（Agent 120 秒）长，否则还在回答就被别人拿走了
 * @param pollInterval 并发名额满了时隔多久再试一次（Redis 没有「阻塞等信号量」的命令，只能轮询）
 * @param confirmationWait 同一个确认正在被另一个请求（可能在另一个实例上）执行时，最多等多久它的结果
 */
@ConfigurationProperties("assistant.state")
public record StateProperties(
        @DefaultValue("memory") StoreType store,
        @DefaultValue("allow") OnError onRedisError,
        @DefaultValue Redis redis,
        @DefaultValue("5m") Duration lease,
        @DefaultValue("50ms") Duration pollInterval,
        @DefaultValue("10s") Duration confirmationWait) {

    /** 存储类型。 */
    public enum StoreType {
        /** 进程内存（前九章的做法）。 */
        MEMORY,
        /** Redis：多个实例共享。 */
        REDIS
    }

    /** Redis 不可用时，限流与预算的策略。 */
    public enum OnError {
        /** 放行：限流不计数、预算当作还没用，并发名额退回本实例的信号量。可用性优先：Redis 挂了不该让所有用户都问不了。 */
        ALLOW,
        /** 拒绝：返回 503 STATE_UNAVAILABLE。成本优先：按量计费的模型，宁可暂停服务也不能让预算失效。 */
        REJECT
    }

    /**
     * Redis 连接。
     *
     * @param url 连接地址，如 {@code redis://localhost:6379/0}；有密码写成 {@code redis://:密码@主机:6379/5}（写进不入库的
     *     config/application-local.yml 或环境变量 {@code REDIS_URL}）。最后一段是 database 编号：和别的系统共用一个 Redis 时换一个编号
     * @param keyPrefix 每个键的前缀，如 {@code mall-assistant:}：一眼看出是谁的键，{@code SCAN mall-assistant:*} 能只列出本应用的
     * @param timeout 每条命令的超时：Redis 本来是亚毫秒级的，超过这个时间就当它不可用，按 {@code on-redis-error} 处理，不让请求卡在 Redis 上
     * @param connectTimeout 建连接的超时
     * @param cooldown 出错一次之后多久不再碰 Redis（直接按出错处理）：Redis 挂了时不要让每个请求都等一次超时，和第 9 章熔断是同一个思路
     */
    public record Redis(
            @DefaultValue("redis://localhost:6379/0") String url,
            @DefaultValue("mall-assistant:") String keyPrefix,
            @DefaultValue("500ms") Duration timeout,
            @DefaultValue("1s") Duration connectTimeout,
            @DefaultValue("5s") Duration cooldown) {}

    /**
     * 全部用默认值（测试里手工组装时用）。
     *
     * @return 配置
     */
    public static StateProperties defaults() {
        return new StateProperties(
                StoreType.MEMORY,
                OnError.ALLOW,
                new Redis(
                        "redis://localhost:6379/0",
                        "mall-assistant:",
                        Duration.ofMillis(500),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(5)),
                Duration.ofMinutes(5),
                Duration.ofMillis(50),
                Duration.ofSeconds(10));
    }

    /**
     * 换一个 Redis 策略（测试用）。
     *
     * @param onError 策略
     * @return 新配置
     */
    public StateProperties withOnRedisError(OnError onError) {
        return new StateProperties(store, onError, redis, lease, pollInterval, confirmationWait);
    }
}
