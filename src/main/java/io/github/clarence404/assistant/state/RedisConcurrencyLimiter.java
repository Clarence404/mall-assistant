package io.github.clarence404.assistant.state;

import io.github.clarence404.assistant.guard.ConcurrencyLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.function.IntSupplier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 并发名额的 Redis 实现：所有实例共用一个「分布式信号量」（第 10 章引入）。
 *
 * <p>所在层：共享状态。{@code assistant.state.store=redis} 时注入 {@code guard.ChatGuard}。一个有序集合（{@code slots:chat}）存所有被拿走的名额，
 * 分数是租期到期时间；拿名额是一段 Lua 脚本（{@code redis/slot-acquire.lua}：收回过期的 → 数 → 不满就加），还名额是一条 {@code ZREM}。
 *
 * <p>和进程内 {@code Semaphore} 的三个不同：
 *
 * <ul>
 *   <li><b>要租期</b>：拿名额的实例挂了没人来还，过了 {@code assistant.state.lease} 自动收回。代价是一轮比租期还长时，名额提前被收回、多放进一个
 *   <li><b>只能轮询</b>：Redis 没有「阻塞等信号量」的命令，没空位时每 {@code poll-interval} 试一次，直到 {@code acquire-timeout}。不公平：
 *       不保证先来的先拿到（内存版是公平模式）
 *   <li><b>Redis 挂了怎么办</b>：拒绝策略 → 503；放行策略 → <b>退回本实例的信号量</b>（不是不限并发：本地的信号量什么都不依赖，
 *       至少还能保证每个实例不超过上限，比完全放开安全）
 * </ul>
 */
public final class RedisConcurrencyLimiter implements ConcurrencyLimiter {

    private static final RedisScript<Long> SCRIPT = RedisKeys.script("slot-acquire.lua", Long.class);
    private static final String COMPONENT = "slots:chat";

    private final StringRedisTemplate redis;
    private final String key;
    private final IntSupplier max;
    private final Duration lease;
    private final Duration pollInterval;
    private final Clock clock;
    private final StateFailures failures;
    private final ConcurrencyLimiter fallback;

    /**
     * 创建。
     *
     * @param redis Redis 客户端
     * @param key 名额集合的键，如 {@code mall-assistant:slots:chat}
     * @param max 最多几个名额（所有实例合计）
     * @param lease 租期
     * @param pollInterval 没空位时隔多久再试
     * @param clock 时钟
     * @param failures 出错时怎么办
     * @param fallback Redis 用不了、策略是放行时退回的本地名额
     */
    public RedisConcurrencyLimiter(
            StringRedisTemplate redis,
            String key,
            IntSupplier max,
            Duration lease,
            Duration pollInterval,
            Clock clock,
            StateFailures failures,
            ConcurrencyLimiter fallback) {
        this.redis = redis;
        this.key = key;
        this.max = max;
        this.lease = lease;
        this.pollInterval = pollInterval;
        this.clock = clock;
        this.failures = failures;
        this.fallback = fallback;
    }

    /**
     * 拿一个名额，最多等 {@code timeout}（ch10-2）。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>每个名额一个随机 ID；执行 {@code SCRIPT}：KEYS 是 {@code key}，ARGV 依次是现在（毫秒）、上限、名额 ID、租期（毫秒）；返回 1 就拿到了，
     *       返回一个 {@link Slot}：release 时 {@code ZREM key 名额ID}（{@link #release}，Redis 出错只记录，租期到了会自动收回）
     *   <li>返回 0：没到 {@code timeout} 就睡 {@code pollInterval} 再试（被中断就把 InterruptedException 抛出去）；到了返回 null
     *   <li>Redis 出错（含冷却期，每次试之前先 {@code failures.ensureAvailable()}）：{@code failures.allowOrThrow(COMPONENT, e)}——拒绝策略会抛；
     *       放行策略退回 {@code fallback.tryAcquire(剩下的等待时间)}
     * </ul>
     */
    @Override
    public Slot tryAcquire(Duration timeout) throws InterruptedException {
        // TODO ch10-2：
        // 截止时间 = System.nanoTime() + timeout；名额 ID = UUID.randomUUID()
        // while (true)：
        //   try：failures.ensureAvailable()；redis.execute(SCRIPT, List.of(key), 现在毫秒, 上限, 名额ID, 租期毫秒)
        //        返回 1 → return () -> release(名额ID)
        //   catch (DataAccessException e)：failures.allowOrThrow(COMPONENT, e)（拒绝策略会抛），然后退回 fallback.tryAcquire(剩下的时间)
        //   过了截止时间 → return null；否则 TimeUnit.MILLISECONDS.sleep(pollInterval 的毫秒数) 再试
        return () -> {};
    }

    /** 还名额：一条 ZREM。出错只记录，不抛（这一轮已经答完了），租期到了会自动收回。 */
    private void release(String slotId) {
        try {
            redis.opsForZSet().remove(key, slotId);
        } catch (DataAccessException e) {
            failures.record(COMPONENT, e);
        }
    }
}
