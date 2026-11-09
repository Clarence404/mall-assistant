package io.github.clarence404.assistant.knowledge;

import org.springframework.http.HttpStatus;

/**
 * 知识库接口失败的几类原因，name() 即返回给前端的错误码（第 4 章引入）。
 *
 * <p>所在层：错误处理。{@link KnowledgeException} 持有它；{@code error.ApiExceptionHandler} 按它填 ProblemDetail 的状态码、title 与
 * code，和第 1 章的 MODEL_UNAVAILABLE / MODEL_TIMEOUT 是同一套格式。
 */
public enum KnowledgeFailure {
    /** 没配置向量模型、连不上、模型没 pull、调用报错。下游的问题，稍后重试可能就好了，所以是 503。 */
    EMBEDDING_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "向量模型暂时不可用"),
    /** 向量库（pgvector）连不上、SQL 报错。同样是下游的问题，503。 */
    VECTOR_STORE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "向量库暂时不可用"),
    /**
     * 库里已有的向量和当前模型对不上（维度不同，或元数据里记的是别的模型）。不重建就一直错，重试没用，所以是 409 Conflict：
     * 请求本身没问题，和服务端现有的数据冲突了。
     */
    INDEX_MODEL_MISMATCH(HttpStatus.CONFLICT, "知识库需要重建");

    private final HttpStatus status;
    private final String title;

    KnowledgeFailure(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    /** HTTP 状态码。 */
    public HttpStatus status() {
        return status;
    }

    /** ProblemDetail 的 title。 */
    public String title() {
        return title;
    }
}
