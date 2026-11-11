package io.github.clarence404.assistant.state;

import io.github.clarence404.assistant.memory.ConversationLocks;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 「这个会话正在回答」标记的 Redis 实现：经典的分布式锁（第 10 章引入）。
 *
 * <p>所在层：共享状态。{@code assistant.state.store=redis} 时注入 {@code memory.ConversationMemory}。键是 {@code conv-busy:记忆键}。
 *
 * <p>分布式锁的三个要点，正好对应三行代码：
 *
 * <ul>
 *   <li><b>占</b>：{@code SET 键 令牌 NX PX 租期}——不存在才写（只有一个人能占到），并且<b>同一条命令</b>里带上过期时间。
 *       分成 {@code SETNX} + {@code PEXPIRE} 两条，中间实例挂了，就留下一个永不过期的锁
 *   <li><b>租期</b>：占着的实例挂了，过了租期自动放开，这个会话不会永远「正在回答」
 *   <li><b>还</b>：只删自己的——值是随机令牌，删之前比一下（{@code redis/unlock.lua}）。不比就可能删掉别人的锁（见脚本里的注释）
 * </ul>
 *
 * <p>它不是「绝对安全」的锁：持有者卡得比租期还久（长时间 GC 停顿），租期到了别人照样能进来，两个请求同时在答。对「同一会话同时只答一个」
 * 这种「尽量避免」的场景够用；真要严格互斥（比如扣库存），要在写入那一侧再加版本号检查（fencing token）。讲义「分布式锁的边界」一节。
 */
public final class RedisConversationLocks implements ConversationLocks {

    private static final RedisScript<Long> UNLOCK = RedisKeys.script("unlock.lua", Long.class);
    private static final String COMPONENT = "conversation-lock";

    private final StringRedisTemplate redis;
    private final RedisKeys keys;
    private final Duration lease;
    private final StateFailures failures;

    /**
     * 创建。
     *
     * @param redis Redis 客户端
     * @param keys 键前缀
     * @param lease 租期（{@code assistant.state.lease}）
     * @param failures 出错时怎么办
     */
    public RedisConversationLocks(StringRedisTemplate redis, RedisKeys keys, Duration lease, StateFailures failures) {
        this.redis = redis;
        this.keys = keys;
        this.lease = lease;
        this.failures = failures;
    }

    /**
     * 占住这个会话（ch10-5 之一）。
     *
     * <p>要求（测试按这个写）：令牌是随机 UUID；{@code opsForValue().setIfAbsent(键, 令牌, lease)}（就是 {@code SET NX PX}），写进去了返回令牌，
     * 没写进去返回 null。Redis 出错（含冷却期）：{@code failures.allowOrThrow}——放行策略返回 {@link #SKIPPED}（这一轮不加锁照常答），拒绝策略抛异常。
     */
    @Override
    public String tryLock(String key) {
        String token = UUID.randomUUID().toString();
        try {
            failures.ensureAvailable();
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey(key), token, lease)) ? token : null;
        } catch (DataAccessException e) {
            failures.allowOrThrow(COMPONENT, e);
            return SKIPPED;
        }
    }

    /**
     * 放开（ch10-5 之二）。
     *
     * <p>要求（测试按这个写）：令牌是 null 或 {@link #SKIPPED} 就什么都不做；否则执行 {@link #UNLOCK} 脚本（KEYS 是键，ARGV 是令牌），
     * 只删自己的。Redis 出错只 {@code failures.record}：租期到了自己会放开。
     */
    @Override
    public void unlock(String key, String token) {
        if (token == null || SKIPPED.equals(token)) {
            return;
        }
        try {
            redis.execute(UNLOCK, List.of(lockKey(key)), token);
        } catch (DataAccessException e) {
            failures.record(COMPONENT, e);
        }
    }

    /** 是否被占着；Redis 出错时当作「被占着」（后台清理宁可这次不删）。 */
    @Override
    public boolean isLocked(String key) {
        try {
            failures.ensureAvailable();
            return Boolean.TRUE.equals(redis.hasKey(lockKey(key)));
        } catch (DataAccessException e) {
            failures.record(COMPONENT, e);
            return true;
        }
    }

    private String lockKey(String key) {
        return keys.of("conv-busy", key);
    }
}
