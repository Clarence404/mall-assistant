package io.github.clarence404.assistant.mall;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 第 3 章：商城客户端 {@link MallApiClient} 把各种 HTTP 响应归成 {@link MallResult}，不抛异常。
 *
 * <p>验证：成功时解析出订单、商品、令牌，订单请求带上 {@code Authorization: Bearer <令牌>}；404 读出 ProblemDetail 里的业务码；
 * 网关没有响应体的 401 也能归类；5xx、读超时、连接被拒都归为 UNAVAILABLE。第 3 章补充了「我的订单」列表：size 作为查询参数传过去、
 * 空列表也是成功、令牌不对 401、商城挂了 UNAVAILABLE，和其他接口同一套归类。
 *
 * <p>手法：{@link StubMallApi} 在本机随机端口起一个假网关，客户端真的发 HTTP 请求过去（不是 mock 方法调用），
 * 连超时、连接被拒这类网络层面的失败也能真实地测到。读超时用一个只等 300 毫秒的客户端配合假网关延迟 1 秒来触发。
 */
class MallApiClientTest {

    private static StubMallApi mall;
    private static MallApiClient client;

    @BeforeAll
    static void startStub() {
        mall = StubMallApi.start();
        client = new MallApiClient(new MallApiProperties(mall.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(5)));
    }

    @AfterAll
    static void stopStub() {
        mall.close();
    }

    @BeforeEach
    void reset() {
        mall.reset();
    }

    @Test
    void loginReturnsAccessToken() {
        MallResult<MallModels.Tokens> result = client.login("13800000000", "mall123");

        assertThat(result.isOk()).isTrue();
        assertThat(result.body().accessToken()).isEqualTo(StubMallApi.TOKEN);
        assertThat(result.body().expiresIn()).isEqualTo(1800);
    }

    @Test
    void wrongPasswordIsUnauthorizedWithBusinessCode() {
        MallResult<MallModels.Tokens> result = client.login("13800000000", "wrong");

        assertThat(result.failure()).isEqualTo(MallFailure.UNAUTHORIZED);
        assertThat(result.code()).isEqualTo("USER_LOGIN_FAILED");
    }

    @Test
    void ownOrderIsParsedAndTokenIsSentAsBearer() {
        MallResult<MallModels.Order> result = client.getOrder(StubMallApi.TOKEN, StubMallApi.ORDER_NO);

        assertThat(result.isOk()).isTrue();
        assertThat(result.body().status()).isEqualTo("PENDING_SHIPMENT");
        assertThat(result.body().payAmount()).isEqualTo("39.90");
        assertThat(result.body().items()).singleElement().satisfies(i -> {
            assertThat(i.productName()).isEqualTo("青柚 · 红心柚 2 只装");
            assertThat(i.quantity()).isEqualTo(1);
        });
        assertThat(result.body().createdAt()).isEqualTo(LocalDateTime.parse("2026-11-05T15:58:48.258"));
        assertThat(mall.requests()).singleElement().satisfies(r -> {
            assertThat(r.path()).isEqualTo("/api/orders/" + StubMallApi.ORDER_NO);
            assertThat(r.authorization()).isEqualTo("Bearer " + StubMallApi.TOKEN);
        });
    }

    /** 别人的订单：商城返回 404 ORDER_NOT_FOUND，和不存在的订单一模一样。 */
    @Test
    void someoneElsesOrderIsNotFound() {
        MallResult<MallModels.Order> result = client.getOrder(StubMallApi.TOKEN, StubMallApi.OTHER_USERS_ORDER_NO);

        assertThat(result.failure()).isEqualTo(MallFailure.NOT_FOUND);
        assertThat(result.code()).isEqualTo("ORDER_NOT_FOUND");
    }

    /** 网关的 401 没有响应体、也不是 JSON：照样按状态码归类，不报解析错误。 */
    @Test
    void invalidTokenIsUnauthorizedEvenWithoutBody() {
        MallResult<MallModels.Order> result = client.getOrder("expired-token", StubMallApi.ORDER_NO);

        assertThat(result.failure()).isEqualTo(MallFailure.UNAUTHORIZED);
        assertThat(result.code()).isNull();
    }

