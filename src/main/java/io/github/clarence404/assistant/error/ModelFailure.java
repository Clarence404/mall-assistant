package io.github.clarence404.assistant.error;

/**
 * 调用模型失败的两类原因，name() 即返回给前端的错误码。
 *
 * <p>所在层：错误处理（第 1 章引入）。{@link ModelCallException} 持有它；{@link ApiExceptionHandler} 用 title / message 填
 * ProblemDetail，{@code ChatController} 用 name() / message 填流式 error 事件。
 */
public enum ModelFailure {
    /** 连不上、模型服务报错（HTTP 5xx、模型不存在等）：除超时以外的一切失败。 */
    MODEL_UNAVAILABLE("模型服务暂时不可用", "智能客服暂时无法连接模型服务，请稍后再试。"),
    /** 超时：阻塞调用读超时、流式调用两段之间间隔太久，或命令行模型进程超时被杀。 */
    MODEL_TIMEOUT("模型响应超时", "智能客服响应超时，请稍后再试或换个简短的问题。");

    private final String title;
    private final String message;

    ModelFailure(String title, String message) {
        this.title = title;
        this.message = message;
    }

    /** ProblemDetail 的 title。 */
    public String title() {
        return title;
    }

    /** 给用户看的提示，不含任何内部细节。 */
    public String message() {
        return message;
    }
}
