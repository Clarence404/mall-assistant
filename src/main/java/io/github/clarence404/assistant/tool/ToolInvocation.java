package io.github.clarence404.assistant.tool;

/**
 * 一次工具调用的记录：调了哪个工具、参数、结果、耗时、是谁决定调的。给页面的调试面板看，也给测试断言。
 *
 * <p>所在层：工具（第 3 章引入）。由 {@link ToolCallRecorder} 生成；阻塞接口放在响应的 {@code tools} 字段，流式接口作为
 * {@code tool} 事件推送。里面<b>没有</b>令牌：令牌在 ToolContext 里，不是工具参数。
 *
 * @param name 工具名，如 queryOrderStatus
 * @param source 谁决定调用的：模型自己（原生工具调用），还是代码按意图直接查询
 * @param arguments 参数，JSON 字符串，如 {@code {"orderNo":"2118..."}}；模型调用时就是模型生成的原样参数
 * @param result 工具返回给模型的 JSON（工具结果消息的内容）；执行抛异常时为异常说明
 * @param durationMs 执行耗时，毫秒（主要是调商城接口的时间）
 */
public record ToolInvocation(String name, Source source, String arguments, String result, long durationMs) {

    /** 谁决定调用这个工具。 */
    public enum Source {
        /** 模型在回答过程中自己决定调用（原生工具调用）。 */
        MODEL,
        /** 代码按意图识别的结果直接调用，再把结果放进提示词（「按意图直接查询」）。 */
        DIRECT
    }
}
