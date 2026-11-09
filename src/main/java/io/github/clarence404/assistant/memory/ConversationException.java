package io.github.clarence404.assistant.memory;

import org.springframework.http.HttpStatus;

/**
 * 会话相关的失败（第 6 章引入）：会话不存在、正在回答上一条、登录失效、无法确认归属、记忆库不可用。
 *
 * <p>所在层：业务层的异常。{@link ConversationMemory} 抛出；接口层的 {@code ApiExceptionHandler} 转成对应状态码的 ProblemDetail，
 * 流式接口转成 error 事件。对话接口遇到「无法确认归属」「记忆库不可用」时不抛它，而是降级成单轮回答（见 {@link ConversationMemory#open}）。
 */
public class ConversationException extends RuntimeException {

    private final Failure failure;

    /**
     * 创建。
     *
     * @param failure 失败类型
     * @param cause 原始异常，可以为 null；只写日志，不给前端
     */
    public ConversationException(Failure failure, Throwable cause) {
        super(failure.message(), cause);
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

    /** 失败类型：错误码就是 name()，和 HTTP 状态码、给用户看的提示一一对应。 */
    public enum Failure {
        /**
         * 404：会话不存在、已过期，或者<b>不是你的</b>。三种情况故意返回一样的结果：不让人借「403 和 404 的差别」探测某个会话 ID 有没有人在用
         * （和商城对别人的订单也返回 404 是一个道理）。
         */
        CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND, "会话不存在", "会话不存在或已过期。"),
        /** 409：同一个会话的上一条消息还没回答完。见 {@link ConversationMemory} 的并发说明。 */
        CONVERSATION_BUSY(HttpStatus.CONFLICT, "会话正忙", "上一条消息还在回答中，请等它说完再发。"),
        /** 401：带了商城令牌，但商城说它无效或已过期。 */
        LOGIN_EXPIRED(HttpStatus.UNAUTHORIZED, "登录已失效", "商城登录已失效，请重新登录。"),
        /** 503：带了商城令牌，但商城暂时不可用，确认不了它属于谁。 */
        OWNER_UNVERIFIED(HttpStatus.SERVICE_UNAVAILABLE, "无法确认会话归属", "商城服务暂时不可用，确认不了登录身份，请稍后再试。"),
        /** 503：记忆库（jdbc 模式的数据库）连不上。 */
        MEMORY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "记忆库不可用", "对话记录暂时读写不了，请稍后再试。");

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
