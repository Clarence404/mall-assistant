package io.github.clarence404.assistant.guard;

import java.time.Duration;
import org.springframework.http.HttpStatus;

/**
 * 对话请求在调模型之前就被挡下：太频繁、太忙、今天的 Token 用完了（第 9 章引入）。
 *
 * <p>所在层：上线保护。{@link ChatGuard#admit} 抛出；阻塞接口由 {@code ApiExceptionHandler} 转成 ProblemDetail（带 {@code code} 和
 * {@code Retry-After}），流式接口由 {@code ChatController} 转成 {@code error} 事件（和第 6 章「会话正忙」一样，流一开始就是 200，只能用事件告诉页面）。
 * 错误码和第 1 章的 MODEL_* 是同一套用法：页面按 code 分支、按 message 显示。
 */
public class ChatRejection extends RuntimeException {

    /** 被挡的原因，name() 就是错误码。 */
    public enum Reason {
        /** 同一个调用方一分钟内问得太多。 */
        RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "提问太频繁了", "提问太频繁了，请稍等一会儿再问。"),
        /** 整个实例同时在处理的对话太多，等了一会儿也没有空位。 */
        TOO_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "智能客服正忙", "现在咨询的人比较多，请稍等几秒再试。"),
        /** 这个调用方今天的 Token 用量到了上限。 */
        TOKEN_BUDGET_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "今日额度已用完", "今天的智能客服额度已经用完了，明天再来，或联系人工客服。"),
        /**
         * 第 10 章：限流、预算、并发名额放在 Redis 里，而 Redis 暂时用不了、策略是「拒绝」（{@code assistant.state.on-redis-error=reject}）。
         * 503：是我们的问题。策略是「放行」时不会出现这个错误码。
         */
        STATE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "智能客服暂时不可用", "智能客服暂时不可用，请稍后再试。");

        private final HttpStatus status;
        private final String title;
        private final String message;

        Reason(HttpStatus status, String title, String message) {
            this.status = status;
            this.title = title;
            this.message = message;
        }

        /** HTTP 状态码：429 是「你」的问题（太频繁、额度用完），503 是「我们」的问题（忙）。 */
        public HttpStatus status() {
            return status;
        }

        /** ProblemDetail 的 title。 */
        public String title() {
            return title;
        }

        /** 给用户看的提示。 */
        public String message() {
            return message;
        }
    }

    private final Reason reason;
    private final Duration retryAfter;

    /**
     * 创建。
     *
     * @param reason 原因
     * @param retryAfter 建议多久之后再试（填进 {@code Retry-After}），不知道时为 null
     */
    public ChatRejection(Reason reason, Duration retryAfter) {
        super(reason.title());
        this.reason = reason;
        this.retryAfter = retryAfter;
    }

    /** 原因。 */
    public Reason reason() {
        return reason;
    }

    /** 建议多久之后再试；不知道时为 null。 */
    public Duration retryAfter() {
        return retryAfter;
    }
}
