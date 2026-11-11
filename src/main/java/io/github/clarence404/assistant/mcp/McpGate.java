package io.github.clarence404.assistant.mcp;

import io.github.clarence404.assistant.mall.MallToken;
import io.github.clarence404.assistant.memory.ConversationException;
import io.github.clarence404.assistant.memory.ConversationOwner;
import io.github.clarence404.assistant.memory.ConversationOwners;
import io.github.clarence404.assistant.state.StateUnavailableException;
import io.modelcontextprotocol.common.McpTransportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 每个 MCP 工具调用的「门」：确认是谁、该不该让他调、有没有超次数（第 8 章引入）。
 *
 * <p>所在层：MCP 的安全。上游是 {@link MallMcpTools} 的每个工具方法（第一行就是 {@link #enter}）；下游是 {@link McpAuth}（取令牌）、
 * 第 6 章的 {@link ConversationOwners}（问商城「这个令牌是谁」）和 {@link McpRateLimiter}（次数上限）。
 *
 * <p><b>工具分两档</b>：
 *
 * <ul>
 *   <li>公开的：查知识库、查商品。不涉及个人数据，没带令牌也能调（MCP 客户端不登录也能问「七天无理由退货要什么条件」）
 *   <li>要登录的：查订单、列订单、提出写操作、查确认结果。没带令牌、令牌无效都拦下，返回明确的原因（「请在 MCP 客户端的请求头里带上商城令牌」），
 *       而不是返回一个空结果让对面的模型以为「这个用户没有订单」
 * </ul>
 *
 * <p>带了令牌就一定要验：哪怕调的是公开工具，令牌无效也拦下（多半是配置错了，早点告诉对面），免得对面以为登录了、其实一直是匿名。
 * 验证方式和第 6 章完全一样——问商城 {@code /api/users/me}，结果按令牌指纹缓存几分钟，不自己解析 JWT（我们没有商城的密钥，验不了签名）。
 */
@Component
public class McpGate {

    private static final Logger log = LoggerFactory.getLogger(McpGate.class);

    static final String LOGIN_REQUIRED =
            "这个工具要登录商城才能用：请在 MCP 客户端的请求头里带上 Authorization: Bearer <商城令牌>（用演示账号登录智能客服页面或商城后获得）";
    static final String LOGIN_EXPIRED = "商城令牌无效或已过期：请重新登录商城，换一个新的令牌配置到 MCP 客户端的请求头里";
    static final String OWNER_UNVERIFIED = "商城暂时不可用，确认不了你是谁，请稍后再试";
    static final String TOO_MANY_CALLS = "调用太频繁了（每分钟最多 %d 次），请稍后再试；不要在循环里反复调用同一个工具";
    static final String STATE_UNAVAILABLE = "服务端暂时不可用（限流状态读不到），请稍后再试";

    private final ConversationOwners owners;
    private final McpRateLimiter limiter;
    private final McpProperties properties;

    /**
     * 创建。
     *
     * @param owners 第 6 章的归属（问商城「这个令牌是谁」，带缓存）
     * @param limiter 次数上限
     * @param properties {@code assistant.mcp.*}
     */
    public McpGate(ConversationOwners owners, McpRateLimiter limiter, McpProperties properties) {
        this.owners = owners;
        this.limiter = limiter;
        this.properties = properties;
    }

    /**
     * 进门：确认调用方、检查登录与次数（ch08-2）。
     *
     * <p>要求（测试按这个写），按顺序：
     *
     * <ol>
     *   <li>{@link McpAuth#token} 取令牌；有令牌就 {@code owners.resolve(令牌)}：{@link ConversationException.Failure#LOGIN_EXPIRED} →
     *       抛 {@link McpRejection}({@link #LOGIN_EXPIRED})，其他失败（商城不可用）→ {@link #OWNER_UNVERIFIED}；没令牌就是 {@link McpCaller#ANONYMOUS}
     *   <li>{@code loginRequired} 而调用方是匿名：抛 {@link #LOGIN_REQUIRED}（不调商城）
     *   <li>{@code limiter.tryAcquire(归属的 key)}：不放行就抛 {@link #TOO_MANY_CALLS}（填上每分钟上限）。匿名调用方共用一个 key：
     *       匿名的没法区分谁是谁，只能整体限
     *   <li>记一行日志：工具名和归属（{@code owner.key()}），<b>不打令牌</b>
     * </ol>
     *
     * @param context 传输上下文（{@link McpAuth#extract} 放进去的）
     * @param tool 工具名（只用于日志）
     * @param loginRequired 这个工具是否要登录
     * @return 调用方
     * @throws McpRejection 被拦下（原因给对面的模型看）
     */
    public McpCaller enter(McpTransportContext context, String tool, boolean loginRequired) {
        String token = McpAuth.token(context);
        McpCaller caller = token == null ? McpCaller.ANONYMOUS : new McpCaller(resolve(token), token);
        if (loginRequired && !caller.loggedIn()) {
            throw new McpRejection(LOGIN_REQUIRED);
        }
        if (!tryAcquire(caller.owner().key())) {
            log.warn("MCP 调用太频繁：{} 调 {}", caller.owner().key(), tool);
            throw new McpRejection(TOO_MANY_CALLS.formatted(properties.callsPerMinute()));
        }
        log.info("MCP 工具调用：{}（{}）", tool, caller.owner().key());
        return caller;
    }

    /** 次数上限；第 10 章：Redis 出错且策略是拒绝时，翻成给对面看的原因（不把 Redis 的异常信息漏出去）。 */
    private boolean tryAcquire(String key) {
        try {
            return limiter.tryAcquire(key);
        } catch (StateUnavailableException e) {
            throw new McpRejection(STATE_UNAVAILABLE);
        }
    }

    /** 令牌 → 归属；失败翻成给对面看的原因。 */
    private ConversationOwner resolve(String token) {
        try {
            return owners.resolve(token);
        } catch (ConversationException e) {
            log.info("MCP 调用方的令牌没有通过商城校验（{}）：{}", MallToken.mask(token), e.failure());
            throw new McpRejection(
                    e.failure() == ConversationException.Failure.LOGIN_EXPIRED ? LOGIN_EXPIRED : OWNER_UNVERIFIED);
        }
    }
}
