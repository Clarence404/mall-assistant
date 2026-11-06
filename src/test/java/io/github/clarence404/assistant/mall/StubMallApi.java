package io.github.clarence404.assistant.mall;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * 假的商城网关：JDK 自带的 {@link HttpServer} 起在随机端口上，按 mall-evolution 网关的接口和错误格式返回固定数据。第 3 章引入。
 *
 * <p>为什么不连真的 mall-evolution：它要 MySQL、Redis、RocketMQ、Nacos 和四个服务，测试里起不动；我们要验证的是「智能客服怎么处理商城的各种响应」，
 * 只要响应的形状和真的一样就够了。形状照抄真实接口（见 mall-evolution 的 {@code api/openapi.json}，以及本章实测的响应）：
 *
 * <ul>
 *   <li>{@code POST /api/auth/login}：演示账号 {@code 13800000000 / mall123} 返回 {@link #TOKEN}，否则 401 + ProblemDetail
 *       {@code USER_LOGIN_FAILED}
 *   <li>{@code GET /api/orders/{orderNo}}：没带令牌或令牌不对 → 401、没有响应体（和网关一样）；{@link #ORDER_NO} → 待发货的订单；
 *       {@link #OTHER_USERS_ORDER_NO}（别人的订单）和其他单号 → 404 + {@code ORDER_NOT_FOUND}
 *   <li>{@code GET /api/products/{skuId}}：20001 在售有货，20004 已下架无货，其他数字 → 404 {@code PRODUCT_NOT_FOUND}，非数字 → 400
 * </ul>
 *
 * 还能注入故障：{@link #failWith(int)} 让所有接口返回指定状态码，{@link #delay(Duration)} 让响应变慢（测读超时）。每个请求都记下来，
 * 测试可以断言「有没有发请求」「带的是什么令牌」。
 */
public final class StubMallApi implements AutoCloseable {

    /** 演示账号登录后拿到的令牌。测试会断言它<b>从不</b>出现在发给模型的任何文字里。 */
    public static final String TOKEN = "stub-mall-token-10001-do-not-leak";

    /** 演示账号自己的订单（23 位：19 位雪花 ID + 4 位用户基因），状态待发货。 */
    public static final String ORDER_NO = "21182516707889029120785";

    /** 别的用户的订单：商城对别人的订单也返回 404，和不存在一样。 */
    public static final String OTHER_USERS_ORDER_NO = "21182516707889029129999";

    static final String ORDER_JSON = """
            {"orderNo":"21182516707889029120785","status":"PENDING_SHIPMENT","totalAmount":"39.90",\
            "freightAmount":"0.00","discountAmount":"0.00","payAmount":"39.90",\
            "receiver":{"name":"张三","phone":"13800000000","address":"杭州市西湖区"},"remark":null,\
            "items":[{"skuId":"20001","productName":"青柚 · 红心柚 2 只装","productImage":"https://img.mall.example/20001.jpg",\
            "unitPrice":"39.90","quantity":1,"itemAmount":"39.90"}],\
            "createdAt":"2026-11-05T15:58:48.258","paidAt":"2026-11-05T15:59:02.001"}""";

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile int failStatus;
    private volatile Duration delay = Duration.ZERO;

    /** 一个收到的请求。 */
    public record Request(String method, String path, String authorization) {}

    private StubMallApi(HttpServer server) {
        this.server = server;
    }

    /**
     * 在 127.0.0.1 的随机端口上启动。
     *
     * @return 已启动的假网关
     */
    public static StubMallApi start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StubMallApi stub = new StubMallApi(server);
            server.createContext("/", stub::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 基础地址，填进 {@code assistant.mall.base-url}。 */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 收到的全部请求。 */
    public List<Request> requests() {
        return requests;
    }

    /** 之后所有请求都返回这个状态码（如 500、503），带一个 SYSTEM_ERROR 的 ProblemDetail。0 表示恢复正常。 */
    public void failWith(int status) {
        this.failStatus = status;
    }

    /** 之后每个响应都先等这么久再返回。 */
    public void delay(Duration delay) {
        this.delay = delay;
    }

    /** 清空记录、恢复正常。 */
    public void reset() {
        requests.clear();
        failStatus = 0;
        delay = Duration.ZERO;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        requests.add(new Request(method, path, authorization));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (failStatus != 0) {
            problem(exchange, failStatus, "SYSTEM_ERROR", "系统繁忙，请稍后再试");
            return;
        }
        if ("POST".equals(method) && "/api/auth/login".equals(path)) {
            login(exchange, body);
        } else if ("GET".equals(method) && path.startsWith("/api/orders/")) {
            order(exchange, path.substring("/api/orders/".length()), authorization);
        } else if ("GET".equals(method) && path.startsWith("/api/products/")) {
            product(exchange, path.substring("/api/products/".length()));
        } else {
            problem(exchange, 404, "NOT_FOUND", "没有这个接口");
        }
    }

    private static void login(HttpExchange exchange, String body) throws IOException {
        if (body.contains("\"13800000000\"") && body.contains("\"mall123\"")) {
            json(
                    exchange,
                    200,
                    "{\"accessToken\":\"" + TOKEN + "\",\"refreshToken\":\"stub-refresh\",\"expiresIn\":1800}");
        } else {
            problem(exchange, 401, "USER_LOGIN_FAILED", "手机号或密码错误");
        }
    }

    private static void order(HttpExchange exchange, String orderNo, String authorization) throws IOException {
        if (!("Bearer " + TOKEN).equals(authorization)) {
            // 网关（Spring Security 资源服务器）的 401：只有 WWW-Authenticate 头，没有响应体
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        if (ORDER_NO.equals(orderNo)) {
            json(exchange, 200, ORDER_JSON);
        } else {
            problem(exchange, 404, "ORDER_NOT_FOUND", "订单 " + orderNo + " 不存在");
        }
    }

    private static void product(HttpExchange exchange, String skuId) throws IOException {
        if (!skuId.matches("\\d+")) {
            problem(exchange, 400, "VALIDATION_FAILED", "请求参数校验失败");
        } else if ("20001".equals(skuId)) {
            json(exchange, 200, """
                    {"product":{"skuId":"20001","name":"青柚 · 红心柚 2 只装","image":"https://img.mall.example/20001.jpg",\
                    "price":"39.90","onShelf":true},"inStock":true}""");
        } else if ("20004".equals(skuId)) {
            json(exchange, 200, """
                    {"product":{"skuId":"20004","name":"青柚 · 已下架演示商品","image":"","price":"9.90","onShelf":false},\
                    "inStock":false}""");
        } else {
            problem(exchange, 404, "PRODUCT_NOT_FOUND", "商品 " + skuId + " 不存在");
        }
    }

    /** mall-evolution 的 ProblemDetail：type / title / status / detail / code / traceId。 */
    private static void problem(HttpExchange exchange, int status, String code, String detail) throws IOException {
        String body = "{\"type\":\"https://api.mall.example/problems/"
                + code.toLowerCase().replace('_', '-')
                + "\",\"title\":\"" + code + "\",\"status\":" + status + ",\"detail\":\"" + detail + "\",\"code\":\""
                + code + "\",\"traceId\":null}";
        send(exchange, status, "application/problem+json", body);
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "application/json", body);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
