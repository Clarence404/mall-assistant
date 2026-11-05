package io.github.clarence404.assistant.error;

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
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

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
}
