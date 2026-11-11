package io.github.clarence404.assistant.guard;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 进程内的并发名额：一个公平的 {@link Semaphore}（第 9 章 {@link ChatGuard} 里的做法，第 10 章挪到这里，{@code assistant.state.store=memory} 时用）。
 *
 * <p>所在层：上线保护。只管本实例：部署三个实例就是三份名额。公平模式：先来的先拿到，等着的人不会被后来的插队。
 */
public final class SemaphoreConcurrencyLimiter implements ConcurrencyLimiter {

    private final Semaphore slots;

    /**
     * 创建。
     *
     * @param maxConcurrent 最多几个（小于 1 时按 1）
     */
    public SemaphoreConcurrencyLimiter(int maxConcurrent) {
        this.slots = new Semaphore(Math.max(1, maxConcurrent), true);
    }

    @Override
    public Slot tryAcquire(Duration timeout) throws InterruptedException {
        return slots.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS) ? slots::release : null;
    }
}
