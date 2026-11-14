package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.error.ModelFailure;
import io.github.clarence404.assistant.intent.IntentService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 模型服务「挂起」（连得上、收了请求却一直不回话）时各种超时实际怎么起作用（审查修复）：用真正的 Ollama 客户端（ollama profile）连一个只收不回的
 * 假服务，量时间、数请求。
 *
 * <p>为了测试快，把读超时从 60 秒调成 2 秒、短时限从 15 秒调成 300 毫秒，比例关系和线上一样。量出来的事实：
 *
 * <ul>
 *   <li>阻塞调用：读超时之后 Spring AI 的重试（{@code spring.ai.retry.max-attempts=1}）会<b>再发一次</b>，所以一次调用要等两个读超时
 *       （线上 60 + 60 秒）。{@link #blockingCallWaitsTwoReadTimeouts}
 *   <li>意图识别套了短时限：到点就放弃，只发出过一次请求（里面的调用被打断，不会再重试）。修复前它和上一条一样要等两个读超时。
 *       {@link #intentGivesUpAtTheShortDeadline}
 *   <li>流式调用：读超时就是「多久没有数据」的超时，不重试。{@link #streamingCallIsNotRetried}
 * </ul>
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=ollama",
            "spring.http.clients.read-timeout=2s",
            "spring.ai.retry.max-attempts=1",
            "assistant.guard.short-call-timeout=300ms",
            "assistant.knowledge.ingest-on-startup=false",
            "assistant.knowledge.memory.dir="
        })
class ModelHangTest {

    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final HttpServer SERVER = start();

    /** 假的模型服务：每个请求都收下，然后一直不回（30 秒，比哪个超时都长）。 */
    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread thread = new Thread(r, "hanging-model");
                thread.setDaemon(true);
                return thread;
            }));
            server.createContext("/", exchange -> {
                REQUESTS.incrementAndGet();
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void ollama(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.ai.ollama.base-url",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @Autowired
    ChatService chatService;

    @Autowired
    IntentService intentService;

    @BeforeEach
    void resetCounter() {
        REQUESTS.set(0);
    }

    @Test
    void blockingCallWaitsTwoReadTimeouts() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> chatService.reply("你好"))
                .isInstanceOfSatisfying(
                        ModelCallException.class, e -> assertThat(e.failure()).isEqualTo(ModelFailure.MODEL_TIMEOUT));

        assertThat(REQUESTS.get()).as("读超时之后重试了一次").isEqualTo(2);
        assertThat(elapsed(start)).isGreaterThanOrEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void intentGivesUpAtTheShortDeadline() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> intentService.classify("退货运费谁出"))
                .isInstanceOfSatisfying(
                        ModelCallException.class, e -> assertThat(e.failure()).isEqualTo(ModelFailure.MODEL_TIMEOUT));

        assertThat(elapsed(start)).as("没有等满读超时（2 秒），更没有等两个").isLessThan(Duration.ofMillis(1800));
        assertThat(REQUESTS.get()).as("到点放弃后没有再重试").isLessThanOrEqualTo(1);
    }

    @Test
    void streamingCallIsNotRetried() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> chatService.stream("你好").blockLast(Duration.ofSeconds(20)))
                .isInstanceOfSatisfying(
                        ModelCallException.class, e -> assertThat(e.failure()).isEqualTo(ModelFailure.MODEL_TIMEOUT));

        assertThat(REQUESTS.get()).as("流式调用不重试").isEqualTo(1);
        assertThat(elapsed(start)).as("等的是一个读超时，不是聊天接口 30 秒的空闲超时").isLessThan(Duration.ofSeconds(15));
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }
}
