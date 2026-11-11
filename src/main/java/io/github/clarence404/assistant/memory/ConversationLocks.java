package io.github.clarence404.assistant.memory;

/**
 * 「这个会话正在回答」的标记：同一个会话同一时间只允许一个请求（第 6 章 {@link ConversationMemory} 里的 {@code busy} 集合，第 10 章抽成接口）。
 *
 * <p>所在层：记忆。两个实现：{@link MemoryConversationLocks}（进程内的集合，默认）和 {@code state.RedisConversationLocks}
 * （Redis 的 {@code SET NX PX}，经典的分布式锁）。为什么多实例必须共享：用户开两个标签页，两个请求被负载均衡分到两个实例，
 * 各自的集合里都没有这个会话，两边同时回答、先后写回，后写的覆盖先写的——第 6 章要防的事又回来了。
 */
public interface ConversationLocks {

    /**
     * 「没加锁，但照常往下走」的令牌（第 10 章）：Redis 用不了而策略是放行时，{@code state.RedisConversationLocks#tryLock} 返回它；
     * 拿它 {@link #unlock} 什么都不做。
     */
    String SKIPPED = "skipped";

    /**
     * 占住这个会话。
     *
     * @param key 记忆键（{@link ConversationOwners#memoryKey}）
     * @return 占到了返回一个令牌（还的时候要带上）；已经被占了返回 null；Redis 出错而策略是放行时返回 {@link #SKIPPED}
     * @throws io.github.clarence404.assistant.state.StateUnavailableException Redis 出错、而策略是 reject 时
     */
    String tryLock(String key);

    /**
     * 放开。只有令牌对得上才放：自己的标记过了租期被别人占走以后，不能把别人的删掉。
     *
     * @param key 记忆键
     * @param token {@link #tryLock} 给的令牌
     */
    void unlock(String key, String token);

    /**
     * 是否正被占着（后台清理过期会话时跳过正在回答的）。
     *
     * @param key 记忆键
     * @return 被占着为 true
     */
    boolean isLocked(String key);
}
