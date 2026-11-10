package io.github.clarence404.assistant.mcp.client;

import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 客户端一侧的装配：不把 ToolContext 发给外部服务（第 8 章引入）。
 *
 * <p>所在层：配置。Spring AI MCP Client Starter 自动配置远程工具时，用 {@link ToolContextToMcpMetaConverter} 决定「调用外部工具时，把 ToolContext 里的什么
 * 作为 {@code _meta} 一起发过去」，<b>默认是全部</b>（{@link ToolContextToMcpMetaConverter#defaultConverter()}，只去掉 MCP 自己的 exchange）。
 * 而第 3 章起，我们的 ToolContext 里放的正是商城令牌（{@code MallTools.MALL_TOKEN}）——不改的话，模型一调外部工具，用户的令牌就发给了别人的服务器。
 *
 * <p>这里提供一个什么都不发的转换器（自动配置用 {@code getIfUnique} 取它，有了就不用默认的）。{@link ExternalMcpTools.Guarded} 调用时也不传 ToolContext，两道保险。
 * 这个坑在 Spring AI 2.0.2 的文档里没有提，是读 {@code SyncMcpToolCallback#call} 的源码时发现的。
 */
@Configuration(proxyBeanMethods = false)
public class McpClientConfig {

    /**
     * 调外部 MCP 工具时不带任何 {@code _meta}。
     *
     * @return 永远返回空 Map 的转换器
     */
    @Bean
    public ToolContextToMcpMetaConverter mcpToolContextConverter() {
        return ToolContextToMcpMetaConverter.noOp();
    }
}
