package io.github.clarence404.assistant.agent;

import org.springframework.http.HttpStatus;

/**
 * 确认接口的失败（第 7 章引入）：确认 ID 不存在 / 不是你的、已过期、已经做过相反的决定。
 *
 * <p>所在层：业务层的异常。{@link ConfirmationService#decide} 抛出；{@code ApiExceptionHandler} 转成对应状态码的 ProblemDetail，带 code。
 */
public class ConfirmationException extends RuntimeException {

    private final Failure failure;

    /**
     * 创建。
     *
     * @param failure 失败类型
     */
    public ConfirmationException(Failure failure) {
        super(failure.message());
        this.failure = failure;
    }

    /**
     * 失败类型。
     *
     * @return 失败类型
     */
    public Failure failure() {
        return failure;
    }

    /** 失败类型：错误码就是 name()。 */
    public enum Failure {
        /**
         * 404：确认 ID 不存在、不是你的、或不是这个会话的。三种情况故意一样（和第 6 章别人的会话返回 404 一个道理）：不让人借响应的差别
         * 探测某个确认 ID 存不存在。
         */
        CONFIRMATION_NOT_FOUND(HttpStatus.NOT_FOUND, "确认不存在", "这个确认不存在或已失效，请重新说一下要办的事。"),
        /** 410：过了有效期。不自动执行，也不能再确认，要重新说一次（订单状态可能已经变了）。 */
        CONFIRMATION_EXPIRED(HttpStatus.GONE, "确认已过期", "这个确认已经过期，没有执行。请重新说一下要办的事。"),
        /** 409：已经做过相反的决定（确认过又点取消、取消过又点确认）。同样的决定重复提交不报错，返回原来的结果。 */
        CONFIRMATION_ALREADY_DECIDED(HttpStatus.CONFLICT, "已经处理过", "这个确认已经处理过了，不能改。"),
        /**
         * 409（第 10 章）：同一个确认正在被另一个请求（可能在另一个实例上）执行，等了一会儿还没有结果。不再执行一次——
         * 写操作「最多执行一次」：过一会儿刷新看结果，商城那边以「我的订单」为准。
         */
        CONFIRMATION_IN_PROGRESS(HttpStatus.CONFLICT, "正在处理", "这个确认正在处理中，请稍后到「我的订单」里看一下结果，不要重复点。"),
        /** 503（第 10 章）：确认存在 Redis 里，而 Redis 暂时用不了。写操作不「出错就放行」：这次办不了，稍后再说一次。 */
        CONFIRMATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "确认服务暂时不可用", "确认服务暂时不可用，这次没有执行，请稍后再说一次。");

        private final HttpStatus status;
        private final String title;
        private final String message;

        Failure(HttpStatus status, String title, String message) {
            this.status = status;
            this.title = title;
            this.message = message;
        }

        /** HTTP 状态码。 */
        public HttpStatus status() {
            return status;
        }

        /** ProblemDetail 的标题。 */
        public String title() {
            return title;
        }

        /** 给用户看的提示。 */
        public String message() {
            return message;
        }
    }
}
