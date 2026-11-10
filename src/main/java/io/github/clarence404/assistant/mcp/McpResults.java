package io.github.clarence404.assistant.mcp;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * 把工具的返回值做成 MCP 的工具结果：转 JSON、超长截断、出错时标 {@code isError}（第 8 章引入）。
 *
 * <p>所在层：MCP 的输出。{@link MallMcpTools} 的每个工具方法都返回 {@link CallToolResult}，而不是让 Spring AI 自动把返回值转 JSON：
 * 自动转换不截断；出异常时还会把异常信息和根因原样拼进结果（见 {@link McpRejection}），我们要自己掌控「对面能看到什么」。
 *
 * <p>结果用 {@code text} 内容放 JSON 字符串：MCP 客户端几乎都把 text 原样交给模型，模型读 JSON 没有问题；结构化输出（{@code structuredContent}
 * + 输出 Schema）是 2025-06 版协议才加的，很多客户端还不认。
 */
public final class McpResults {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 截断时追加的说明：让对面的模型知道后面还有，别当成完整的结果。 */
    public static final String TRUNCATED = "…（结果太长，已截断）";

    private McpResults() {}

    /**
     * 成功的结果：值转成 JSON，超过 {@code maxChars} 截断。
     *
     * @param value 返回值（record）
     * @param maxChars 最多多少字
     * @return 工具结果
     */
    public static CallToolResult ok(Object value, int maxChars) {
        String json = value instanceof String s ? s : JSON.writeValueAsString(value);
        return CallToolResult.builder()
                .addTextContent(limit(json, maxChars))
                .isError(false)
                .build();
    }

    /**
     * 失败的结果（{@code isError=true}）：没登录、太频繁、参数不对等，消息是固定话术。
     *
     * @param message 给对面模型看的原因
     * @return 工具结果
     */
    public static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    /**
     * 超长截断。
     *
     * @param text 原文
     * @param maxChars 最多多少字
     * @return 不超过 {@code maxChars + 截断说明} 的文字
     */
    public static String limit(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + TRUNCATED;
    }
}
