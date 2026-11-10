package io.github.clarence404.assistant.mcp;

/**
 * MCP 工具调用被拦下：没登录、登录过期、调得太频繁等（第 8 章引入）。
 *
 * <p>所在层：MCP 的错误。{@link McpGate#enter} 抛出，{@link MallMcpTools} 接住后翻成 {@code isError=true} 的工具结果，消息原样给对面的模型看。
 *
 * <p><b>为什么翻成工具结果、而不是 MCP 协议错误</b>：MCP 有两种失败。协议错误（JSON-RPC error）表示「这次调用本身坏了」（方法不存在、参数不是 JSON），
 * 客户端多半直接报错给人看；工具结果里的 {@code isError=true} 表示「工具执行了，但没办成」，会交还给模型，模型能据此告诉用户「请先登录」。
 * 没登录、太频繁属于后者。还有一层：Spring AI 把工具方法抛出的异常转成结果时，会把异常信息（含根因）原样拼进结果，
 * 自己接住再写固定话术，才能保证不会把内部细节漏给对面。
 */
public class McpRejection extends RuntimeException {

    /**
     * 创建。
     *
     * @param message 给对面模型看的原因（固定话术，不含任何令牌、内部地址）
     */
    public McpRejection(String message) {
        super(message, null, false, false);
    }
}
