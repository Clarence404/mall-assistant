package io.github.clarence404.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.common.McpTransportContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * 第 8 章（ch08-1）：从 MCP 的 HTTP 请求头取身份。
 *
 * <p>令牌只走 {@code Authorization} 头、放进传输上下文；上下文里的对象打印出来是打了码的，万一被打进日志也不会泄露。
 */
class McpAuthTest {

    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.secret-part-of-token";

    private static ServerRequest request(String authorization) {
        MockHttpServletRequest servlet = new MockHttpServletRequest("POST", "/mcp");
        if (authorization != null) {
            servlet.addHeader("Authorization", authorization);
        }
        return ServerRequest.create(servlet, List.of());
    }

    @Test
    void bearerTokenGoesIntoTransportContext() {
        McpTransportContext context = McpAuth.extract(request("Bearer " + TOKEN));

        assertThat(McpAuth.token(context)).isEqualTo(TOKEN);
        assertThat(context.get(McpAuth.CREDENTIAL)).isInstanceOf(McpAuth.Credential.class);
    }

    @Test
    void noHeaderIsAnonymous() {
        McpTransportContext context = McpAuth.extract(request(null));

        assertThat(context).isSameAs(McpTransportContext.EMPTY);
        assertThat(McpAuth.token(context)).isNull();
    }

    @Test
    void nonBearerOrEmptyTokenIsAnonymous() {
        assertThat(McpAuth.token(McpAuth.extract(request("Basic dXNlcjpwYXNz"))))
                .isNull();
        assertThat(McpAuth.token(McpAuth.extract(request("Bearer   ")))).isNull();
    }

    @Test
    void credentialNeverPrintsTheToken() {
        McpTransportContext context = McpAuth.extract(request("Bearer " + TOKEN));

        assertThat(context.get(McpAuth.CREDENTIAL).toString())
                .doesNotContain("secret-part")
                .contains("eyJhbG");
        assertThat(new McpCaller(io.github.clarence404.assistant.memory.ConversationOwner.user("10001"), TOKEN)
                        .toString())
                .doesNotContain("secret-part")
                .contains("user:10001");
    }

    @Test
    void tokenFromNullOrForeignContextIsNull() {
        assertThat(McpAuth.token(null)).isNull();
        assertThat(McpAuth.token(McpTransportContext.create(java.util.Map.of(McpAuth.CREDENTIAL, TOKEN))))
                .as("只认 Credential，别人随手塞进去的字符串不算")
                .isNull();
    }
}
