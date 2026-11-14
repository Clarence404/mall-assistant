package io.github.clarence404.assistant.aftersales;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 售后申请单存在内存里（默认）：零依赖，重启就没了，只在一个进程里有效（第 7 章引入）。
 *
 * <p>所在层：售后申请的存储。按 confirmationId 存（{@link ConcurrentHashMap#putIfAbsent} 保证同一个确认只存一张）。
 */
public final class InMemoryAfterSalesStore implements AfterSalesStore {

    private final Map<String, AfterSalesApplication> byConfirmation = new ConcurrentHashMap<>();

    /**
     * 存一张申请单。整个方法加锁（审查修复）：「这个确认存过没有」「这笔订单有没有待审核的」「存」三步要一气呵成，中间不能插进另一个线程；
     * 申请单一天也没几张，一把锁足够。
     */
    @Override
    public synchronized AfterSalesApplication create(AfterSalesApplication application) {
        AfterSalesApplication sameConfirmation = byConfirmation.get(application.confirmationId());
        if (sameConfirmation != null) {
            return sameConfirmation;
        }
        if (application.status() == AfterSalesApplication.Status.PENDING_REVIEW) {
            Optional<AfterSalesApplication> open = findOpen(application.ownerKey(), application.orderNo());
            if (open.isPresent()) {
                return open.get();
            }
        }
        byConfirmation.put(application.confirmationId(), application);
        return application;
    }

    @Override
    public List<AfterSalesApplication> findByOwner(String ownerKey) {
        return byConfirmation.values().stream()
                .filter(a -> a.ownerKey().equals(ownerKey))
                .sorted(Comparator.comparing(AfterSalesApplication::createdAt).reversed())
                .toList();
    }

    @Override
    public Optional<AfterSalesApplication> findOpen(String ownerKey, String orderNo) {
        return findByOwner(ownerKey).stream()
                .filter(a -> a.orderNo().equals(orderNo) && a.status() == AfterSalesApplication.Status.PENDING_REVIEW)
                .findFirst();
    }

    @Override
    public String type() {
        return "memory";
    }
}
