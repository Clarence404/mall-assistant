package io.github.clarence404.assistant.mall;

/**
 * 一次商城接口调用的结果：成功时有 body，失败时有失败类别和商城返回的错误码。
 *
 * <p>所在层：外部系统接入（第 3 章引入）。{@link MallApiClient} 的每个方法都返回它，而不是抛异常：调用方（工具）总要把失败翻译成
 * 「给模型看的结构化结果」，用返回值比层层 try/catch 直观，也不会漏掉某种失败。
 *
 * @param body 成功时的响应体；失败时为 null
 * @param failure 失败类别；成功时为 null
 * @param code 商城 ProblemDetail 里的业务码（如 ORDER_NOT_FOUND）；成功、或响应体里没有时为 null
 * @param detail 商城 ProblemDetail 里的 detail，只用于日志；可能为 null
 * @param <T> 响应体类型
 */
public record MallResult<T>(T body, MallFailure failure, String code, String detail) {

    /** 成功。 */
    public static <T> MallResult<T> ok(T body) {
        return new MallResult<>(body, null, null, null);
    }

    /** 失败。 */
    public static <T> MallResult<T> failed(MallFailure failure, String code, String detail) {
        return new MallResult<>(null, failure, code, detail);
    }

    /** 是否成功（2xx 且有响应体）。 */
    public boolean isOk() {
        return failure == null;
    }
}