    @Test
    void orderListIsParsedWithSizeAndBearerToken() {
        MallResult<MallModels.OrderPage> result = client.listOrders(StubMallApi.TOKEN, 5);

        assertThat(result.isOk()).isTrue();
        assertThat(result.body().items())
                .extracting(MallModels.OrderListItem::orderNo)
                .containsExactly(StubMallApi.ORDER_NO, StubMallApi.COMPLETED_ORDER_NO);
        assertThat(result.body().items().getFirst().status()).isEqualTo("PENDING_SHIPMENT");
        assertThat(result.body().items().getFirst().createdAt())
                .isEqualTo(LocalDateTime.parse("2026-11-05T15:58:48.258"));
        assertThat(result.body().hasMore()).isFalse();
        assertThat(mall.requests()).singleElement().satisfies(r -> {
            assertThat(r.path()).isEqualTo("/api/orders");
            assertThat(r.query()).isEqualTo("size=5");
            assertThat(r.authorization()).isEqualTo("Bearer " + StubMallApi.TOKEN);
        });
    }

    /** 只取第一页：条数少于订单数时 hasMore=true，游标原样带回来（智能客服用不到，不往下翻）。 */
    @Test
    void orderListFirstPageReportsMore() {
        MallResult<MallModels.OrderPage> result = client.listOrders(StubMallApi.TOKEN, 1);

        assertThat(result.body().items()).hasSize(1);
        assertThat(result.body().hasMore()).isTrue();
        assertThat(result.body().nextCursor()).isNotBlank();
    }

    /** 新用户还没下过单：200 + 空列表，是成功而不是 NOT_FOUND。 */
    @Test
    void emptyOrderListIsStillOk() {
        MallResult<MallModels.OrderPage> result = client.listOrders(StubMallApi.NEW_USER_TOKEN, 5);

        assertThat(result.isOk()).isTrue();
        assertThat(result.body().items()).isEmpty();
    }

    @Test
    void orderListFailuresAreClassifiedLikeOtherCalls() {
        assertThat(client.listOrders("expired-token", 5).failure()).isEqualTo(MallFailure.UNAUTHORIZED);

        mall.failWith(500);
        assertThat(client.listOrders(StubMallApi.TOKEN, 5).failure()).isEqualTo(MallFailure.UNAVAILABLE);
    }

    @Test
    void productDetailIncludesStock() {
        MallResult<MallModels.ProductPage> result = client.getProduct("20001");

        assertThat(result.isOk()).isTrue();
        assertThat(result.body().product().name()).isEqualTo("青柚 · 红心柚 2 只装");
        assertThat(result.body().product().onShelf()).isTrue();
        assertThat(result.body().inStock()).isTrue();
        assertThat(mall.requests().getFirst().authorization())
                .as("商品详情匿名可查，不带令牌")
                .isNull();
    }

    @Test
    void unknownProductIsNotFoundAndBadSkuIsRejected() {
        assertThat(client.getProduct("99999").failure()).isEqualTo(MallFailure.NOT_FOUND);
        assertThat(client.getProduct("abc").failure()).isEqualTo(MallFailure.REJECTED);
    }

    @Test
    void serverErrorIsUnavailable() {
        mall.failWith(503);

        assertThat(client.getProduct("20001").failure()).isEqualTo(MallFailure.UNAVAILABLE);
        assertThat(client.getOrder(StubMallApi.TOKEN, StubMallApi.ORDER_NO).failure())
                .isEqualTo(MallFailure.UNAVAILABLE);
    }

    @Test
    void readTimeoutIsUnavailable() {
        mall.delay(Duration.ofSeconds(1));
        MallApiClient impatient =
                new MallApiClient(new MallApiProperties(mall.baseUrl(), Duration.ofSeconds(2), Duration.ofMillis(300)));

        MallResult<MallModels.ProductPage> result = impatient.getProduct("20001");

        assertThat(result.failure()).isEqualTo(MallFailure.UNAVAILABLE);
    }

    /** 网关没启动：连接被拒，快速返回 UNAVAILABLE。端口取一个刚刚关掉的空闲端口。 */
    @Test
    void connectionRefusedIsUnavailable() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        MallApiClient offline = new MallApiClient(
                new MallApiProperties("http://127.0.0.1:" + port, Duration.ofSeconds(2), Duration.ofSeconds(5)));

