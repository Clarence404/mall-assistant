package io.github.clarence404.assistant.chat;

import static io.github.clarence404.assistant.agent.AgentFixtures.act;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.assistant.mall.StubMallApi;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 第 7 章：Agent 与确认接口（整个应用跑起来，模型是假的，商城是假网关）。
 *
 * <ul>
 *   <li>{@code POST /api/chat}：办事请求的响应多了 {@code agent}（每一步）与 {@code confirmation}（确认卡片）
 *   <li>{@code POST /api/chat/stream}：{@code intent → memory → step × N → confirm → token → done}
 *   <li>{@code POST /api/agent/confirmations/{id}}：确认执行、重复确认返回同一结果、取消、冲突 409、别人 404、请求里塞参数无效、格式不对 400
 *   <li>{@code GET /api/agent/after-sales}：确认后能看到自己的售后申请单
 * </ul>
 */
class AgentApiTest extends FakeModelWebTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TOKEN = StubMallApi.TOKEN;
    private static final String PENDING = StubMallApi.PENDING_PAYMENT_ORDER_NO;
    private static final String AFTER_SALES =
            "{\"intent\":\"AFTER_SALES\",\"confidence\":0.9,\"orderNo\":null,\"skuId\":null}";

    private static MockHttpServletRequestBuilder chat(String message, String conversationId) {
        return post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .content("{\"message\":\"" + message + "\",\"conversationId\":\"" + conversationId + "\"}");
    }

    private static MockHttpServletRequestBuilder confirm(String id, String body, String token) {
        MockHttpServletRequestBuilder request = post("/api/agent/confirmations/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static String decision(String decision, String conversationId) {
        return "{\"decision\":\"" + decision + "\",\"conversationId\":\"" + conversationId + "\"}";
    }

    /** 让 Agent 提出一个写操作，返回确认 ID。 */
    private String propose(String conversationId, String... script) throws Exception {
        model.willClassify(AFTER_SALES);
        model.willAct(script);
        MvcResult result = mockMvc.perform(chat("帮我办一下，新地址是杭州市西湖区文三路 100 号", conversationId))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("confirmation")
                .get("id")
                .asString();
    }

    /** 阻塞接口：agent 字段有每一步，confirmation 字段是确认卡片；商城没收到写请求。 */
    @Test
    void blockingReplyCarriesStepsAndConfirmation() throws Exception {
        model.willClassify(AFTER_SALES);
        model.willAct(act("queryOrder", "orderNo", PENDING), act("cancelOrder", "orderNo", PENDING));

        mockMvc.perform(chat("订单 " + PENDING + " 买错了，帮我取消", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agent.mode").value("loop"))
                .andExpect(jsonPath("$.agent.stopReason").value("AWAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.agent.steps.length()").value(2))
                .andExpect(jsonPath("$.agent.steps[0].kind").value("READ"))
                .andExpect(jsonPath("$.agent.steps[0].thought").isString())
                .andExpect(jsonPath("$.agent.steps[1].kind").value("PROPOSE"))
                .andExpect(jsonPath("$.confirmation.action").value("cancelOrder"))
                .andExpect(jsonPath("$.confirmation.orderNo").value(PENDING))
                .andExpect(jsonPath("$.confirmation.expiresAt").isString())
                .andExpect(jsonPath("$.confirmation.ttlSeconds").value(300));
        assertThat(MALL.writes()).isEmpty();
    }

    /** 流式：intent → memory → step → step → confirm → token → done。 */
    @Test
    void streamEventOrder() throws Exception {
        model.willClassify(AFTER_SALES);
        model.willAct(act("queryOrder", "orderNo", PENDING), act("cancelOrder", "orderNo", PENDING));
        String id = UUID.randomUUID().toString();

        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .content("{\"message\":\"帮我取消订单 " + PENDING + "\",\"conversationId\":\"" + id + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(10_000);
        List<ChatStreamApiTest.Sse> events = ChatStreamApiTest.parse(result);

        assertThat(events)
                .extracting(ChatStreamApiTest.Sse::event)
                .containsExactly("intent", "memory", "step", "step", "confirm", "token", "done");
        JsonNode confirm = events.get(4).json();
        assertThat(confirm.get("orderNo").asString()).isEqualTo(PENDING);
        assertThat(events.get(3).json().get("kind").asString()).isEqualTo("PROPOSE");
        assertThat(events.get(5).json().get("text").asString()).contains("点「确认」后才会办理");
    }

    /** 确认：执行，订单变成已取消；同样的决定再提交一次返回同一个结果，商城只收到一次；再点取消 409。 */
    @Test
    void confirmIsExecutedOnce() throws Exception {
        String conversation = UUID.randomUUID().toString();
        String id =
                propose(conversation, act("queryOrder", "orderNo", PENDING), act("cancelOrder", "orderNo", PENDING));

        String first = mockMvc.perform(confirm(id, decision("approve", conversation), TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.decision").value("APPROVE"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String second = mockMvc.perform(confirm(id, decision("APPROVE", conversation), TOKEN))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(second).isEqualTo(first);
        assertThat(MALL.orderStatus(PENDING)).isEqualTo("CANCELLED");
        assertThat(MALL.writes()).hasSize(1);
        mockMvc.perform(confirm(id, decision("reject", conversation), TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIRMATION_ALREADY_DECIDED"));
    }

    /** 别人的确认 ID、别的会话、匿名、不存在：都是 404；什么都不执行。 */
    @Test
    void othersGet404() throws Exception {
        String conversation = UUID.randomUUID().toString();
        String id =
                propose(conversation, act("queryOrder", "orderNo", PENDING), act("cancelOrder", "orderNo", PENDING));

        mockMvc.perform(confirm(id, decision("approve", conversation), StubMallApi.NEW_USER_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONFIRMATION_NOT_FOUND"));
        mockMvc.perform(confirm(id, decision("approve", UUID.randomUUID().toString()), TOKEN))
                .andExpect(status().isNotFound());
        mockMvc.perform(confirm(id, decision("approve", conversation), null)).andExpect(status().isNotFound());
        mockMvc.perform(confirm(UUID.randomUUID().toString(), decision("approve", conversation), TOKEN))
                .andExpect(status().isNotFound());
        mockMvc.perform(confirm(id, decision("approve", conversation), "forged-token"))
                .andExpect(status().isUnauthorized());
        assertThat(MALL.writes()).isEmpty();
    }

    /** 请求体里塞别的订单号、别的地址：一律忽略，执行的是确认卡片上那一个。 */
    @Test
    void tamperedParametersAreIgnored() throws Exception {
        String conversation = UUID.randomUUID().toString();
        String id = propose(
                conversation,
                act("queryOrder", "orderNo", PENDING),
                act("changeAddress", "orderNo", PENDING, "address", "杭州市西湖区文三路 100 号"));

        mockMvc.perform(confirm(
                        id,
                        "{\"decision\":\"approve\",\"conversationId\":\"" + conversation + "\",\"orderNo\":\""
                                + StubMallApi.ORDER_NO + "\",\"params\":{\"address\":\"黑客的地址\"},"
                                + "\"address\":\"黑客的地址\"}",
                        TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));

        assertThat(MALL.orderAddress(PENDING)).isEqualTo("杭州市西湖区文三路 100 号");
        assertThat(MALL.orderAddress(StubMallApi.ORDER_NO)).isEqualTo("杭州市西湖区");
        assertThat(MALL.writes())
                .singleElement()
                .satisfies(r -> assertThat(r.body()).doesNotContain("黑客"));
    }

    /** 取消：什么都不执行；decision 不是 approve / reject 时 400。 */
    @Test
    void rejectAndBadDecision() throws Exception {
        String conversation = UUID.randomUUID().toString();
        String id =
                propose(conversation, act("queryOrder", "orderNo", PENDING), act("cancelOrder", "orderNo", PENDING));

        mockMvc.perform(confirm(id, decision("maybe", conversation), TOKEN)).andExpect(status().isBadRequest());
        mockMvc.perform(confirm(id, decision("reject", conversation), TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(MALL.writes()).isEmpty();
    }

    /** 售后：确认后在「我的售后申请单」里能看到，状态待人工审核；别的用户看不到，匿名是空列表。 */
    @Test
    void afterSalesApplicationIsListed() throws Exception {
        String conversation = UUID.randomUUID().toString();
        String shipped = StubMallApi.SHIPPED_ORDER_NO;
        String id = propose(
                conversation,
                act("queryOrder", "orderNo", shipped),
                act("applyAfterSales", "orderNo", shipped, "reason", "东西坏了"));

        mockMvc.perform(confirm(id, decision("approve", conversation), TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.afterSalesId").isString());

        mockMvc.perform(get("/api/agent/after-sales").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.confirmationId == '" + id + "')].status")
                        .value("PENDING_REVIEW"));
        mockMvc.perform(get("/api/agent/after-sales")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + StubMallApi.NEW_USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/agent/after-sales"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
