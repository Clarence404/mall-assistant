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

    @Override
    public AfterSalesApplication create(AfterSalesApplication application) {
        AfterSalesApplication existing = byConfirmation.putIfAbsent(application.confirmationId(), application);
        return existing != null ? existing : application;
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
