package io.github.clarence404.assistant.error;

/**
 * 调用模型失败的原因，name() 即返回给前端的错误码：第 1 章的两类（不可用、超时），第 9 章加了熔断中。
 *
 * <p>所在层：错误处理（第 1 章引入）。{@link ModelCallException} 持有它；{@link ApiExceptionHandler} 用 title / message 填
 * ProblemDetail，{@code ChatController} 用 name() / message 填流式 error 事件。
 */
public enum ModelFailure {
    /** 连不上、模型服务报错（HTTP 5xx、模型不存在等）：除超时以外的一切失败。 */
    MODEL_UNAVAILABLE("模型服务暂时不可用", "智能客服暂时无法连接模型服务，请稍后再试。"),
    /** 超时：阻塞调用读超时、流式调用两段之间间隔太久，或命令行模型进程超时被杀。 */
    MODEL_TIMEOUT("模型响应超时", "智能客服响应超时，请稍后再试或换个简短的问题。"),
    /**
     * 熔断中（第 9 章）：最近连续失败太多次，暂时不再调模型、直接返回，等熔断时间过了再放一个请求去试（见 {@code guard.ModelCircuitBreaker}）。
     * 不是又一次失败，而是「已经知道它挂了，不让用户再等一个超时」。
     */
    MODEL_CIRCUIT_OPEN("模型服务熔断中", "智能客服暂时连不上模型服务，已暂停调用、稍后自动恢复，请过半分钟再试。");

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
