package io.github.clarence404.assistant.error;

/** 调用模型失败的两类原因，name() 即返回给前端的错误码。 */
public enum ModelFailure {
    MODEL_UNAVAILABLE("模型服务暂时不可用", "智能客服暂时无法连接模型服务，请稍后再试。"),
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
