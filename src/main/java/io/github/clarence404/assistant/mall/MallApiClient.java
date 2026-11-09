package io.github.clarence404.assistant.mall;

import java.net.http.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 调商城（mall-evolution）网关的 HTTP 客户端：登录、查自己的订单、列出自己最近的订单、查商品、查当前用户（第 6 章）。每个方法都返回 {@link MallResult}，不抛异常。
 *
 * <p>所在层：外部系统接入（第 3 章引入）。上游是 {@code tool.MallTools}（模型调用的工具）和 {@link MallLoginController}
 * （页面登录）；下游是商城网关 {@code assistant.mall.base-url}（默认 {@code http://localhost:9000}）。
 *
 * <p>智能客服对商城来说就是「另一个前端」：走网关的对外接口、带用户自己的令牌，网关和 mall-app 照常校验 JWT、限流、只返回本人的订单。
 * 所以智能客服<b>看不到别人的订单</b>不是靠提示词约束模型，而是商城本身就不给：用户 A 的令牌去查用户 B 的订单号，商城返回 404。
 * 这也是工具安全的基本原则「工具以当前用户的身份和权限执行，不用全能服务账号」。
 *
 * <p>用的是 Spring 的 {@link RestClient}（同步 HTTP 客户端）。超时单独配置（连接 2 秒、读 5 秒，见 {@link MallApiProperties}），
 * 不复用调模型用的 {@code spring.http.clients.*}（读超时 60 秒）。
 */
@Component
public class MallApiClient {

    private static final Logger log = LoggerFactory.getLogger(MallApiClient.class);

    private final RestClient rest;

    /**
     * 按配置创建 RestClient：JDK 自带的 HttpClient，HTTP/1.1，连接与读超时取自 {@link MallApiProperties}。
     *
     * @param properties {@code assistant.mall.*}
     */
    public MallApiClient(MallApiProperties properties) {
        // 明确用 HTTP/1.1：JDK HttpClient 默认先尝试 HTTP/2 升级，对 http:// 地址会多带一个 Upgrade 头，没必要
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        this.rest = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * 登录：{@code POST /api/auth/login}，换回访问令牌。
     *
     * @param phone 手机号
     * @param password 密码，只转发给商城，不记日志、不保存
     * @return 成功时是令牌；手机号或密码错是 {@link MallFailure#UNAUTHORIZED}（code=USER_LOGIN_FAILED）
     */
    public MallResult<MallModels.Tokens> login(String phone, String password) {
        return exchange(
                "登录",
                rest.post()
                        .uri("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new MallModels.LoginRequest(phone, password)),
                MallModels.Tokens.class);
    }

    /**
     * 查当前登录用户自己的一笔订单：{@code GET /api/orders/{orderNo}}，带 {@code Authorization: Bearer <令牌>}。
     *
     * @param accessToken 用户的访问令牌（来自页面登录，经 ToolContext 传到工具里）
     * @param orderNo 订单号，已校验过格式
     * @return 成功时是订单；不存在或不是本人的订单是 {@link MallFailure#NOT_FOUND}；令牌无效或过期是 {@link MallFailure#UNAUTHORIZED}
     */
    public MallResult<MallModels.Order> getOrder(String accessToken, String orderNo) {
        return exchange(
                "查订单",
                rest.get().uri("/api/orders/{orderNo}", orderNo).headers(h -> h.setBearerAuth(accessToken)),
                MallModels.Order.class);
    }

    /**
     * 列出当前登录用户最近的订单：{@code GET /api/orders?size=N}，带 {@code Authorization: Bearer <令牌>}。第 3 章补充。
     *
     * <p>只取第一页（最近的 N 笔），不传游标、不按状态过滤。和 {@link #getOrder} 一样，用户是谁由商城从令牌里解析，
     * 接口不接受「用户 ID」参数，所以这里也列不出别人的订单。
     *
     * @param accessToken 用户的访问令牌
     * @param size 每页条数，调用方已限制在合理范围（商城自己也会再收紧到 1～50）
     * @return 成功时是一页订单（可能为空列表）；令牌无效或过期是 {@link MallFailure#UNAUTHORIZED}
     */
    public MallResult<MallModels.OrderPage> listOrders(String accessToken, int size) {
        return exchange(
                "列订单",
                rest.get().uri("/api/orders?size={size}", size).headers(h -> h.setBearerAuth(accessToken)),
                MallModels.OrderPage.class);
    }

    /**
     * 查当前令牌对应的商城用户：{@code GET /api/users/me}，带 {@code Authorization: Bearer <令牌>}。第 6 章引入，用来把会话绑定到商城用户。
     *
     * <p>为什么不自己解析令牌：商城的访问令牌是 JWT，中间那段 Base64 解开就能看到用户 ID（{@code sub}），但没有商城的密钥就<b>验不了签名</b>——
     * 谁都可以伪造一个 {@code sub} 写着别人 ID 的令牌。让商城自己验（这个接口只有令牌有效时才返回本人信息），拿到的用户 ID 才可信。
     *
     * @param accessToken 商城访问令牌
     * @return 成功时是用户 ID 与昵称；令牌无效或过期是 {@link MallFailure#UNAUTHORIZED}；商城不可用是 {@link MallFailure#UNAVAILABLE}
     */
    public MallResult<MallModels.UserProfile> me(String accessToken) {
        return exchange(
                "查当前用户",
                rest.get().uri("/api/users/me").headers(h -> h.setBearerAuth(accessToken)),
                MallModels.UserProfile.class);
    }

    /**
     * 查商品详情与是否有货：{@code GET /api/products/{skuId}}，匿名可查。
     *
     * @param skuId 商品编号，已校验过是数字
     * @return 成功时是商品与库存展示；不存在是 {@link MallFailure#NOT_FOUND}
     */
    public MallResult<MallModels.ProductPage> getProduct(String skuId) {
        return exchange("查商品", rest.get().uri("/api/products/{skuId}", skuId), MallModels.ProductPage.class);
    }

    /**
     * 发出请求，把结果统一成 {@link MallResult}：2xx 读出响应体；4xx / 5xx 读出商城 ProblemDetail 里的业务码，按状态码归类；
     * 连不上、超时等网络异常归为 {@link MallFailure#UNAVAILABLE}。不抛异常，失败只记日志（不记令牌、不记密码）。
     *
     * <p>状态码归类：401 / 403 → UNAUTHORIZED，404 → NOT_FOUND，429 与 5xx → UNAVAILABLE，其余 4xx → REJECTED。
     *
     * @param action 动作名，只用于日志，如「查订单」
     * @param request 已经拼好的请求（方法、路径、请求头、请求体）
     * @param type 成功时响应体的类型
     * @param <T> 响应体类型
     * @return 成功或失败的结果，从不返回 null
     */
    <T> MallResult<T> exchange(String action, RestClient.RequestHeadersSpec<?> request, Class<T> type) {
        long start = System.nanoTime();
        try {
            // exchange 拿到原始响应自己处理：默认的 retrieve() 遇到 4xx / 5xx 会直接抛异常，这里要按状态码分类，不想靠异常走流程
            MallResult<T> result = request.exchange((req, res) -> {
                HttpStatusCode status = res.getStatusCode();
                if (status.is2xxSuccessful()) {
                    T body = res.bodyTo(type);
                    return body != null
                            ? MallResult.ok(body)
                            : MallResult.failed(MallFailure.UNAVAILABLE, null, "响应体为空");
                }
                MallModels.Problem problem = readProblem(res);
                return MallResult.failed(classify(status), problem.code(), problem.detail());
            });
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (result.isOk()) {
                log.debug("商城接口{}成功：{} ms", action, ms);
            } else {
                log.info("商城接口{}失败：{}，code={}，{} ms", action, result.failure(), result.code(), ms);
            }
            return result;
        } catch (RestClientException | HttpMessageConversionException e) {
            // RestClientException 含 ResourceAccessException（连接被拒、超时）；HttpMessageConversionException 是响应体解析不了
            log.warn("商城接口{}调用失败：{}", action, e.getMessage());
            return MallResult.failed(MallFailure.UNAVAILABLE, null, e.getMessage());
        }
    }

    /** 状态码归类，见 {@link #exchange} 的说明。 */
    static MallFailure classify(HttpStatusCode status) {
        int code = status.value();
        if (code == 401 || code == 403) {
            return MallFailure.UNAUTHORIZED;
        }
        if (code == 404) {
            return MallFailure.NOT_FOUND;
        }
        if (code == 429 || status.is5xxServerError()) {
            return MallFailure.UNAVAILABLE;
        }
        return MallFailure.REJECTED;
    }

    /**
     * 尽量读出 ProblemDetail；网关的 401 没有响应体、内容类型也不是 JSON，读不出来就返回空的 Problem，不影响按状态码归类。
     */
    private static MallModels.Problem readProblem(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse res) {
        try {
            MallModels.Problem problem = res.bodyTo(MallModels.Problem.class);
            return problem != null ? problem : new MallModels.Problem(null, null);
        } catch (RestClientException | HttpMessageConversionException e) {
            return new MallModels.Problem(null, null);
        }
    }
}
