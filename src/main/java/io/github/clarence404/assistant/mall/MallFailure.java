package io.github.clarence404.assistant.mall;

/**
 * 调商城接口失败的几类原因：调用方（工具、登录接口）按类别决定给用户说什么，不关心具体的 HTTP 状态码。
 *
 * <p>所在层：外部系统接入（第 3 章引入）。{@link MallApiClient} 把 HTTP 状态码和网络异常归成这几类，放进 {@link MallResult}。
 */
public enum MallFailure {
    /** 401 / 403：没带令牌、令牌无效或已过期，或者没有权限。 */
    UNAUTHORIZED,
    /** 404：订单或商品不存在。商城对「别人的订单」也返回 404（不让人借 403 / 404 的差别探测订单号）。 */
    NOT_FOUND,
    /** 其余 4xx：请求本身不合法，如参数格式不对（400）。 */
    REJECTED,
    /** 连不上、超时、429 限流、5xx：商城暂时不可用，稍后再试可能就好了。 */
    UNAVAILABLE
}
