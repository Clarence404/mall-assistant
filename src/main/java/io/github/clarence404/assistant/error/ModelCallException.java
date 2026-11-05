package io.github.clarence404.assistant.error;

/** 调用模型失败：把各种底层异常（连接拒绝、读超时、HTTP 5xx……）归成 {@link ModelFailure} 的两类。 */
public class ModelCallException extends RuntimeException {

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
        // TODO ch01-2：实现异常归类。
        //  1. error 本身已经是 ModelCallException 的，原样返回，不要再包一层
        //  2. 沿 getCause() 逐层查找（注意防止无限循环，限制层数即可），任意一层是超时类异常就是 MODEL_TIMEOUT：
        //     java.util.concurrent.TimeoutException（Reactor 的 timeout）、java.net.SocketTimeoutException（阻塞读超时）、
        //     java.net.http.HttpTimeoutException；Netty 的 ReadTimeoutException 可以按类名以 "TimeoutException" 结尾识别
        //  3. 其余一律是 MODEL_UNAVAILABLE；新异常的 cause 要设为原始 error，日志里才看得到根因
        //  验收：ModelCallExceptionTest
        throw new UnsupportedOperationException("TODO ch01-2：实现 ModelCallException#from", error);
    }
}
