package io.github.clarence404.assistant.aftersales;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * 内存版售后申请单库（审查修复）：同一笔订单只能有一张待审核的申请，哪怕两张确认单同时来存。JDBC 版的同一条规则在
 * {@code JdbcAfterSalesStoreIT} 里用真正的 PostgreSQL 验证。
 */
class InMemoryAfterSalesStoreTest {

    private static final String OWNER = "user:10001";
    private static final String ORDER = "21182516707889029140785";

    private final InMemoryAfterSalesStore store = new InMemoryAfterSalesStore();

    private static AfterSalesApplication application(String orderNo) {
        return new AfterSalesApplication(
                "AS-" + UUID.randomUUID().toString().substring(0, 8),
                OWNER,
                orderNo,
                AfterSalesApplication.Type.RETURN_REFUND,
                "东西坏了",
                AfterSalesApplication.Status.PENDING_REVIEW,
                UUID.randomUUID().toString(),
                Instant.parse("2026-11-05T10:00:00Z"));
    }

    /** 同一笔订单、两张不同的确认单：第二次不新建，返回第一张（调用方看返回值的确认 ID 不是自己的，就知道没建成）。 */
    @Test
    void secondApplicationForTheSameOrderReturnsTheFirst() {
        AfterSalesApplication first = application(ORDER);
        AfterSalesApplication second = application(ORDER);

        assertThat(store.create(first)).isEqualTo(first);
        assertThat(store.create(second)).isEqualTo(first);

        assertThat(store.findByOwner(OWNER)).containsExactly(first);
    }

    /** 别的订单互不影响；同一个确认 ID 重复存仍然幂等。 */
    @Test
    void otherOrdersAndRepeatedConfirmationsBehaveAsBefore() {
        AfterSalesApplication first = application(ORDER);
        AfterSalesApplication other = application("21182516707889029150785");

        store.create(first);
        assertThat(store.create(other)).isEqualTo(other);
        assertThat(store.create(first)).isEqualTo(first);

        assertThat(store.findByOwner(OWNER)).hasSize(2);
    }

    /** 16 个线程同时为同一笔订单存（各自的确认单）：库里只有一张，所有人拿到的都是它。 */
    @Test
    void concurrentApplicationsForTheSameOrderStoreExactlyOne() throws Exception {
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AfterSalesApplication>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                AfterSalesApplication mine = application(ORDER);
                Callable<AfterSalesApplication> task = () -> {
                    start.await();
                    return store.create(mine);
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            List<AfterSalesApplication> returned = new ArrayList<>();
            for (Future<AfterSalesApplication> result : results) {
                returned.add(result.get());
            }

            assertThat(store.findByOwner(OWNER)).hasSize(1);
            assertThat(returned).containsOnly(store.findByOwner(OWNER).getFirst());
        }
    }
}
