package io.github.clarence404.assistant.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 「这一轮还有后台工作在跑」的登记（审查修复，{@link TurnRecord#hold} / {@link TurnRecord#whenSettled}）：收尾动作等后台工作停了才执行。
 */
class TurnRecordTest {

    private final TurnRecord record = new TurnRecord("user:1", "c1");
    private final List<String> done = new ArrayList<>();

    /** 没有后台工作（普通问答、RAG）：收尾立刻执行，和没有这个机制时一样。 */
    @Test
    void runsImmediatelyWhenNothingIsHeld() {
        record.whenSettled(() -> done.add("close"));

        assertThat(done).containsExactly("close");
    }

    /** 有后台工作：收尾先记下，等它停了再按登记的顺序执行（先放开会话，再还名额、记账）。 */
    @Test
    void defersUntilTheHoldIsReleasedAndKeepsOrder() {
        TurnRecord.Hold running = record.hold();
        record.whenSettled(() -> done.add("close-conversation"));
        record.whenSettled(() -> done.add("release-permit"));
        assertThat(done).isEmpty();

        running.close();

        assertThat(done).containsExactly("close-conversation", "release-permit");
        // 之后再来的收尾又是立刻执行
        record.whenSettled(() -> done.add("later"));
        assertThat(done).last().isEqualTo("later");
    }

    /** 两处都在跑：要等最后一个停；同一个句柄关两次只算一次。 */
    @Test
    void waitsForTheLastHoldAndIgnoresDoubleClose() {
        TurnRecord.Hold first = record.hold();
        TurnRecord.Hold second = record.hold();
        record.whenSettled(() -> done.add("cleanup"));

        first.close();
        first.close();
        assertThat(done).as("第二个还没停，重复关第一个不能把计数减成 0").isEmpty();

        second.close();
        assertThat(done).containsExactly("cleanup");
    }

    /**
     * 关句柄的线程带着「被打断」的标记（取消订阅时 Reactor 会打断跑 Agent 的线程）：收尾执行期间标记要先拿掉——同步的 Redis 调用看到这个标记
     * 会直接失败，名额就还不回去了；执行完再放回去。
     */
    @Test
    void cleanupRunsWithTheInterruptFlagCleared() {
        TurnRecord.Hold running = record.hold();
        record.whenSettled(
                () -> done.add("interrupted=" + Thread.currentThread().isInterrupted()));

        Thread.currentThread().interrupt();
        try {
            running.close();
            assertThat(Thread.currentThread().isInterrupted()).as("标记执行完放回去了").isTrue();
        } finally {
            Thread.interrupted(); // 别把标记留给后面的测试
        }

        assertThat(done).containsExactly("interrupted=false");
    }

    /** 一个收尾动作抛异常，不连累后面的（名额一定要还）。 */
    @Test
    void oneFailingActionDoesNotBlockTheRest() {
        TurnRecord.Hold running = record.hold();
        record.whenSettled(() -> {
            throw new IllegalStateException("关会话时 Redis 连不上");
        });
        record.whenSettled(() -> done.add("release-permit"));

        running.close();

        assertThat(done).containsExactly("release-permit");
    }
}
