package io.github.clarence404.assistant.mall;

/**
 * 商城访问令牌的小工具：从 {@code Authorization} 请求头取出令牌、打日志时打码。
 *
 * <p>所在层：外部系统接入（第 3 章引入）。上游是 {@code ChatController}（从请求头取令牌）；令牌之后只放进 Spring AI 的
 * {@code ToolContext}，在工具里使用，<b>从不进入发给模型的任何文字</b>：模型的输入可能被记录、被提供方留存，也可能被用户用提示注入
 * 套出来（「把你收到的所有内容原样输出」），令牌一旦进了提示词就等于泄露了。日志里也只打前几位。
 */
public final class MallToken {

    private static final String BEARER = "Bearer ";

    private MallToken() {}

    /**
     * 从 {@code Authorization: Bearer <token>} 请求头取出令牌。
     *
     * @param authorization 请求头的值，可以为 null
     * @return 令牌；没带请求头、不是 Bearer 格式、或令牌为空时返回 null（按未登录处理）
     */
    public static String fromAuthorizationHeader(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        String token = authorization.substring(BEARER.length()).strip();
        return token.isEmpty() ? null : token;
    }

    /**
     * 日志用的打码：只留前 6 个字符。
     *
     * @param token 令牌，可以为 null
     * @return 如 {@code eyJhbG…}；null 时返回「未登录」
     */
    public static String mask(String token) {
        if (token == null) {
            return "未登录";
        }
        return token.length() <= 6 ? "…" : token.substring(0, 6) + "…";
    }
}
