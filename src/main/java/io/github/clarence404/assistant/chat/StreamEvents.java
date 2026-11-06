package io.github.clarence404.assistant.chat;

/**
 * 流式接口 {@code POST /api/chat/stream} 推给前端的 SSE 事件，data 都是 JSON。
 *
 * <ul>
 *   <li>{@code event: intent}，data 为 {@code IntentResult}：第 2 章新增，最先推送，告诉前端识别出的意图；只认 token / done / error
 *       的旧前端忽略它即可
 *   <li>{@code event: tool}，data 为 {@code ToolInvocation}：第 3 章新增，0 到多个，在它之后的文字之前推送，告诉前端调用了哪个工具、
 *       参数和结果；不认识它的前端同样可以忽略
 *   <li>{@code event: token}，data 为 {@link Token}：一段增量文本，前端按顺序拼接
 *   <li>{@code event: done}，data 为 {@link Done}：回答结束，之后连接关闭
 *   <li>{@code event: error}，data 为 {@link Error}：出错，之后连接关闭，不再有 done
 * </ul>
 *
 * <p>顺序：{@code intent → tool* → token* → done | error}（第 3 章起 tool 也可能夹在 token 之间：模型先说了几句再调工具时）。
 *
 * <p>data 用 JSON 而不是纯文本：增量文本里常有换行和前导空格，纯文本 SSE 会把换行拆成多行 data、 把行首空格吃掉。
 *
 * <p>所在层：接口层的协议定义（第 1 章引入）。{@link ChatController#stream} 按这里的事件名和 data 类型组装事件，
 * {@code static/index.html} 按同样的事件名解析。SSE（Server-Sent Events）是浏览器原生支持的「服务端单向推送」格式：响应类型为
 * {@code text/event-stream}，每条事件由 {@code event:} 和 {@code data:} 若干行组成、以空行结束。用显式的 done / error
 * 收尾，而不是靠「连接断了」判断结束，前端才分得清「正常说完」「出错」「网络中断」三种情况。
 */
public final class StreamEvents {

    public static final String INTENT = "intent";
    public static final String TOOL = "tool";
    public static final String TOKEN = "token";
    public static final String DONE = "done";
    public static final String ERROR = "error";

    private StreamEvents() {}

    /**
     * 一段增量文本。
     *
     * @param text 模型新生成的一小段文字（通常是一个到几个 token），前端追加到气泡末尾
     */
    public record Token(String text) {}

    /**
     * 正常结束。
     *
     * @param reason 结束原因，目前只有 {@code stop}（模型自然说完）
     */
    public record Done(String reason) {

        /** 模型自然说完。 */
        public static Done stop() {
            return new Done("stop");
        }
    }

    /**
     * 出错结束：code 与阻塞接口 ProblemDetail 里的 code 一致。
     *
     * @param code 错误码，取 {@code ModelFailure} 的 name()，如 MODEL_TIMEOUT
     * @param message 给用户看的提示，不含内部细节
     */
    public record Error(String code, String message) {}
}
