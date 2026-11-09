package io.github.clarence404.assistant.agent;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 售后 Agent 的配置，前缀 {@code assistant.agent}（第 7 章引入）。每一项在 {@code application.yml} 里都有注释。
 *
 * <p>所在层：业务层的配置。由 {@code @ConfigurationPropertiesScan} 绑定，注入 {@link AgentService}、{@link AgentLoop}、
 * {@link ConfirmationService} 等。
 *
 * <p>Agent 和前几章「一问一答」最大的不同是：它会<b>自己连着干好几步</b>（想 → 选工具 → 执行 → 看结果 → 再想……）。所以这里的配置大多是在给这个循环
 * 「装刹车」：最多几步、同一个调用最多重复几次、总共最多多久。没有刹车的 Agent，小模型一犯傻就会无限地查同一笔订单，既慢又费钱。
 *
 * @param enabled 总开关；关掉后「我要退款」这类办事请求和第 5 章一样走 RAG，只回答规则、不办事
 * @param mode 循环的实现方式：{@code loop}（默认，方案 B：自己写的循环，每一步让模型用结构化输出给出下一步动作，所有模型都能用）或 {@code native}
 *     （方案 A：交给 Spring AI 原生的工具调用循环，只有支持工具调用的 ollama / openai 能用；命令行模型自动退回 loop）
 * @param maxSteps 一次办事最多几步（一次「想 + 执行」算一步）；到了还没办完就停下来，告诉用户没办完
 * @param maxSameCalls 同一个工具、同样的参数最多执行几次：小模型常常反复查同一笔订单，超过就不再执行，提醒它换一步
 * @param timeout 一次办事最长多久（所有步骤加起来）；每一步开始前检查，超时就停
 * @param observationMaxChars 每一步的结果（交还给模型看的「观察」）最多多少字：结果太长会挤占上下文，小模型更容易漏看关键字段
 * @param maxOutputTokens 每一步模型最多输出多少个 token（Ollama 的 num_predict）：一步的 JSON 正常一两百个 token；不限的话，小模型在结构化输出里
 *     偶尔会一直吐空白或重复同一句，直到读超时（录制时 1.5b 就卡过 60 秒），限住了最多是这一步「输出看不懂」，循环照常往下走
 * @param confirmationTtl 待确认操作的有效期：生成之后多久内点确认才算数；过期要重新说一次，防止用户很久以后误点到一张旧卡片
 * @param prompt 循环模式的系统提示词，对应 {@code prompts/<prompt>.st}
 * @param nativePrompt 原生工具调用模式的系统提示词，对应 {@code prompts/<nativePrompt>.st}
 */
@ConfigurationProperties("assistant.agent")
public record AgentProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("loop") Mode mode,
        @DefaultValue("6") int maxSteps,
        @DefaultValue("2") int maxSameCalls,
        @DefaultValue("120s") Duration timeout,
        @DefaultValue("1200") int observationMaxChars,
        @DefaultValue("400") int maxOutputTokens,
        @DefaultValue("5m") Duration confirmationTtl,
        @DefaultValue("agent-v1") String prompt,
        @DefaultValue("agent-native-v1") String nativePrompt) {

    /** 启动时就校验，配错了立刻报错。 */
    public AgentProperties {
        if (maxSteps < 1 || maxSameCalls < 1) {
            throw new IllegalArgumentException(
                    "assistant.agent.max-steps / max-same-calls 至少为 1，当前 " + maxSteps + " / " + maxSameCalls);
        }
        if (observationMaxChars < 200) {
            throw new IllegalArgumentException(
                    "assistant.agent.observation-max-chars 不能小于 200，当前 " + observationMaxChars);
        }
    }

    /**
     * 测试用：默认值，只改模式。
     *
     * @param mode 循环方式
     * @return 配置
     */
    public static AgentProperties defaults(Mode mode) {
        return new AgentProperties(
                true,
                mode,
                6,
                2,
                Duration.ofSeconds(120),
                1200,
                400,
                Duration.ofMinutes(5),
                "agent-v1",
                "agent-native-v1");
    }

    /** 循环的实现方式，取舍见 docs/ch07.md「方案 A 还是方案 B」。 */
    public enum Mode {
        /** 方案 B：自己写的循环，每一步一次结构化输出（{@link AgentLoop}）。 */
        LOOP,
        /** 方案 A：Spring AI 原生工具调用循环（{@link NativeAgentRunner}）。 */
        NATIVE
    }
}
