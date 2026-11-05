package io.github.clarence404.assistant.error;

import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 阻塞接口的统一错误出口：模型调用失败返回 503 + ProblemDetail（RFC 9457），带 {@code code} 字段，不带堆栈。
 *
 * <p>参数校验失败（400）交给 Spring MVC 自带的 ProblemDetail 处理（{@code spring.mvc.problemdetails.enabled=true}）。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    // TODO ch01-5：加一个处理 ModelCallException 的 @ExceptionHandler 方法，返回 ResponseEntity<ProblemDetail>：
    //  - 状态码 503 Service Unavailable，响应头 Retry-After: 5
    //  - ProblemDetail 的 detail 用 failure.message()，title 用 failure.title()，再加一个属性 code = failure.name()
    //  - 异常细节（含 cause）只打到日志（warn），不要放进响应体
    //  验收：ChatApiTest.modelUnavailableBecomes503ProblemDetail / modelTimeoutBecomes503WithTimeoutCode
}
