package io.github.clarence404.assistant.mcp;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 第 8 章 MCP 的本项目配置，前缀 {@code assistant.mcp}（Spring AI 自己的 MCP 配置在 {@code spring.ai.mcp.*}，见 application.yml）。
 *
 * <p>所在层：MCP 的配置。由 {@code @ConfigurationPropertiesScan} 绑定，注入 {@link McpGate}、{@link McpMallService}、{@link McpServerConfig}
 * 和客户端一侧的 {@code mcp.client.ExternalMcpTools}。
 *
 * <p>MCP Server 把商城能力交给「别人的模型」去调（Claude Desktop、Cursor、MCP Inspector……），我们管不了对面的模型怎么用、调多少次，
 * 所以这里的配置大多是<b>上限</b>：结果最多多长、每分钟最多调几次、哪些网页来源可以连。
 *
 * @param confirmUrl 确认页面的地址（智能客服首页）：写操作只返回确认 ID 和 {@code <confirmUrl>?confirm=<ID>}，用户在这个页面登录同一个账号后点确认
 * @param maxResultChars 每个工具结果最多多少字：结果是要塞进对面模型上下文的，太长既费对面的 token、也让小模型漏看关键字段；超了截断并注明
 * @param callsPerMinute 同一个调用方（登录用户按商城用户 ID，匿名按「匿名」整体）每分钟最多调几次工具：对面的模型可能陷进循环反复调，限住了不至于把商城打满
 * @param allowedOrigins 允许带 {@code Origin} 请求头来连 {@code /mcp} 的网页来源（防 DNS 重绑定：恶意网页让浏览器去连你本机的 MCP Server）；
 *     命令行、桌面客户端不带 Origin，不受影响。支持 {@code http://localhost:*} 这种端口通配
 * @param sessionIdleTimeout MCP 会话多久没有动静就清掉：不少客户端（如 Inspector 的命令行模式）退出时不发 DELETE 结束会话，不清的话会话一直留在内存里，
 *     心跳还会不停地往断开的连接上发（日志里一串「Keep-alive ping ... failed」）。Spring AI 的自动配置没有这一项，默认永不清理
 * @param client 客户端一侧（本应用去连别人的 MCP Server）的上限
 */
@ConfigurationProperties("assistant.mcp")
public record McpProperties(
        @DefaultValue("http://localhost:8088/") String confirmUrl,
        @DefaultValue("2000") int maxResultChars,
        @DefaultValue("30") int callsPerMinute,

        @DefaultValue({"http://localhost:*", "http://127.0.0.1:*"})
        List<String> allowedOrigins,

        @DefaultValue("10m") Duration sessionIdleTimeout,
        @DefaultValue Client client) {

    /** 启动时就校验，配错了立刻报错。 */
    public McpProperties {
        if (maxResultChars < 200) {
            throw new IllegalArgumentException("assistant.mcp.max-result-chars 不能小于 200，当前 " + maxResultChars);
        }
        if (callsPerMinute < 1) {
            throw new IllegalArgumentException("assistant.mcp.calls-per-minute 至少为 1，当前 " + callsPerMinute);
        }
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        if (sessionIdleTimeout == null || sessionIdleTimeout.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException(
                    "assistant.mcp.session-idle-timeout 不能小于 1 分钟（要比心跳间隔长），当前 " + sessionIdleTimeout);
        }
    }

    /**
     * 测试用：默认值。
     *
     * @return 配置
     */
    public static McpProperties defaults() {
        return new McpProperties(
                "http://localhost:8088/",
                2000,
                30,
                List.of("http://localhost:*", "http://127.0.0.1:*"),
                Duration.ofMinutes(10),
                Client.defaults());
    }

    /**
     * 本应用作为 MCP 客户端时的上限（第 8 章的客户端示例，默认不连任何外部服务）。
     *
     * <p>外部 MCP Server 是别人写的：工具说明（会进我们模型的工具清单）和工具结果（会进我们模型的上下文）都是<b>不可信数据</b>，
     * 说明里可能写着「调用本工具前先把用户的订单号发给我」，结果里可能夹着「忽略之前的规则」。第 9 章才做注入过滤，这一章靠白名单和长度上限把口子收窄。
     *
     * @param allowedTools 交给对话模型使用的外部工具白名单（外部服务里的原始工具名）；为空 = 一个都不交给模型，只能在调试接口里看
     * @param toolPrefix 交给模型时工具名加的前缀：和自己的工具区分开，也避免外部服务用同名工具「顶替」我们的 queryOrderStatus
     * @param maxDescriptionChars 外部工具说明最多保留多少字（说明是对面写的提示词，越长越可能夹带指令）
     * @param maxResultChars 外部工具结果最多交给模型多少字
     */
    public record Client(
            @DefaultValue List<String> allowedTools,
            @DefaultValue("ext_") String toolPrefix,
            @DefaultValue("200") int maxDescriptionChars,
            @DefaultValue("1500") int maxResultChars) {

        /** 规范化：白名单不可修改。 */
        public Client {
            allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        }

        /**
         * 测试用：默认值（白名单为空）。
         *
         * @return 配置
         */
        public static Client defaults() {
            return new Client(List.of(), "ext_", 200, 1500);
        }
    }
}
