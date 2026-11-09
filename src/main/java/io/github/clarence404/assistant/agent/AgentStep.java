package io.github.clarence404.assistant.agent;

/**
 * Agent 的一步：想了什么、选了哪个工具、参数、结果、耗时（第 7 章引入）。
 *
 * <p>所在层：接口层的数据。{@link AgentLoop} / {@link NativeAgentRunner} 每走一步生成一个；阻塞接口放在响应的 {@code agent.steps} 里，
 * 流式接口作为 {@code step} 事件<b>边走边推</b>（页面能看着 Agent 一步一步干活，而不是干等十几秒）；页面调试面板「Agent 步骤」一栏显示。
 * 下一步给模型看的「办事记录」也是用它拼出来的（{@link AgentPlanner}）。
 *
 * @param index 第几步，从 1 开始
 * @param thought 模型这一步的想法（思考摘要）；原生工具调用模式下拿不到，为 null
 * @param action 工具名（如 queryOrder、finish）；模型输出看不懂时是它填的原样文字或 null
 * @param kind 这一步的结果类型
 * @param arguments 参数 JSON；无效时为 {@code {}}
 * @param observation 结果：只读工具的查询结果（JSON）、写操作的「已生成待确认操作」、被代码拦下的原因，或 finish 的回答
 * @param durationMs 这一步的耗时（模型想 + 执行），毫秒
 */
public record AgentStep(
        int index, String thought, String action, Kind kind, String arguments, String observation, long durationMs) {

    /** 一步的结果类型。 */
    public enum Kind {
        /** 执行了只读工具。 */
        READ,
        /** 提出了写操作：已生成待确认操作，等用户点确认（不执行）。 */
        PROPOSE,
        /** 被代码拦下：输出看不懂、工具不存在、参数不对、重复调用、写操作校验不过。结果里写着原因，交还给模型改正。 */
        REJECTED,
        /** 办完了（或办不了），给用户回答。 */
        FINISH
    }
}
