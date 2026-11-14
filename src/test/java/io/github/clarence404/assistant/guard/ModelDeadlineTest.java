package io.github.clarence404.assistant.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.error.ModelFailure;
import io.github.clarence404.assistant.ops.TurnRecord;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 短调用的时限（审查修复）：到点放弃并打断里面的调用；账本跟着换线程；里面抛的异常原样出来。
 */
class ModelDeadlineTest {

    /** 模型挂起（这里用一直 sleep 代替）：到时限就报 MODEL_TIMEOUT，而且里面的调用被打断了，不会在后台继续占着模型。 */
    @Test
    void hangingCallIsAbandonedAndInterrupted() throws InterruptedException {
        ModelDeadline deadline = new ModelDeadline(Duration.ofMillis(100));
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);
        long start = System.nanoTime();

        assertThatThrownBy(() -> deadline.call("intent", () -> {
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException e) {
                        interrupted.set(true);
                    } finally {
                        finished.countDown();
                    }
                    return "太晚了";
                }))
                .isInstanceOfSatisfying(
                        ModelCallException.class, e -> assertThat(e.failure()).isEqualTo(ModelFailure.MODEL_TIMEOUT));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).isTrue();
    }

    /** 来得及的调用：结果原样返回；这一轮的账本在执行调用的那个线程上也找得到（Token 记账靠它）。 */
    @Test
    void turnRecordFollowsTheCallToItsThread() {
        ModelDeadline deadline = new ModelDeadline(Duration.ofSeconds(5));
        TurnRecord record = new TurnRecord("user:1", "c1");
        AtomicReference<TurnRecord> seen = new AtomicReference<>();
        AtomicReference<Thread> thread = new AtomicReference<>();

        String result;
        try (TurnRecord.Binding bound = record.bind()) {
            result = deadline.call("condense", () -> {
                seen.set(TurnRecord.current());
                thread.set(Thread.currentThread());
                return "好";
            });
        }

        assertThat(result).isEqualTo("好");
        assertThat(seen.get()).isSameAs(record);
        assertThat(thread.get()).isNotSameAs(Thread.currentThread());
    }

    /** 里面抛的异常原样抛出来（意图识别要靠异常类型区分「解析不了」和「模型调不通」）。 */
    @Test
    void exceptionsPassThroughUnchanged() {
        ModelDeadline deadline = new ModelDeadline(Duration.ofSeconds(5));
        IllegalStateException boom = new IllegalStateException("解析不了");

        assertThatThrownBy(() -> deadline.call("intent", () -> {
                    throw boom;
                }))
                .isSameAs(boom);
    }

    /** 不设时限：就在调用方的线程上跑，和没有这一层一样。 */
    @Test
    void noneRunsOnTheCallerThread() {
        AtomicReference<Thread> thread = new AtomicReference<>();

        ModelDeadline.none().call("intent", () -> {
            thread.set(Thread.currentThread());
            return 1;
        });

        assertThat(thread.get()).isSameAs(Thread.currentThread());
    }
}