        assertThat(offline.getProduct("20001").failure()).isEqualTo(MallFailure.UNAVAILABLE);
    }

    /** 第 7 章：取消待支付的订单成功，返回取消后的订单；POST 带令牌。 */
    @Test
    void cancelPendingPaymentOrder() {
        MallResult<MallModels.Order> result =
                client.cancelOrder(StubMallApi.TOKEN, StubMallApi.PENDING_PAYMENT_ORDER_NO);

        assertThat(result.isOk()).isTrue();
        assertThat(result.body().status()).isEqualTo("CANCELLED");
        assertThat(mall.writes()).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("POST");
            assertThat(r.path()).isEqualTo("/api/orders/" + StubMallApi.PENDING_PAYMENT_ORDER_NO + "/cancel");
            assertThat(r.authorization()).isEqualTo("Bearer " + StubMallApi.TOKEN);
        });
    }

    /** 第 7 章：已付款的订单不能取消：409 归为 REJECTED，带商城的业务码和说明（说明可以直接给用户看）。 */
    @Test
    void cancelPaidOrderIsRejectedWithConflict() {
        MallResult<MallModels.Order> result = client.cancelOrder(StubMallApi.TOKEN, StubMallApi.ORDER_NO);

        assertThat(result.failure()).isEqualTo(MallFailure.REJECTED);
        assertThat(result.code()).isEqualTo("ORDER_STATUS_CONFLICT");
        assertThat(result.detail()).contains("已支付，不能取消");
        assertThat(client.cancelOrder(StubMallApi.TOKEN, StubMallApi.OTHER_USERS_ORDER_NO)
                        .failure())
                .isEqualTo(MallFailure.NOT_FOUND);
    }

    /** 第 7 章：改地址只传要改的字段（null 不输出），返回改完的订单（带收货信息）。 */
    @Test
    void changeAddressSendsOnlyGivenFields() {
        MallResult<MallModels.Order> result = client.changeAddress(
                StubMallApi.TOKEN,
                StubMallApi.PENDING_PAYMENT_ORDER_NO,
                new MallModels.AddressPatch(null, "13900000000", null));

        assertThat(result.isOk()).isTrue();
        assertThat(result.body().receiver().phone()).isEqualTo("13900000000");
        assertThat(result.body().receiver().address()).isEqualTo("杭州市西湖区");
        assertThat(mall.writes().getFirst().body()).isEqualTo("{\"phone\":\"13900000000\"}");
    }

    /** 第 7 章：已发货不能改地址（409）；手机号格式不对（400）；两者都归为 REJECTED。 */
    @Test
    void changeAddressConflictAndValidation() {
        MallResult<MallModels.Order> shipped = client.changeAddress(
                StubMallApi.TOKEN, StubMallApi.SHIPPED_ORDER_NO, new MallModels.AddressPatch("李四", null, null));
        MallResult<MallModels.Order> badPhone = client.changeAddress(
                StubMallApi.TOKEN,
                StubMallApi.PENDING_PAYMENT_ORDER_NO,
                new MallModels.AddressPatch(null, "123", null));

        assertThat(shipped.failure()).isEqualTo(MallFailure.REJECTED);
        assertThat(shipped.code()).isEqualTo("ORDER_STATUS_CONFLICT");
        assertThat(badPhone.failure()).isEqualTo(MallFailure.REJECTED);
        assertThat(badPhone.code()).isEqualTo("VALIDATION_FAILED");
    }

    /** 第 7 章：查支付单。 */
    @Test
    void paymentStatus() {
        assertThat(client.getPayment(StubMallApi.TOKEN, StubMallApi.SHIPPED_ORDER_NO)
                        .body()
                        .status())
                .isEqualTo("PAID");
        assertThat(client.getPayment(StubMallApi.TOKEN, StubMallApi.PENDING_PAYMENT_ORDER_NO)
                        .body()
                        .paidAt())
                .isNull();
        assertThat(client.getPayment("forged", StubMallApi.ORDER_NO).failure()).isEqualTo(MallFailure.UNAUTHORIZED);
    }
}
