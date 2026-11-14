package io.github.clarence404.assistant.guard;

import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.error.ModelFailure;
import io.github.clarence404.assistant.ops.TurnRecord;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 短调用的时限：追问改写、意图识别这类「输出只有一句话 / 一个小 JSON」的模型调用，最多等 {@code assistant.guard.short-call-timeout}
 * （默认 15 秒），到点就放弃并报 {@link ModelFailure#MODEL_TIMEOUT}（审查修复时引入）。
 *
 * <p>所在层：上线保护。{@code QuestionCondenser}（追问改写）和 {@code IntentService}（意图识别）把那一次 {@code call()} 包在
 * {@link #call} 里。
 *
 * <p><b>为什么要它</b>：阻塞调用的超时原来只有 HTTP 客户端的 {@code spring.http.clients.read-timeout}（60 秒，是给「生成一整段回答」留的），
 * 而且实测 Spring AI 的重试（{@code spring.ai.retry.max-attempts=1}）对读超时同样生效——模型服务挂起（连得上、不回话）时，一次阻塞调用要
 * 60 + 60 秒才失败。一轮对话开头先改写、再识别意图，两次加起来约 4 分钟，这期间一直占着并发名额和会话的「正在回答」标记。正常情况下这两步
 * 本地小模型一两秒、命令行模型几秒就该回来，等 15 秒还没回来，再等下去也没有意义。
 *
 * <p><b>怎么做</b>：读超时是 HTTP 客户端级别的，Spring AI 没有「这一次调用单独设超时」的选项，而且三种模型（Ollama 的 RestClient、OpenAI
 * 官方 SDK、命令行进程）各有各的超时。所以在外面套一层和厂商无关的时限：把这次调用放到一个虚拟线程上跑，调用方 {@code future.get(时限)}；
 * 到点就 {@code cancel(true)} 打断那个线程。实测（Ollama + 一个只收不回的假服务）：打断后阻塞的 HTTP 调用立刻以 {@code InterruptedException}
 * 结束，也不会再重试——所以这里「不重试」不用另外配置，时限把两次尝试一起罩住了。
 *
 * <p>换了线程，两样跟着线程走的东西要带过去：这一轮的账本（{@link TurnRecord}，Token 记账的 advisor 靠它找到这一轮）和日志的 MDC
 * （traceId）。
 *
 * <p>时限设为 0 或负数 = 不设时限，直接在调用方的线程上跑（前几章手工组装的测试用 {@link #none()}，行为和原来完全一样）。
 */
@Component
public class ModelDeadline {

    private static final Logger log = LoggerFactory.getLogger(ModelDeadline.class);

    /** 每次调用一个虚拟线程：等模型时线程是挂起的，不占平台线程。 */
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final Duration timeout;

    /**
     * 创建（Spring 注入）。
     *
     * @param timeout {@code assistant.guard.short-call-timeout}：短调用最多等多久；0 = 不设时限
     */
    @Autowired
    public ModelDeadline(@Value("${assistant.guard.short-call-timeout:15s}") Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * 不设时限（手工组装对象的测试用）。
     *
     * @return 直接在调用方线程上执行的实现
     */
    public static ModelDeadline none() {
        return new ModelDeadline(Duration.ZERO);
    }

    /** 时限；0 或负数表示不设。 */
    public Duration timeout() {
        return timeout;
    }

    /**
     * 在时限内执行一次模型调用。
     *
     * <p>{@code call} 里抛出的异常原样抛给调用方（意图识别靠 {@code JacksonException} 区分「输出解析不了」和「模型调不通」，不能在这里改掉类型）。
     *
     * @param stage 这是哪一步（日志用），如 {@code "intent"}、{@code "condense"}
     * @param call 真正的调用
     * @param <T> 结果类型
     * @return {@code call} 的结果
     * @throws ModelCallException 到时限还没回来（{@link ModelFailure#MODEL_TIMEOUT}）
     */
    public <T> T call(String stage, Supplier<T> call) {
        if (timeout.isZero() || timeout.isNegative()) {
            return call.get();
        }
        TurnRecord record = TurnRecord.current();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        Future<T> future = EXECUTOR.submit(() -> {
            if (mdc != null) {
                MDC.setContextMap(mdc);
            }
            // 没有账本（调试接口直接调意图识别）时不绑定；UsageAdvisor 找不到账本就不记
            TurnRecord.Binding bound = record == null ? () -> {} : record.bind();
            try (bound) {
                return call.get();
            } finally {
                MDC.clear();
            }
        });
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("模型短调用（{}）等了 {} 还没回来，放弃", stage, timeout);
            throw new ModelCallException(ModelFailure.MODEL_TIMEOUT, e);
        } catch (InterruptedException e) {
            // 调用方自己被打断（比如外层也有时限）：把里面的调用一起停掉，保留打断标记
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ModelCallException(ModelFailure.MODEL_UNAVAILABLE, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new ModelCallException(ModelFailure.MODEL_UNAVAILABLE, cause);
        }
    }
}
