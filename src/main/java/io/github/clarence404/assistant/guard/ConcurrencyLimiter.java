package io.github.clarence404.assistant.guard;

import java.time.Duration;

/**
 * 并发名额：同时最多处理几轮对话（第 10 章从 {@link ChatGuard} 里的信号量抽出接口）。
 *
 * <p>所在层：上线保护。两个实现：{@link SemaphoreConcurrencyLimiter}（一个实例一个 {@link java.util.concurrent.Semaphore}，第 9 章的做法，默认）和
 * {@code state.RedisConcurrencyLimiter}（Redis 有序集合当「分布式信号量」，所有实例合计）。{@link ChatGuard} 只认这个接口。
 *
 * <p>用哪个要看保护的是谁：模型服务是所有实例共用的一个（本项目就是一台 Ollama），名额就该全局算——每个实例各放 8 个、三个实例就是 24 个压到
 * 同一台模型上；如果每个实例背后有自己的模型（或者模型服务能随实例扩容），每实例各算各的反而更合适，也不用多一次 Redis 往返。
 */
public interface ConcurrencyLimiter {

    /**
     * 拿一个名额，最多等 {@code timeout}。
     *
     * @param timeout 没有空位时最多等多久
     * @return 拿到的名额（用完 {@link Slot#release()}）；等不到为 null
     * @throws InterruptedException 等的时候线程被中断
     * @throws io.github.clarence404.assistant.state.StateUnavailableException Redis 实现出错、而策略是 reject 时
     */
    Slot tryAcquire(Duration timeout) throws InterruptedException;

    /** 一个名额。 */
    @FunctionalInterface
    interface Slot {

        /** 还回去。调用方（{@link ChatGuard}）保证只调一次。 */
        void release();
    }
}
