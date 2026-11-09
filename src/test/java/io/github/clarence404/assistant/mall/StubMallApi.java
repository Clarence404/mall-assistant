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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 假的商城网关：JDK 自带的 {@link HttpServer} 起在随机端口上，按 mall-evolution 网关的接口和错误格式返回固定数据。第 3 章引入。
 *
 * <p>为什么不连真的 mall-evolution：它要 MySQL、Redis、RocketMQ、Nacos 和四个服务，测试里起不动；我们要验证的是「智能客服怎么处理商城的各种响应」，
 * 只要响应的形状和真的一样就够了。形状照抄真实接口（见 mall-evolution 的 {@code api/openapi.json}，以及本章实测的响应）：
 *
 * <ul>
 *   <li>{@code POST /api/auth/login}：演示账号 {@code 13800000000 / mall123} 返回 {@link #TOKEN}，否则 401 + ProblemDetail
 *       {@code USER_LOGIN_FAILED}
 *   <li>{@code GET /api/orders/{orderNo}}：没带令牌或令牌不对 → 401、没有响应体（和网关一样）；{@link #ORDER_NO} → 待发货的订单，
 *       {@link #COMPLETED_ORDER_NO} → 已完成的订单（两件商品）；{@link #OTHER_USERS_ORDER_NO}（别人的订单）和其他单号 → 404 +
 *       {@code ORDER_NOT_FOUND}
 *   <li>{@code GET /api/orders?size=N}（第 3 章补充，「我的订单」列表，游标分页的第一页）：演示账号有两笔订单，按下单时间从新到旧
 *       （{@link #ORDER_NO}、{@link #COMPLETED_ORDER_NO}），取前 N 笔，还有剩下的就 {@code hasMore=true}；{@link #NEW_USER_TOKEN}
 *       （没下过单的新用户）→ 空列表；令牌不对 → 401、没有响应体
 *   <li>{@code GET /api/products/{skuId}}：20001 在售有货，20004 已下架无货，其他数字 → 404 {@code PRODUCT_NOT_FOUND}，非数字 → 400
 *   <li>{@code GET /api/users/me}（第 6 章，确认会话归属）：{@link #TOKEN} → 用户 10001，{@link #NEW_USER_TOKEN} → 用户 10002，
 *       令牌不对 → 401、没有响应体
 *   <li>第 7 章（Agent 的写操作）：演示账号多了两笔<b>有状态</b>的订单，只能按订单号查到、不在「我的订单」列表里（免得改动前几章的列表断言和录制）：
 *       {@link #PENDING_PAYMENT_ORDER_NO}（待支付）、{@link #SHIPPED_ORDER_NO}（已发货）。{@code POST /api/orders/{orderNo}/cancel}：
 *       待支付的变成已取消，其他状态 409 {@code ORDER_STATUS_CONFLICT}（detail 照抄商城的「订单 xxx 已支付，不能取消」）；
 *       {@code PATCH /api/orders/{orderNo}/address}：待支付、待发货的改掉收货信息，已发货 / 已完成 409，手机号格式不对 400
 *       {@code VALIDATION_FAILED}；{@code GET /api/orders/{orderNo}/payment}：待支付 PENDING、已取消 NONE、其余 PAID。别人的订单都是 404。
 *       {@link #reset()} 恢复这些订单的初始状态
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

    /** 演示账号的另一笔订单（更早下的），状态已完成，两件商品。第 3 章补充，用于「我的订单」列表。 */
    public static final String COMPLETED_ORDER_NO = "21180000000000000010785";

    /** 一个还没下过单的新用户的令牌：订单列表为空。第 3 章补充。 */
    public static final String NEW_USER_TOKEN = "stub-mall-token-10002-new-user";

    /** 别的用户的订单：商城对别人的订单也返回 404，和不存在一样。 */
    public static final String OTHER_USERS_ORDER_NO = "21182516707889029129999";

    /** 第 7 章：演示账号一笔待支付的订单（能取消、能改地址），有状态，不在列表里。 */
    public static final String PENDING_PAYMENT_ORDER_NO = "21182516707889029130785";

    /** 第 7 章：演示账号一笔已发货的订单（不能取消、不能改地址，只能申请售后），有状态，不在列表里。 */
    public static final String SHIPPED_ORDER_NO = "21182516707889029140785";

    private static final Pattern STRING_FIELD = Pattern.compile("\"(receiver|phone|detail)\"\\s*:\\s*\"([^\"]*)\"");

    static final String ORDER_JSON = """
            {"orderNo":"21182516707889029120785","status":"PENDING_SHIPMENT","totalAmount":"39.90",\
            "freightAmount":"0.00","discountAmount":"0.00","payAmount":"39.90",\
            "receiver":{"name":"张三","phone":"13800000000","address":"杭州市西湖区"},"remark":null,\
            "items":[{"skuId":"20001","productName":"青柚 · 红心柚 2 只装","productImage":"https://img.mall.example/20001.jpg",\
            "unitPrice":"39.90","quantity":1,"itemAmount":"39.90"}],\
            "createdAt":"2026-11-05T15:58:48.258","paidAt":"2026-11-05T15:59:02.001"}""";

    static final String COMPLETED_ORDER_JSON = """
            {"orderNo":"21180000000000000010785","status":"COMPLETED","totalAmount":"59.80",\
            "freightAmount":"0.00","discountAmount":"0.00","payAmount":"59.80",\
            "items":[{"skuId":"20002","productName":"青柚 · 白心柚 1 只装","unitPrice":"19.90","quantity":1,"itemAmount":"19.90"},\
            {"skuId":"20003","productName":"青柚 · 柚子茶 500g","unitPrice":"39.90","quantity":1,"itemAmount":"39.90"}],\
            "createdAt":"2026-11-01T10:00:00","paidAt":"2026-11-01T10:01:00"}""";

    /** 「我的订单」列表项（商城的 OrderListItemView），从新到旧。 */
    private static final List<String> ORDER_LIST_ITEMS = List.of("""
            {"orderNo":"21182516707889029120785","status":"PENDING_SHIPMENT","payAmount":"39.90",\
            "createdAt":"2026-11-05T15:58:48.258"}""", """
            {"orderNo":"21180000000000000010785","status":"COMPLETED","payAmount":"59.80",\
            "createdAt":"2026-11-01T10:00:00"}""");

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    /** 第 7 章：演示账号的订单（订单号 → 当前状态与收货信息），取消、改地址会改它，{@link #reset()} 时恢复。 */
    private final Map<String, StubOrder> orders = new ConcurrentHashMap<>();

    private volatile int failStatus;
    private volatile Duration delay = Duration.ZERO;

    /**
     * 一个收到的请求。
     *
     * @param method 方法
     * @param path 路径，不含查询串
     * @param query 查询串，如 {@code size=5}；没有时为 null（第 3 章补充，用来断言 size 传了多少）
     * @param authorization Authorization 请求头；没带时为 null
     * @param body 请求体（第 7 章，断言改地址传了哪些字段）；没有时为空串
     */
    public record Request(String method, String path, String query, String authorization, String body) {}

    /**
     * 第 7 章：一笔演示订单的当前状态。
     *
     * @param orderNo 订单号
     * @param status 状态枚举名
     * @param receiver 收货人
     * @param phone 手机号
     * @param address 地址
     * @param modified 是否被取消 / 改过（两笔老订单没改过时原样返回老的 JSON，前几章的断言不受影响）
     */
    record StubOrder(String orderNo, String status, String receiver, String phone, String address, boolean modified) {}

    private StubMallApi(HttpServer server) {
        this.server = server;
        resetOrders();
    }

    /** 四笔演示订单的初始状态。 */
    private void resetOrders() {
        orders.clear();
        for (String[] o : new String[][] {
            {ORDER_NO, "PENDING_SHIPMENT"},
            {COMPLETED_ORDER_NO, "COMPLETED"},
            {PENDING_PAYMENT_ORDER_NO, "PENDING_PAYMENT"},
            {SHIPPED_ORDER_NO, "SHIPPED"}
        }) {
            orders.put(o[0], new StubOrder(o[0], o[1], "张三", "13800000000", "杭州市西湖区", false));
        }
    }

    /**
     * 第 7 章：某笔订单现在的状态（断言「取消了没有」）。
     *
     * @param orderNo 订单号
     * @return 状态枚举名；没有这笔时为 null
     */
    public String orderStatus(String orderNo) {
        StubOrder order = orders.get(orderNo);
        return order == null ? null : order.status();
    }

    /**
     * 第 7 章：某笔订单现在的收货地址。
     *
     * @param orderNo 订单号
     * @return 地址；没有这笔时为 null
     */
    public String orderAddress(String orderNo) {
        StubOrder order = orders.get(orderNo);
        return order == null ? null : order.address();
    }

    /**
     * 第 7 章：收到的写请求（取消、改地址），不含登录。
     *
     * @return 方法不是 GET 的请求
     */
    public List<Request> writes() {
        return requests.stream()
                .filter(r -> !"GET".equals(r.method()) && !"/api/auth/login".equals(r.path()))
                .toList();
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

    /** 清空记录、恢复正常（第 7 章起也恢复订单的初始状态）。 */
    public void reset() {
        requests.clear();
        failStatus = 0;
        delay = Duration.ZERO;
        resetOrders();
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
        requests.add(new Request(method, path, exchange.getRequestURI().getQuery(), authorization, body));
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
        } else if ("GET".equals(method) && "/api/orders".equals(path)) {
            listOrders(exchange, exchange.getRequestURI().getQuery(), authorization);
        } else if ("POST".equals(method) && path.startsWith("/api/orders/") && path.endsWith("/cancel")) {
            cancel(exchange, between(path, "/cancel"), authorization);
        } else if ("PATCH".equals(method) && path.startsWith("/api/orders/") && path.endsWith("/address")) {
            changeAddress(exchange, between(path, "/address"), authorization, body);
        } else if ("GET".equals(method) && path.startsWith("/api/orders/") && path.endsWith("/payment")) {
            payment(exchange, between(path, "/payment"), authorization);
        } else if ("GET".equals(method) && path.startsWith("/api/orders/")) {
            order(exchange, path.substring("/api/orders/".length()), authorization);
        } else if ("GET".equals(method) && "/api/users/me".equals(path)) {
            me(exchange, authorization);
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

    private void order(HttpExchange exchange, String orderNo, String authorization) throws IOException {
        StubOrder order = ownOrder(exchange, orderNo, authorization);
        if (order != null) {
            json(exchange, 200, orderJson(order));
        }
    }

    /** 第 7 章：取消。只有待支付能取消，否则 409，detail 照抄商城（OrderCancelService#conflictMessage）。 */
    private void cancel(HttpExchange exchange, String orderNo, String authorization) throws IOException {
        StubOrder order = ownOrder(exchange, orderNo, authorization);
        if (order == null) {
            return;
        }
        if (!"PENDING_PAYMENT".equals(order.status())) {
            String state = switch (order.status()) {
                case "PENDING_SHIPMENT" -> "已支付";
                case "SHIPPED" -> "已发货";
                case "COMPLETED" -> "已完成";
                case "CANCELLED" -> "已取消";
                default -> "已关闭";
            };
            problem(exchange, 409, "ORDER_STATUS_CONFLICT", "订单 " + orderNo + " " + state + "，不能取消");
            return;
        }
        StubOrder cancelled =
                new StubOrder(orderNo, "CANCELLED", order.receiver(), order.phone(), order.address(), true);
        orders.put(orderNo, cancelled);
        json(exchange, 200, orderJson(cancelled));
    }

    /** 第 7 章：改地址。只有待支付、待发货能改；手机号要 1 开头 11 位；没传的字段不改（同商城的 COALESCE）。 */
    private void changeAddress(HttpExchange exchange, String orderNo, String authorization, String body)
            throws IOException {
        StubOrder order = ownOrder(exchange, orderNo, authorization);
        if (order == null) {
            return;
        }
        Map<String, String> fields = new java.util.HashMap<>();
        Matcher m = STRING_FIELD.matcher(body);
        while (m.find()) {
            fields.put(m.group(1), m.group(2));
        }
        String receiver = fields.getOrDefault("receiver", order.receiver());
        String phone = fields.getOrDefault("phone", order.phone());
        String address = fields.getOrDefault("detail", order.address());
        if (!phone.matches("1\\d{10}") || receiver.length() > 20 || address.length() > 200) {
            problem(exchange, 400, "VALIDATION_FAILED", "请求参数校验失败");
            return;
        }
        if (!"PENDING_PAYMENT".equals(order.status()) && !"PENDING_SHIPMENT".equals(order.status())) {
            problem(exchange, 409, "ORDER_STATUS_CONFLICT", "订单 " + orderNo + " 已发货或已被修改，请刷新后重试");
            return;
        }
        StubOrder changed = new StubOrder(orderNo, order.status(), receiver, phone, address, true);
        orders.put(orderNo, changed);
        json(exchange, 200, orderJson(changed));
    }

    /** 第 7 章：支付单。待支付 PENDING，已取消 NONE，其余 PAID。 */
    private void payment(HttpExchange exchange, String orderNo, String authorization) throws IOException {
        StubOrder order = ownOrder(exchange, orderNo, authorization);
        if (order == null) {
            return;
        }
        String status = switch (order.status()) {
            case "PENDING_PAYMENT" -> "PENDING";
            case "CANCELLED", "CLOSED" -> "NONE";
            default -> "PAID";
        };
        String paidAt = "PAID".equals(status) ? "\"2026-11-08T09:31:00\"" : "null";
        json(
                exchange,
                200,
                "{\"orderNo\":\"" + orderNo + "\",\"status\":\"" + status + "\",\"paidAt\":" + paidAt + "}");
    }

    /** 演示账号自己的订单；令牌不对 401（新用户的令牌有效但没有订单），别人的 / 不存在 404。已经写好错误响应时返回 null。 */
    private StubOrder ownOrder(HttpExchange exchange, String orderNo, String authorization) throws IOException {
        if (!("Bearer " + TOKEN).equals(authorization) && !("Bearer " + NEW_USER_TOKEN).equals(authorization)) {
            unauthorized(exchange);
            return null;
        }
        StubOrder order = ("Bearer " + TOKEN).equals(authorization) ? orders.get(orderNo) : null;
        if (order == null) {
            problem(exchange, 404, "ORDER_NOT_FOUND", "订单 " + orderNo + " 不存在");
        }
        return order;
    }

    /** 订单详情 JSON：两笔老订单没改过时原样返回老的 JSON；其余按当前状态拼（带收货信息）。 */
    private static String orderJson(StubOrder order) {
        if (!order.modified() && ORDER_NO.equals(order.orderNo())) {
            return ORDER_JSON;
        }
        if (!order.modified() && COMPLETED_ORDER_NO.equals(order.orderNo())) {
            return COMPLETED_ORDER_JSON;
        }
        boolean paid = !"PENDING_PAYMENT".equals(order.status()) && !"CANCELLED".equals(order.status());
        return "{\"orderNo\":\"" + order.orderNo() + "\",\"status\":\"" + order.status()
                + "\",\"totalAmount\":\"39.90\",\"freightAmount\":\"0.00\",\"discountAmount\":\"0.00\","
                + "\"payAmount\":\"39.90\",\"receiver\":{\"name\":\"" + order.receiver() + "\",\"phone\":\""
                + order.phone() + "\",\"address\":\"" + order.address() + "\"},\"remark\":null,"
                + "\"items\":[{\"skuId\":\"20001\",\"productName\":\"青柚 · 红心柚 2 只装\",\"unitPrice\":\"39.90\","
                + "\"quantity\":1,\"itemAmount\":\"39.90\"}],\"createdAt\":\"2026-11-08T09:30:00\",\"paidAt\":"
                + (paid ? "\"2026-11-08T09:31:00\"" : "null") + "}";
    }

    /** 路径中间的订单号：/api/orders/123/cancel → 123。 */
    private static String between(String path, String suffix) {
        return path.substring("/api/orders/".length(), path.length() - suffix.length());
    }

    /** 「我的订单」第一页：size 缺省 20，和商城一样收紧到 1～50；只认演示账号和新用户的令牌。 */
    private static void listOrders(HttpExchange exchange, String query, String authorization) throws IOException {
        List<String> items;
        if (("Bearer " + TOKEN).equals(authorization)) {
            items = ORDER_LIST_ITEMS;
        } else if (("Bearer " + NEW_USER_TOKEN).equals(authorization)) {
            items = List.of();
        } else {
            unauthorized(exchange);
            return;
        }
        int size = 20;
        if (query != null && query.startsWith("size=")) {
            size = Math.clamp(Integer.parseInt(query.substring("size=".length())), 1, 50);
        }
        List<String> page = items.subList(0, Math.min(size, items.size()));
        boolean hasMore = items.size() > page.size();
        json(
                exchange,
                200,
                "{\"items\":[" + String.join(",", page) + "],\"nextCursor\":" + (hasMore ? "\"stub-cursor\"" : "null")
                        + ",\"hasMore\":" + hasMore + "}");
    }

    /** 「我的」：第 6 章用它确认令牌属于哪个用户。 */
    private static void me(HttpExchange exchange, String authorization) throws IOException {
        if (("Bearer " + TOKEN).equals(authorization)) {
            json(exchange, 200, "{\"userId\":\"10001\",\"nickname\":\"演示用户\",\"points\":120}");
        } else if (("Bearer " + NEW_USER_TOKEN).equals(authorization)) {
            json(exchange, 200, "{\"userId\":\"10002\",\"nickname\":\"新用户\",\"points\":0}");
        } else {
            unauthorized(exchange);
        }
    }

    /** 网关（Spring Security 资源服务器）的 401：只有 WWW-Authenticate 头，没有响应体。 */
    private static void unauthorized(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
        exchange.sendResponseHeaders(401, -1);
        exchange.close();
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
