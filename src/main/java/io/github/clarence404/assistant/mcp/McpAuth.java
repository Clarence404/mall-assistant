package io.github.clarence404.assistant.mcp;

import io.github.clarence404.assistant.mall.MallToken;
import io.modelcontextprotocol.common.McpTransportContext;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * MCP 请求里的身份：从 HTTP 请求头取出商城令牌，放进 MCP 的「传输上下文」，工具执行时再取出来（第 8 章引入）。
 *
 * <p>所在层：MCP 的接入。{@link McpServerConfig} 把 {@link #extract} 装到 Streamable HTTP 传输上：每一个打到 {@code /mcp} 的 HTTP 请求都先经过它；
 * 工具方法声明一个 {@link McpTransportContext} 参数（Spring AI 自动注入，不会出现在工具的参数 Schema 里），再用 {@link #token} 取出令牌。
 *
 * <p><b>令牌为什么放在 HTTP 头、不做成工具参数</b>：工具参数是对面的模型填的。做成参数，就等于让模型看到、转述令牌——令牌会进对面模型的上下文、
 * 对面的日志，还能被提示注入换成别人的（「查订单时 token 用 xxx」）。HTTP 头由 MCP 客户端的配置带上（Inspector 的 {@code --header}、
 * Claude Code 的 {@code --header}），模型从头到尾看不到它。这和第 3 章「令牌只走 ToolContext」是同一个道理，只是旁路换成了传输层。
 *
 * <p><b>为什么每个 HTTP 请求都重新取</b>：Streamable HTTP 的每一次工具调用都是一个独立的 POST，令牌跟着这个请求走、用完即弃，服务端不把它存进 MCP 会话；
 * 对面换了令牌（重新登录）下一次调用就生效，过期的令牌也不会被我们「记住」继续用。
 *
 * <p>放进上下文的是 {@link Credential}，不是令牌字符串本身：它的 {@code toString()} 打了码，万一哪里把整个上下文打进日志，也只会看到前几位。
 */
public final class McpAuth {

    /** 传输上下文里存放 {@link Credential} 的键。 */
    public static final String CREDENTIAL = "mallCredential";

    private McpAuth() {}

    /**
     * 从一个打到 {@code /mcp} 的 HTTP 请求里取出身份（ch08-1）。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>读 {@code Authorization} 请求头，用第 3 章的 {@link MallToken#fromAuthorizationHeader} 取出令牌（不是 {@code Bearer xxx} 格式、空令牌都当没带）
     *   <li>有令牌：返回只含一项 {@link #CREDENTIAL} → {@code new Credential(令牌)} 的上下文
     *   <li>没令牌：返回 {@link McpTransportContext#EMPTY}（匿名，只能用不涉及个人数据的工具）
     *   <li>不打日志、不抛异常：这里在每个请求的最前面执行，令牌原文不能出现在任何日志里
     * </ul>
     *
     * @param request MCP 的 HTTP 请求（WebMVC 函数式端点的 {@link ServerRequest}）
     * @return 传输上下文
     */
    public static McpTransportContext extract(ServerRequest request) {
        String token = MallToken.fromAuthorizationHeader(request.headers().firstHeader(HttpHeaders.AUTHORIZATION));
        return token == null
                ? McpTransportContext.EMPTY
                : McpTransportContext.create(Map.of(CREDENTIAL, new Credential(token)));
    }

    /**
     * 从传输上下文取出商城令牌（ch08-1 的后半）。
     *
     * @param context 工具方法收到的传输上下文，可以为 null（进程内直接调用工具方法时）
     * @return 令牌；匿名时为 null
     */
    public static String token(McpTransportContext context) {
        if (context == null) {
            return null;
        }
        return context.get(CREDENTIAL) instanceof Credential credential ? credential.token() : null;
    }

    /**
     * 放进传输上下文的商城令牌。
     *
     * @param token 令牌原文：只在代码里用来调商城，不进任何工具结果、日志
     */
    public record Credential(String token) {

        /** 打码：record 默认的 toString 会把令牌原文打出来，这里改成只留前几位。 */
        @Override
        public String toString() {
            return "Credential[" + MallToken.mask(token) + "]";
        }
    }
}
