package io.github.clarence404.assistant.error;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * 调用模型失败：把各种底层异常（连接拒绝、读超时、HTTP 5xx……）归成 {@link ModelFailure} 的两类。
 *
 * <p>所在层：错误处理（第 1 章引入）。由业务层（{@code ChatService}、{@code IntentService}）在调用模型出错时创建；阻塞接口由
 * {@link ApiExceptionHandler} 转成 ProblemDetail，流式接口由 {@code ChatController} 转成 error 事件。
 *
 * <p>为什么要归类：不同厂商、阻塞与流式、不同 HTTP 客户端抛出的异常五花八门，前端和监控只关心「连不上」还是「太慢」，归成两类后
 * 两个接口返回同一套错误码，也不会把底层异常信息（内部地址、堆栈）漏给用户。
 */
public class ModelCallException extends RuntimeException {

    private static final int MAX_CAUSE_DEPTH = 16;

    private final ModelFailure failure;

    /**
     * 包装一个已经知道分类的失败。
     *
     * @param failure 失败分类，决定错误码与给用户的提示
     * @param cause 原始异常，只用于日志排查
     */
    public ModelCallException(ModelFailure failure, Throwable cause) {
        super(failure.title(), cause);
        this.failure = failure;
    }

    /** 失败分类。 */
    public ModelFailure failure() {
        return failure;
    }

    /**
     * 把调用模型时抛出的任意异常归类：沿 cause 链找到超时类异常记为 {@link ModelFailure#MODEL_TIMEOUT}，其余一律记为
     * {@link ModelFailure#MODEL_UNAVAILABLE}；已经归过类的原样返回。
     *
     * <p>「原样返回」保证多层都调用 {@code from} 时（如 ChatService 里 {@code onErrorMap} 一次、ChatController 里又一次）不会套娃。
     *
     * @param error 任意异常
     * @return 归好类的异常，从不返回 null
     */
    public static ModelCallException from(Throwable error) {
        if (error instanceof ModelCallException alreadyClassified) {
            return alreadyClassified;
        }
        ModelFailure failure = isTimeout(error) ? ModelFailure.MODEL_TIMEOUT : ModelFailure.MODEL_UNAVAILABLE;
        return new ModelCallException(failure, error);
    }

    /**
     * 沿 cause 链（最多 16 层，防止循环引用）找超时异常：框架常把真正的原因包好几层，只看最外层会误判。
     * 命令行模型的 {@code CliTimeoutException} 类名同样以 TimeoutException 结尾，也会被认成超时。
     */
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
