package io.github.clarence404.assistant.aftersales;

import java.util.List;
import java.util.Optional;

/**
 * 售后申请单的存储（第 7 章引入）：内存（{@link InMemoryAfterSalesStore}，默认）或 PostgreSQL（{@link JdbcAfterSalesStore}），
 * 由 {@code assistant.after-sales.store} 选，和第 6 章记忆的 memory / jdbc 一个套路（见 {@link AfterSalesConfig}）。
 *
 * <p>所在层：售后申请的存储。上游是 {@code agent.WriteActionGuard}（提议前查「这笔是不是已经有待审核的申请」）、
 * {@code agent.ConfirmationService}（确认后创建）和 {@link AfterSalesController}（列出自己的申请单）。
 */
public interface AfterSalesStore {

    /**
     * 存一张申请单。<b>幂等</b>：同一个 {@code confirmationId} 已经存过时不再新建，返回已经存的那张。
     *
     * <p>为什么幂等要做在存储这一层：确认接口自己已经保证「同一个确认只执行一次」（{@code ConfirmationService} 里的锁和结果缓存），
     * 但那只在一个进程里有效；多个实例、或者执行到一半进程重启后用户再点一次，靠的就是这里的唯一约束（JDBC 是 {@code confirmation_id} 的唯一索引）。
     *
     * @param application 申请单
     * @return 存下的申请单（可能是之前那张）
     */
    AfterSalesApplication create(AfterSalesApplication application);

    /**
     * 某个用户的全部申请单，按提交时间从新到旧。
     *
     * @param ownerKey 归属，如 {@code user:10001}
     * @return 申请单，可能为空列表
     */
    List<AfterSalesApplication> findByOwner(String ownerKey);

    /**
     * 某个用户的某笔订单还在审核中的申请单（同一笔订单不重复提交）。
     *
     * @param ownerKey 归属
     * @param orderNo 订单号
     * @return 待审核的申请单；没有时为空
     */
    Optional<AfterSalesApplication> findOpen(String ownerKey, String orderNo);

    /**
     * 存储类型，调试信息用。
     *
     * @return {@code memory} 或 {@code jdbc}
     */
    String type();
}
