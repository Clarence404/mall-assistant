package io.github.clarence404.assistant.memory;

import java.time.Duration;
import java.util.Optional;

/**
 * 「这个商城令牌是谁」的缓存：按令牌的指纹（SHA-256）存商城用户，几分钟内不用每句话都问商城（第 6 章 {@link ConversationOwners} 里的缓存，
 * 第 10 章抽成接口）。
 *
 * <p>所在层：记忆。两个实现：{@link MemoryOwnerCache}（进程内，默认）和 {@code state.RedisOwnerCache}（Redis 的 {@code SET PX}）。
 *
 * <p><b>它和别的共享状态不一样：放不放进 Redis 都对</b>。缓存只是「省一次调用」，每个实例各缓存一份，结果完全一样，只是每个实例第一次各问一次商城；
 * 共享的好处是少问几次商城，代价是每次查缓存多一次网络往返（本地缓存是纳秒级，Redis 是亚毫秒到毫秒级）。生产上常见的是两级：本地小缓存 + Redis。
 * 这里放进 Redis 是为了说明「哪些状态必须共享、哪些可以不共享」，讲义里有对比。不管哪个实现，出错都当作「没缓存」——多问一次商城，不会出错。
 */
public interface OwnerCache {

    /**
     * 查缓存。
     *
     * @param fingerprint 令牌指纹
     * @return 归属；没有或已过期为空
     */
    Optional<ConversationOwner> get(String fingerprint);

    /**
     * 放进缓存。
     *
     * @param fingerprint 令牌指纹
     * @param owner 商城确认过的用户（不缓存匿名，也不缓存失败）
     * @param ttl 多久后过期
     */
    void put(String fingerprint, ConversationOwner owner, Duration ttl);

    /** 清掉过期的（内存实现要定期清；Redis 的键自己会过期）。 */
    default void evictExpired() {}
}
