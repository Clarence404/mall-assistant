package io.github.clarence404.assistant.agent;

import java.util.List;

/**
 * 一次办事的调试信息：用的哪种循环、走了哪些步、为什么停、总耗时（第 7 章引入）。
 *
 * <p>所在层：接口层的数据。{@link AgentResult#trace()} 生成，阻塞接口放在响应的 {@code agent} 字段（不走 Agent 时为 null），页面调试面板
 * 「Agent 步骤」一栏显示。流式接口不发它：步骤已经一条条作为 {@code step} 事件推过了。
 *
 * @param mode 循环方式：{@code loop} 或 {@code native}
 * @param steps 每一步，按顺序
 * @param stopReason 为什么停
 * @param elapsedMs 总耗时，毫秒
 */
public record AgentTrace(String mode, List<AgentStep> steps, StopReason stopReason, long elapsedMs) {

    /** 循环为什么停。 */
    public enum StopReason {
        /** 模型说办完了（finish），回答是它给的。 */
        FINISHED,
        /** 提出了写操作，等用户点确认：这一轮到此为止，回答是代码写好的固定话术。 */
        AWAITING_CONFIRMATION,
        /** 到了步数上限还没办完。 */
        STEP_LIMIT,
        /** 反复做同一个调用（被拦下后还在重复）。 */
        REPEATED,
        /** 超过总时长。 */
        TIMEOUT,
        /** 页面关了（流式被取消）。 */
        CANCELLED,
        /** 商城登录失效，没法确认是谁在办。 */
        LOGIN_EXPIRED
    }
}
