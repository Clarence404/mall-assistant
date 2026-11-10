package io.github.clarence404.assistant.mcp;

import io.github.clarence404.assistant.mall.MallToken;
import io.github.clarence404.assistant.memory.ConversationOwner;

/**
 * 一次 MCP 工具调用是谁发起的：商城确认过的用户（或匿名），以及他的令牌（第 8 章引入）。
 *
 * <p>所在层：MCP 的领域模型。{@link McpGate#enter} 得出它，{@link McpMallService} 用令牌调商城、用归属登记待确认操作。
 *
 * @param owner 归属：第 6 章的 {@link ConversationOwner}，匿名或商城用户（用户 ID 由商城 {@code /api/users/me} 确认，不是我们自己解析令牌）
 * @param token 商城令牌；匿名时为 null。只在代码里用，{@link #toString()} 打了码
 */
public record McpCaller(ConversationOwner owner, String token) {

    /** 匿名调用方。 */
    public static final McpCaller ANONYMOUS = new McpCaller(ConversationOwner.ANONYMOUS, null);

    /** 是否登录了商城。 */
    public boolean loggedIn() {
        return token != null && owner.kind() == ConversationOwner.Kind.USER;
    }

    /** 打码：record 默认的 toString 会把令牌原文打出来。 */
    @Override
    public String toString() {
        return "McpCaller[" + owner.key() + ", " + MallToken.mask(token) + "]";
    }
}
