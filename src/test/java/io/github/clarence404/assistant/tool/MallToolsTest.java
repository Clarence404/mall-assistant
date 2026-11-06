package io.github.clarence404.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.mall.MallApiClient;
import io.github.clarence404.assistant.mall.MallApiProperties;
import io.github.clarence404.assistant.mall.MallFailure;
import io.github.clarence404.assistant.mall.MallModels;
import io.github.clarence404.assistant.mall.MallResult;
import io.github.clarence404.assistant.mall.StubMallApi;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 第 3 章：工具 {@link MallTools#queryOrderStatus} / {@link MallTools#queryProduct}，以及补充的 {@link MallTools#listMyOrders}，
 * 对着假商城网关的各种情况。
 *
 * <p>验证：查到时结果已经翻成「给模型直接用」的样子（中文状态、商品摘要、格式化时间、没有物流的说明）；没登录、参数格式不对时
 * 根本不发请求；查不到、别人的订单、令牌过期、商城挂了，都返回 {@code found=false} + 能说给用户听的原因，<b>不抛异常</b>。
 * 另外检查 Spring AI 从注解生成的工具定义：参数 Schema 里只有业务参数，没有 ToolContext（令牌不会让模型填）。
 *
 * <p>列出订单（第 3 章补充）：列表接口没有商品，工具逐笔补查详情拼出商品摘要；空列表是「查到了，没有订单」；模型填的 size 收紧到
 * 1～10；补查失败时订单照样列出、只是不带商品摘要。
 *
 * <p>手法：直接 new 出工具，对着 {@link StubMallApi} 发真实 HTTP 请求，再看它收到了哪些请求；最后两个测试经 {@link ToolCallback}
 * 调用，走的是和模型调用时完全一样的「JSON 参数进、JSON 结果出」的入口。
 */
class MallToolsTest {

    private static StubMallApi mall;
    private static MallTools tools;

    private static final ToolContext LOGGED_IN = new ToolContext(MallTools.context(StubMallApi.TOKEN));
    private static final ToolContext ANONYMOUS = new ToolContext(MallTools.context(null));

    @BeforeAll
    static void startStub() {
        mall = StubMallApi.start();
        tools = new MallTools(
                new MallApiClient(new MallApiProperties(mall.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(5))));
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
    void ownOrderIsTranslatedForTheModel() {
        OrderLookup result = tools.queryOrderStatus(StubMallApi.ORDER_NO, LOGGED_IN);

        assertThat(result.found()).isTrue();
        assertThat(result.reason()).isNull();
        assertThat(result.orderNo()).isEqualTo(StubMallApi.ORDER_NO);
        assertThat(result.status()).isEqualTo("待发货（已付款，商家还没发货）");
        assertThat(result.payAmount()).isEqualTo("39.90");
        assertThat(result.items()).isEqualTo("青柚 · 红心柚 2 只装 ×1");
        assertThat(result.createdAt()).isEqualTo("2026-11-05 15:58");
        assertThat(result.logistics()).isEqualTo(OrderLookup.NO_LOGISTICS);
    }

    @Test
    void notLoggedInAsksToLoginWithoutCallingMall() {
        OrderLookup result = tools.queryOrderStatus(StubMallApi.ORDER_NO, ANONYMOUS);

        assertThat(result.found()).isFalse();
        assertThat(result.reason()).contains("登录");
        assertThat(mall.requests()).isEmpty();
        assertThat(tools.queryOrderStatus(StubMallApi.ORDER_NO, null).reason())
                .as("没有 ToolContext 也按未登录处理")
                .contains("登录");
    }

    /** 参数是模型生成的，可能抄错、可能编造：格式不对直接返回原因，不拿去调商城。 */
    @Test
    void malformedOrderNoIsRejectedWithoutCallingMall() {
        for (String bad : new String[] {"12345", "ABC1849203385720832512", "", null}) {
            OrderLookup result = tools.queryOrderStatus(bad, LOGGED_IN);
            assertThat(result.found()).as(bad).isFalse();
            assertThat(result.reason()).as(bad).contains("格式不对");
        }
        assertThat(mall.requests()).isEmpty();
        assertThat(tools.queryOrderStatus(" " + StubMallApi.ORDER_NO + " ", LOGGED_IN)
                        .found())
                .as("首尾空白去掉后再校验")
                .isTrue();
    }

    @Test
    void someoneElsesOrderIsNotFound() {
        OrderLookup result = tools.queryOrderStatus(StubMallApi.OTHER_USERS_ORDER_NO, LOGGED_IN);

        assertThat(result.found()).isFalse();
        assertThat(result.reason()).contains("没有找到").contains("只能查自己的订单");
        assertThat(result.status()).isNull();
    }

    @Test
    void expiredTokenAsksToLoginAgain() {
        OrderLookup result =
                tools.queryOrderStatus(StubMallApi.ORDER_NO, new ToolContext(MallTools.context("expired-token")));

        assertThat(result.found()).isFalse();
        assertThat(result.reason()).contains("重新登录");
    }

    @Test
    void mallFailureBecomesStructuredUnavailable() {
        mall.failWith(500);

        assertThat(tools.queryOrderStatus(StubMallApi.ORDER_NO, LOGGED_IN).reason())
                .contains("暂时不可用");
        assertThat(tools.queryProduct("20001").reason()).contains("暂时不可用");
    }

    @Test
    void listMyOrdersTranslatesEveryOrderAndAddsItems() {
        OrderListLookup result = tools.listMyOrders(null, LOGGED_IN);

        assertThat(result.found()).isTrue();
        assertThat(result.reason()).isNull();
        assertThat(result.orders()).hasSize(2);
        assertThat(result.orders().getFirst())
                .isEqualTo(new OrderListLookup.Brief(
                        StubMallApi.ORDER_NO, "待发货（已付款，商家还没发货）", "39.90", "青柚 · 红心柚 2 只装 ×1", "2026-11-05 15:58"));
        assertThat(result.orders().getLast().status()).startsWith("已完成");
        assertThat(result.orders().getLast().items()).isEqualTo("青柚 · 白心柚 1 只装 ×1、青柚 · 柚子茶 500g ×1");
        assertThat(result.hasMore()).isFalse();
        assertThat(result.note()).isEqualTo(OrderListLookup.LIST_NOTE);
        assertThat(result.logistics()).isEqualTo(OrderLookup.NO_LOGISTICS);
        // 1 次列表 + 2 次补查商品，全部带的是当前用户的令牌
        assertThat(mall.requests())
                .extracting(StubMallApi.Request::path)
                .containsExactly(
                        "/api/orders",
                        "/api/orders/" + StubMallApi.ORDER_NO,
                        "/api/orders/" + StubMallApi.COMPLETED_ORDER_NO);
        assertThat(mall.requests())
                .allSatisfy(r -> assertThat(r.authorization()).isEqualTo("Bearer " + StubMallApi.TOKEN));
    }

    /** 没下过单：found=true + 空列表，提示模型如实说「还没有订单」，而不是当成查询失败。 */
    @Test
    void listMyOrdersWithNoOrdersIsFoundButEmpty() {
        OrderListLookup result = tools.listMyOrders(5, new ToolContext(MallTools.context(StubMallApi.NEW_USER_TOKEN)));

        assertThat(result.found()).isTrue();
        assertThat(result.orders()).isEmpty();
        assertThat(result.note()).isEqualTo(OrderListLookup.EMPTY_NOTE);
    }

    @Test
    void listMyOrdersNotLoggedInAsksToLoginWithoutCallingMall() {
        OrderListLookup result = tools.listMyOrders(5, ANONYMOUS);

        assertThat(result.found()).isFalse();
        assertThat(result.reason()).contains("请先在页面右上角登录");
        assertThat(result.orders()).isNull();
        assertThat(tools.listMyOrders(5, null).reason()).contains("登录");
        assertThat(mall.requests()).isEmpty();
    }

    @Test
    void listMyOrdersExpiredTokenOrMallDown() {
        assertThat(tools.listMyOrders(5, new ToolContext(MallTools.context("expired-token")))
                        .reason())
                .contains("重新登录");

        mall.failWith(503);
        OrderListLookup down = tools.listMyOrders(5, LOGGED_IN);
        assertThat(down.found()).isFalse();
        assertThat(down.reason()).contains("暂时不可用");
    }

    /** size 是模型填的：0、负数、100 都收紧到 1～10，没填用默认 5。 */
    @Test
    void listMyOrdersClampsSize() {
        for (Integer size : new Integer[] {0, -3, 1, 100, null}) {
            mall.reset();
            tools.listMyOrders(size, LOGGED_IN);
            int expected = size == null ? MallTools.DEFAULT_LIST_SIZE : Math.clamp(size, 1, 10);
            assertThat(mall.requests().getFirst().query()).as("size=" + size).isEqualTo("size=" + expected);
        }
        mall.reset();
        OrderListLookup one = tools.listMyOrders(1, LOGGED_IN);
        assertThat(one.orders()).hasSize(1);
        assertThat(one.hasMore()).as("还有更早的订单没列出来").isTrue();
    }

    /** 补查商品是尽力而为：列表成功、详情失败时，订单照样列出，只是没有商品摘要，也不再补查后面的。 */
    @Test
    void listMyOrdersStillListsWhenItemLookupFails() {
        MallApiClient flaky =
                new MallApiClient(new MallApiProperties(mall.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(5))) {
                    private int detailCalls;

                    @Override
                    public MallResult<MallModels.Order> getOrder(String accessToken, String orderNo) {
                        detailCalls++;
                        assertThat(detailCalls).as("第一次补查失败后不再补查").isEqualTo(1);
                        return MallResult.failed(MallFailure.UNAVAILABLE, null, "模拟详情接口失败");
                    }
                };

        OrderListLookup result = new MallTools(flaky).listMyOrders(5, LOGGED_IN);

        assertThat(result.found()).isTrue();
        assertThat(result.orders()).hasSize(2).allSatisfy(o -> {
            assertThat(o.items()).isNull();
            assertThat(o.status()).isNotBlank();
        });
    }

    @Test
    void productFoundWithStock() {
        ProductLookup result = tools.queryProduct("20001");

        assertThat(result.found()).isTrue();
        assertThat(result.name()).isEqualTo("青柚 · 红心柚 2 只装");
        assertThat(result.price()).isEqualTo("39.90");
        assertThat(result.onShelf()).isTrue();
        assertThat(result.inStock()).isTrue();
    }

    @Test
    void offShelfProductIsStillFoundButNotInStock() {
        ProductLookup result = tools.queryProduct("20004");

        assertThat(result.found()).isTrue();
        assertThat(result.onShelf()).isFalse();
        assertThat(result.inStock()).isFalse();
    }

    @Test
    void unknownOrMalformedProduct() {
        assertThat(tools.queryProduct("99999").reason()).contains("没有编号为 99999 的商品");
        assertThat(tools.queryProduct("二万零一").reason()).contains("格式不对");
        assertThat(mall.requests()).hasSize(1);
    }

    /**
     * 工具定义就是给模型看的「说明书」：名字、说明、参数 Schema 都由注解生成。ToolContext 参数不出现在 Schema 里，
     * 模型既看不到也填不了令牌。
     */
    @Test
    void toolDefinitionsExposeOnlyBusinessParameters() {
        ToolDefinition order = tools.callback("queryOrderStatus").getToolDefinition();
        ToolDefinition product = tools.callback("queryProduct").getToolDefinition();

        ToolDefinition list = tools.callback("listMyOrders").getToolDefinition();

        assertThat(tools.callbacks())
                .as("按名字排序，工具清单的顺序每次启动都一样")
                .extracting(c -> c.getToolDefinition().name())
                .containsExactly("listMyOrders", "queryOrderStatus", "queryProduct");
        assertThat(order.description()).contains("订单号");
        assertThat(order.inputSchema())
                .contains("\"orderNo\"")
                .doesNotContain("toolContext")
                .doesNotContain("Token");
        assertThat(product.inputSchema()).contains("\"skuId\"").doesNotContain("toolContext");
        assertThat(list.description()).contains("没给订单号");
        assertThat(list.inputSchema()).contains("\"size\"").doesNotContain("toolContext");
    }

    /** 经 ToolCallback 调用：JSON 参数进、JSON 结果出，令牌从 ToolContext 来；没查到的字段不输出，结果更短。 */
    @Test
    void callbackTakesJsonArgumentsAndTokenFromToolContext() {
        ToolCallback order = tools.callback("queryOrderStatus");

        String json = order.call("{\"orderNo\":\"" + StubMallApi.ORDER_NO + "\"}", LOGGED_IN);

        assertThat(json).contains("\"found\":true").contains("待发货").doesNotContain("\"reason\"");
        assertThat(mall.requests().getFirst().authorization()).isEqualTo("Bearer " + StubMallApi.TOKEN);

        String anonymous = order.call("{\"orderNo\":\"" + StubMallApi.ORDER_NO + "\"}", ANONYMOUS);
        assertThat(anonymous).contains("\"found\":false").contains("登录");
    }
}
