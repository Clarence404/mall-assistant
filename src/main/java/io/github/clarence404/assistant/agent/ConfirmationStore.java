package io.github.clarence404.assistant.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 待确认操作存在哪（第 10 章从 {@link ConfirmationService} 里抽出来）。
 *
 * <p>所在层：Agent 的存储。两个实现：{@link MemoryConfirmationStore}（进程内的 Map，第 7 章的做法，默认）和 {@code state.RedisConfirmationStore}
 * （多个实例共享：A 实例上生成的确认卡片，负载均衡把「确认」请求转到 B 实例，B 也找得到，而且<b>只执行一次</b>）。
 *
 * <p><b>「只执行一次」靠 {@link #claim}</b>：第 7 章每个确认一把 {@code ReentrantLock}，并发的两次点击一个接一个处理。锁只在一个 JVM 里有效，
 * 两个实例各有各的锁，两次点击落在两个实例上就会各执行一次——取消两次、两张售后单。这里换成「抢占」：谁先在存储里写下自己的决定，谁去执行；
 * 后来的看到已经有人抢了，决定相同就等前一个的结果，决定不同就是 409。Redis 里就是一条 {@code SET 键 决定 NX PX 毫秒}（不存在才写，原子）。
 *
 * <p>为什么不用「分布式锁 + 释放」：锁要设过期时间（不然拿锁的实例挂了就永远锁死），而写操作可能比过期时间还慢（商城卡住），锁过期后第二个请求进来
 * 再执行一次——正是要防的事。抢占不释放：抢到的实例挂在半路，这个确认就停在「处理中」，用户会看到 409「正在处理」，过期后要重新提。
 * 这是「最多执行一次」：对写操作，办不成比办两次好收拾（第 7 章同样的道理：写操作不重试）。
 */
public interface ConfirmationStore {

    /**
     * 登记一个新的待确认操作。
     *
     * @param confirmation 待确认操作（没有结果）
     * @param ttl 存多久（过期时间 + 保留期，之后自动删）
     * @throws io.github.clarence404.assistant.state.StateUnavailableException Redis 出错（写操作一律拒绝）
     */
    void save(StoredConfirmation confirmation, Duration ttl);

    /**
     * 按 ID 取，带上结果（有的话）。
     *
     * @param id 确认 ID
     * @return 待确认操作；不存在（或已经清掉）时为空
     */
    Optional<StoredConfirmation> find(String id);

    /**
     * 抢占：这个确认由我来处理，决定是 {@code decision}。原子的「不存在才写」。
     *
     * @param id 确认 ID
     * @param decision 我的决定
     * @param ttl 抢占记录存多久（和确认本身一样长）
     * @return 抢到了为空；已经有人抢了，返回<b>他的</b>决定
     */
    Optional<ConfirmationOutcome.Decision> claim(String id, ConfirmationOutcome.Decision decision, Duration ttl);

    /**
     * 记下结果（只有抢到的那个调用）。
     *
     * @param id 确认 ID
     * @param outcome 结果
     * @param ttl 存多久
     */
    void complete(String id, ConfirmationOutcome outcome, Duration ttl);

    /**
     * 这个用户的全部确认（含已处理、已过期但还没清掉的）；聊天里说「确认」时找他还没处理的那个。
     *
     * @param ownerKey 归属
     * @return 确认，带上结果
     */
    List<StoredConfirmation> findByOwner(String ownerKey);

    /**
     * 清掉过期时间早于 {@code cutoff} 的（内存实现要定期清；Redis 的键自己会过期，什么都不用做）。
     *
     * @param cutoff 截止时间
     */
    default void sweep(Instant cutoff) {}
}
