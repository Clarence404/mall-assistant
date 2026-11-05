package io.github.clarence404.assistant.chat;

/**
 * 流式接口 {@code POST /api/chat/stream} 推给前端的三种 SSE 事件，data 都是 JSON。
 *
 * <ul>
 *   <li>{@code event: token}，data 为 {@link Token}：一段增量文本，前端按顺序拼接
 *   <li>{@code event: done}，data 为 {@link Done}：回答结束，之后连接关闭
 *   <li>{@code event: error}，data 为 {@link Error}：出错，之后连接关闭，不再有 done
 * </ul>
 *
 * <p>data 用 JSON 而不是纯文本：增量文本里常有换行和前导空格，纯文本 SSE 会把换行拆成多行 data、 把行首空格吃掉。
 */
public final class StreamEvents {

    public static final String TOKEN = "token";
    public static final String DONE = "done";
    public static final String ERROR = "error";

    private StreamEvents() {}

    /** 一段增量文本。 */
    public record Token(String text) {}

    /** 正常结束。 */
    public record Done(String reason) {

        public static Done stop() {
            return new Done("stop");
        }
    }

    /** 出错结束：code 与阻塞接口 ProblemDetail 里的 code 一致。 */
    public record Error(String code, String message) {}
}
