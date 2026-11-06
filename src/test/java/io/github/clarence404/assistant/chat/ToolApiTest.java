package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.assistant.mall.StubMallApi;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 第 3 章：对话接口带上商城令牌、用上工具，并把工具调用记录返回给页面。
 *
 * <p>验证：{@code Authorization: Bearer <令牌>} 请求头被取出来、经 ToolContext 交给工具（假商城收到同一个令牌）；阻塞接口的
 * {@code tools} 字段列出调用过的工具、来源、参数和结果；流式接口在 intent 之后、文字之前推 {@code tool} 事件；
 * 不是 Bearer 格式的请求头按未登录处理；响应里不回显令牌。
 *
 * <p>手法：同 {@link FakeModelWebTest}，整个应用跑起来，模型换成 {@link FakeChatModel}，商城换成 {@code StubMallApi}。
 */
class ToolApiTest extends FakeModelWebTest {

    private static final String ORDER_INTENT = "{\"intent\":\"ORDER_STATUS\",\"confidence\":0.9,\"orderNo\":\""
            + StubMallApi.ORDER_NO + "\",\"skuId\":null}";

    @Test
    void chatForwardsTokenToToolAndReturnsToolRecords() throws Exception {
        model.willClassify(ORDER_INTENT);
        model.willReply("您的订单目前是「待发货」。");

        String body = mockMvc.perform(chat("我的订单 " + StubMallApi.ORDER_NO + " 到哪了")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + StubMallApi.TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("您的订单目前是「待发货」。"))
                .andExpect(jsonPath("$.tools.length()").value(1))
                .andExpect(jsonPath("$.tools[0].name").value("queryOrderStatus"))
                .andExpect(jsonPath("$.tools[0].source").value("DIRECT"))
                .andExpect(jsonPath("$.tools[0].arguments").value(containsString(StubMallApi.ORDER_NO)))
                .andExpect(jsonPath("$.tools[0].result").value(containsString("待发货")))
                .andExpect(jsonPath("$.tools[0].durationMs").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(MALL.requests())
                .singleElement()
                .satisfies(r -> assertThat(r.authorization()).isEqualTo("Bearer " + StubMallApi.TOKEN));
        assertThat(body).doesNotContain(StubMallApi.TOKEN);
    }

    /** 第 3 章补充：没给订单号时直接列出最近的订单，记录照样出现在 tools 字段里，页面调试面板不用改就能显示。 */
    @Test
    void orderQuestionWithoutOrderNoReturnsListRecord() throws Exception {
        model.willClassify("{\"intent\":\"ORDER_STATUS\",\"confidence\":0.9,\"orderNo\":null,\"skuId\":null}");

        String body = mockMvc.perform(chat("我有哪些订单").header(HttpHeaders.AUTHORIZATION, "Bearer " + StubMallApi.TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tools.length()").value(1))
                .andExpect(jsonPath("$.tools[0].name").value("listMyOrders"))
                .andExpect(jsonPath("$.tools[0].source").value("DIRECT"))
                .andExpect(jsonPath("$.tools[0].result").value(containsString(StubMallApi.COMPLETED_ORDER_NO)))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).doesNotContain(StubMallApi.TOKEN);
    }

    /** 意图识别没抽到 SKU，由模型自己决定调 queryProduct；商品匿名可查。 */
    @Test
    void modelCanCallToolThroughHttpWithoutLogin() throws Exception {
        model.willCallTool("queryProduct", "{\"skuId\":\"20001\"}");
        model.willReply("红心柚目前有货，价格 39.90 元。");

        mockMvc.perform(chat("红心柚那个 20001 还有吗"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tools[0].name").value("queryProduct"))
                .andExpect(jsonPath("$.tools[0].source").value("MODEL"))
                .andExpect(jsonPath("$.tools[0].result").value(containsString("\"inStock\":true")));
    }

    @Test
    void nonBearerAuthorizationIsTreatedAsNotLoggedIn() throws Exception {
        model.willClassify(ORDER_INTENT);

        mockMvc.perform(chat("我的订单 " + StubMallApi.ORDER_NO + " 到哪了").header(HttpHeaders.AUTHORIZATION, "Basic abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tools[0].result").value(containsString("登录")));

        assertThat(MALL.requests()).isEmpty();
    }

    @Test
    void streamSendsToolEventsBeforeTokens() throws Exception {
        model.willClassify(ORDER_INTENT);
        model.willStream("您的订单", "待发货。");

        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + StubMallApi.TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"我的订单 " + StubMallApi.ORDER_NO + " 到哪了\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(5_000);

        List<ChatStreamApiTest.Sse> events = ChatStreamApiTest.parse(result);
        assertThat(events)
                .extracting(ChatStreamApiTest.Sse::event)
                .containsExactly("intent", "tool", "token", "token", "done");
        assertThat(events.get(1).json().get("name").asString()).isEqualTo("queryOrderStatus");
        assertThat(events.get(1).json().get("result").asString()).contains("待发货");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain(StubMallApi.TOKEN);
    }

    private static MockHttpServletRequestBuilder chat(String message) {
        return post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"" + message + "\"}");
    }
}
