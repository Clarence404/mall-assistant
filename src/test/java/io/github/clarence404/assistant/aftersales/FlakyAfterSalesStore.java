package io.github.clarence404.assistant.aftersales;

import java.util.List;
import java.util.Optional;

/**
 * 可以「弄坏」的售后申请单库（测试替身，审查修复引入）：平时和 {@link InMemoryAfterSalesStore} 一样，{@link #breakNow()} 之后每个方法都抛异常，
 * 模拟数据库连不上——用来验证「执行确认时下游抛异常，确认单不会卡在处理中」。
 */
public final class FlakyAfterSalesStore implements AfterSalesStore {

    private final AfterSalesStore delegate = new InMemoryAfterSalesStore();
    private volatile boolean broken;

    /** 从现在起每次调用都抛异常。 */
    public void breakNow() {
        broken = true;
    }

    /** 恢复正常。 */
    public void repair() {
        broken = false;
    }

    private void check() {
        if (broken) {
            throw new IllegalStateException("售后申请单库连不上（测试里故意弄坏的）");
        }
    }

    @Override
    public AfterSalesApplication create(AfterSalesApplication application) {
        check();
        return delegate.create(application);
    }

    @Override
    public List<AfterSalesApplication> findByOwner(String ownerKey) {
        check();
        return delegate.findByOwner(ownerKey);
    }

    @Override
    public Optional<AfterSalesApplication> findOpen(String ownerKey, String orderNo) {
        check();
        return delegate.findOpen(ownerKey, orderNo);
    }

    @Override
    public String type() {
        return "flaky";
    }
}
