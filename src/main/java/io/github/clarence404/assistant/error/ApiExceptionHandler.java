package io.github.clarence404.assistant.error;

import io.github.clarence404.assistant.agent.ConfirmationException;
import io.github.clarence404.assistant.guard.ChatRejection;
import io.github.clarence404.assistant.knowledge.KnowledgeException;
import io.github.clarence404.assistant.knowledge.KnowledgeFailure;
import io.github.clarence404.assistant.memory.ConversationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 阻塞接口的统一错误出口：模型调用失败返回 503 + ProblemDetail（RFC 9457），带 {@code code} 字段，不带堆栈。
 *
 * <p>参数校验失败（400）交给 Spring MVC 自带的 ProblemDetail 处理（{@code spring.mvc.problemdetails.enabled=true}）。
 *
 * <p>ProblemDetail 是 HTTP 接口报错的标准 JSON 格式（RFC 9457，{@code Content-Type: application/problem+json}），固定字段有
 * type、title、status、detail、instance，可以加扩展字段。这里加了 {@code code}（MODEL_UNAVAILABLE / MODEL_TIMEOUT），前端按 code
 * 分支、按 detail 显示，和流式接口 error 事件里的 code 是同一套。
 *
 * <p>所在层：错误处理（第 1 章引入）。上游是 Spring MVC：任何 Controller 抛出 {@link ModelCallException} 都会被路由到这里；
 * 流式接口的错误不经过这里（响应已经开始写了），由 {@code ChatController} 转成 error 事件。
 *
 * <p>第 4 章起还处理知识库的 {@link KnowledgeException}：向量模型 / 向量库不可用 503，库里是别的模型生成的向量 409，同样带 code。
 *
 * <p>第 6 章起还处理会话的 {@link ConversationException}：会话不存在 404、正在回答 409、登录失效 401、商城或记忆库不可用 503。
 *
 * <p>第 7 章起还处理确认接口的 {@link ConfirmationException}：确认不存在或不是你的 404、过期 410、已经做过相反的决定 409。
 *
 * <p>第 9 章起还处理对话接口进门时的 {@link ChatRejection}：太频繁、今日额度用完 429，太忙 503，都带 {@code Retry-After}；模型熔断中
 * （MODEL_CIRCUIT_OPEN）和第 1 章的两类一样是 503。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 模型调用失败 → 503 Service Unavailable + ProblemDetail。
     *
     * <p>为什么是 503 而不是 500：问题出在下游的模型服务，不是我们的代码有 bug，稍后重试有可能成功；{@code Retry-After: 5}
     * 告诉客户端过 5 秒再试。
     *
     * @param e 已归类的模型调用异常，原始异常在 cause 里，只写日志
     * @return 503 响应，body 里只有面向用户的提示，没有堆栈和内部地址
     */
    @ExceptionHandler(ModelCallException.class)
    public ResponseEntity<ProblemDetail> handleModelFailure(ModelCallException e) {
        ModelFailure failure = e.failure();
        // 细节只进日志，不给前端
        log.warn("调用模型失败：{}", failure, e.getCause());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, failure.message());
        problem.setTitle(failure.title());
        problem.setProperty("code", failure.name());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem);
    }

    /**
     * 知识库操作失败 → ProblemDetail（第 4 章）：EMBEDDING_UNAVAILABLE / VECTOR_STORE_UNAVAILABLE 是 503（带 Retry-After），
     * INDEX_MODEL_MISMATCH 是 409（重试没用，要重建）。
     *
     * @param e 已归类的知识库异常，detail 是给用户看的说明，原始异常只写日志
     * @return 对应状态码的响应
     */
    @ExceptionHandler(KnowledgeException.class)
    public ResponseEntity<ProblemDetail> handleKnowledgeFailure(KnowledgeException e) {
        KnowledgeFailure failure = e.failure();
        log.warn("知识库操作失败：{} {}", failure, e.getMessage(), e.getCause());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(failure.status(), e.getMessage());
        problem.setTitle(failure.title());
        problem.setProperty("code", failure.name());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(failure.status());
        if (failure.status() == HttpStatus.SERVICE_UNAVAILABLE) {
            response.header(HttpHeaders.RETRY_AFTER, "5");
        }
        return response.body(problem);
    }

    /**
     * 会话操作失败 → ProblemDetail（第 6 章）：状态码与 code 见 {@link ConversationException.Failure}。
     *
     * @param e 会话异常，原始异常（如数据库连不上）只写日志
     * @return 对应状态码的响应；503 带 Retry-After
     */
    @ExceptionHandler(ConversationException.class)
    public ResponseEntity<ProblemDetail> handleConversationFailure(ConversationException e) {
        ConversationException.Failure failure = e.failure();
        log.info("会话操作失败：{}", failure, e.getCause());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(failure.status(), failure.message());
        problem.setTitle(failure.title());
        problem.setProperty("code", failure.name());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(failure.status());
        if (failure.status() == HttpStatus.SERVICE_UNAVAILABLE) {
            response.header(HttpHeaders.RETRY_AFTER, "5");
        }
        return response.body(problem);
    }

    /**
     * 对话请求在进门时被挡下 → ProblemDetail（第 9 章）：429（太频繁、今日额度用完）或 503（太忙），{@code Retry-After} 是建议的等待秒数
     * （HTTP 标准头，客户端和网关都认得）。
     *
     * @param e 被挡下的原因
     * @return 对应状态码的响应
     */
    @ExceptionHandler(ChatRejection.class)
    public ResponseEntity<ProblemDetail> handleRejection(ChatRejection e) {
        ChatRejection.Reason reason = e.reason();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(reason.status(), reason.message());
        problem.setTitle(reason.title());
        problem.setProperty("code", reason.name());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(reason.status());
        if (e.retryAfter() != null) {
            response.header(
                    HttpHeaders.RETRY_AFTER,
                    String.valueOf(Math.max(1, e.retryAfter().toSeconds())));
        }
        return response.body(problem);
    }

    /**
     * 确认接口失败 → ProblemDetail（第 7 章）：不存在或不是你的 404、过期 410、决定冲突 409。
     *
     * @param e 确认异常
     * @return 对应状态码的响应
     */
    @ExceptionHandler(ConfirmationException.class)
    public ResponseEntity<ProblemDetail> handleConfirmationFailure(ConfirmationException e) {
        ConfirmationException.Failure failure = e.failure();
        log.info("确认失败：{}", failure);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(failure.status(), failure.message());
        problem.setTitle(failure.title());
        problem.setProperty("code", failure.name());
        return ResponseEntity.status(failure.status()).body(problem);
    }
}
