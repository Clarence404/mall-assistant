package io.github.clarence404.assistant.error;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/** 调用模型失败：把各种底层异常（连接拒绝、读超时、HTTP 5xx……）归成 {@link ModelFailure} 的两类。 */
public class ModelCallException extends RuntimeException {

    private static final int MAX_CAUSE_DEPTH = 16;

    private final ModelFailure failure;

    public ModelCallException(ModelFailure failure, Throwable cause) {
        super(failure.title(), cause);
        this.failure = failure;
    }

    public ModelFailure failure() {
        return failure;
    }

    /**
     * 把调用模型时抛出的任意异常归类：沿 cause 链找到超时类异常记为 {@link ModelFailure#MODEL_TIMEOUT}，其余一律记为
     * {@link ModelFailure#MODEL_UNAVAILABLE}；已经归过类的原样返回。
     */
    public static ModelCallException from(Throwable error) {
        if (error instanceof ModelCallException alreadyClassified) {
            return alreadyClassified;
        }
        ModelFailure failure = isTimeout(error) ? ModelFailure.MODEL_TIMEOUT : ModelFailure.MODEL_UNAVAILABLE;
        return new ModelCallException(failure, error);
    }

    private static boolean isTimeout(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof TimeoutException // Reactor 的 Flux.timeout
                    || current instanceof SocketTimeoutException // RestClient 阻塞调用读超时
                    || current instanceof HttpTimeoutException // JDK HttpClient
                    // Netty 的 ReadTimeoutException 等第三方超时异常，按类名识别，避免编译期依赖它们
                    || current.getClass().getSimpleName().endsWith("TimeoutException")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
