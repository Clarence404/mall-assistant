package io.github.clarence404.assistant.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 工具调用的开关，前缀 {@code assistant.tools}（注意不是 Spring AI 自己的 {@code spring.ai.tools.*}）。
 *
 * <p>所在层：业务层的配置（第 3 章引入）。由 {@code @ConfigurationPropertiesScan} 绑定，注入 {@code ChatService}。
 *
 * <p>两种让模型用上商城数据的方式：
 *
 * <ul>
 *   <li><b>原生工具调用</b>（{@code native-calling}）：把工具清单交给模型，模型自己决定调不调、参数填什么。灵活，但要模型支持
 *       （Ollama 的 qwen2.5、OpenAI 兼容接口的大多数模型都支持），而且多一次模型调用；小模型常常该调不调、参数抄错
 *   <li><b>按意图直接查询</b>（{@code direct-lookup}）：第 2 章的意图识别已经抽出了订单号 / SKU，代码直接调工具，把结果作为
 *       「查询结果」放进提示词，模型只负责把结果说成人话。确定、省一次模型调用，对小模型和不支持工具调用的模型也有效
 * </ul>
 *
 * 两者可以同时开：能直接查的先直接查（这次就不再给模型注册工具），查不了的（比如意图识别漏了订单号）再交给模型自己决定。
 *
 * @param nativeCalling 是否把工具注册给模型。命令行模型（claude-cli / codex-cli profile）设为 false：{@code CliChatModel}
 *     只把模型当纯文本用，不支持工具调用（它的选项也不是 {@code ToolCallingChatOptions}，就算注册了 Spring AI 也会悄悄丢掉）
 * @param directLookup 意图是查订单且抽到了订单号、或问商品且抽到了 SKU 时，是否由代码直接查询
 */
@ConfigurationProperties("assistant.tools")
public record ToolProperties(
        @DefaultValue("true") boolean nativeCalling,
        @DefaultValue("true") boolean directLookup) {}
