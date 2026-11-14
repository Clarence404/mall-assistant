package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import io.github.clarence404.assistant.guard.ConcurrencyLimiter;
import io.github.clarence404.assistant.guard.SemaphoreConcurrencyLimiter;
import io.github.clarence404.assistant.memory.ConversationLocks;
import io.github.clarence404.assistant.memory.MemoryConversationLocks;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 流式接口的收尾（归还并发名额、放开会话的「正在回答」标记）跑在哪个线程上（审查修复）。
 *
 * <p>为什么要管：共享状态放进 Redis 之后（第 10 章），这两样收尾都是同步的 Redis 调用。Reactor 有一类「不许阻塞」的线程（parallel 调度器、
 * 网络 IO 的事件循环），在上面做同步网络调用会把整条线程上别的请求一起卡住。
 *
 * <p>实测（ollama profile 连一个只收不回的假服务，在 doFinally 里打印线程）：模型自己报的错、正常的文字片段是在
 * {@code loomBoundedElastic-N}（可以阻塞）上发出的，所以审查里担心的「reactor-netty 事件循环线程」那一条不属实；但空闲超时
 * （{@code timeout(streamTimeout)}）的错误信号确实在 {@code parallel-N} 上——修复前收尾就跑在那里。修复后
 * {@code ChatService#answerStream} 把交给下游的信号统一换到 {@code boundedElastic}。
 *
 * <p>做法：把并发名额和会话锁换成会记下「是在哪个线程上被归还的」的实现（内存版外面包一层），再让流式回答分别以超时、以一个从
 * parallel 线程发出的错误结束。
 */
@TestPropertySource(
        properties = {
            "assistant.guard.enabled=true",
            "assistant.guard.requests-per-minute=1000",
            "assistant.chat.stream-timeout=300ms"
        })
class StreamCleanupThreadTest extends FakeModelWebTest {

    /** 一次收尾：线程名，以及它是不是 Reactor「不许阻塞」的线程。 */
    record Cleanup(String what, String thread, boolean nonBlocking) {}

    static final List<Cleanup> CLEANUPS = new CopyOnWriteArrayList<>();

    private static void record(String what) {
        CLEANUPS.add(new Cleanup(what, Thread.currentThread().getName(), Schedulers.isInNonBlockingThread()));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingState {

        /** 并发名额：归还时记下线程。 */
        @Bean
        @Primary
        ConcurrencyLimiter recordingConcurrencyLimiter() {
            ConcurrencyLimiter delegate = new SemaphoreConcurrencyLimiter(8);
            return timeout -> {
                ConcurrencyLimiter.Slot slot = delegate.tryAcquire(timeout);
                return slot == null
                        ? null
                        : () -> {
                            record("release-slot");
                            slot.release();
                        };
            };
        }

        /** 会话锁：放开时记下线程。 */
        @Bean
        @Primary
        ConversationLocks recordingConversationLocks() {
            ConversationLocks delegate = new MemoryConversationLocks();
            return new ConversationLocks() {
                @Override
                public String tryLock(String key) {
                    return delegate.tryLock(key);
                }

                @Override
                public void unlock(String key, String token) {
                    record("unlock-conversation");
                    delegate.unlock(key, token);
                }

                @Override
                public boolean isLocked(String key) {
                    return delegate.isLocked(key);
                }
            };
        }
    }

    @BeforeEach
    void clear() {
        CLEANUPS.clear();
    }

    /** 模型一直不出字：空闲超时结束这一轮。超时信号来自 parallel 线程，收尾不能跟着跑在那里。 */
    @Test
    void cleanupAfterIdleTimeoutRunsOnABlockingCapableThread() throws Exception {
        model.willStream(Flux.never());

        List<ChatStreamApiTest.Sse> events = ChatStreamApiTest.parse(stream("你好"));

        assertThat(events.getLast().event()).isEqualTo("error");
        assertThat(events.getLast().json().get("code").asString()).isEqualTo("MODEL_TIMEOUT");
        assertCleanupsOffNonBlockingThreads();
    }

    /** 模型的流在一个「不许阻塞」的线程上出错（这里故意放到 parallel 上发）：同样要换了线程再收尾。 */
    @Test
    void cleanupAfterAnErrorFromANonBlockingThreadRunsOnABlockingCapableThread() throws Exception {
        model.willStream(Flux.<String>error(new IllegalStateException("模型中途断了"))
                .delaySubscription(Duration.ofMillis(20), Schedulers.parallel()));

        List<ChatStreamApiTest.Sse> events = ChatStreamApiTest.parse(stream("你好"));

        assertThat(events.getLast().event()).isEqualTo("error");
        assertCleanupsOffNonBlockingThreads();
    }

    private static void assertCleanupsOffNonBlockingThreads() {
        // 两样收尾都发生了（会话锁在流结束时放一次，Controller 的 Flux.using 里再放一次，都算）
        await().atMost(Duration.ofSeconds(5))
                .until(() -> CLEANUPS.stream().anyMatch(c -> c.what().equals("release-slot"))
                        && CLEANUPS.stream().anyMatch(c -> c.what().equals("unlock-conversation")));
        assertThat(CLEANUPS)
                .allSatisfy(c -> assertThat(c.nonBlocking())
                        .as("%s 跑在 %s 上", c.what(), c.thread())
                        .isFalse());
    }

    private MvcResult stream(String message) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"" + message + "\",\"conversationId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(5_000); // 等 Flux 结束
        return result;
    }
}
