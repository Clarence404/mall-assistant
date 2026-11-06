package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.ConnectException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.ResourceAccessException;

/**
 * 第 2 章：POST /api/intent，以及对话接口先识别意图、超出范围直接拒答。
 *
 * <p>验证：意图接口返回结构化结果；模型输出解析不了时重试一次后兜底成 UNKNOWN（照样 200，而不是报错）；模型连不上时 503；
 * 对话接口先调一次意图识别、再调一次回答；把握够大的 OUT_OF_SCOPE 直接回拒答话术，省掉回答模型那次调用；把握不够的照常回答；
 * 流式接口第一个事件是 intent。
 *
 * <p>手法：{@link FakeChatModel} 按「请求里有没有输出 Schema」区分两类调用，分别预设，并用 {@code classificationPrompts()} /
 * {@code answerPrompts()} 数各调了几次，以此断言「有没有调回答模型」。
 */
class IntentApiTest extends FakeModelWebTest {

    private static final String ORDER_STATUS =
            "{\"intent\":\"ORDER_STATUS\",\"confidence\":0.9,\"orderNo\":\"1849203385720832512\",\"skuId\":null}";
    private static final String OUT_OF_SCOPE =
            "{\"intent\":\"OUT_OF_SCOPE\",\"confidence\":0.9,\"orderNo\":null,\"skuId\":null}";

    @Test
    void intentEndpointReturnsStructuredResult() throws Exception {
        model.willClassify(ORDER_STATUS);

        send("/api/intent", "我的订单 1849203385720832512 到哪了")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("ORDER_STATUS"))
                .andExpect(jsonPath("$.confidence").value(0.9))
                .andExpect(jsonPath("$.orderNo").value("1849203385720832512"));

        assertThat(model.answerPrompts()).isEmpty();
    }

    @Test
    void intentEndpointFallsBackToUnknownInsteadOfFailing() throws Exception {
        model.willClassify("这不是 JSON");

        send("/api/intent", "随便说点什么")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("UNKNOWN"))
                .andExpect(jsonPath("$.confidence").value(0.0));

        assertThat(model.classificationPrompts()).hasSize(2); // 第一次解析失败，带上更严格的提醒重试一次
    }

    @Test
    void intentEndpointMapsModelUnavailableTo503() throws Exception {
        model.willFailClassification(
                new ResourceAccessException("I/O error", new ConnectException("Connection refused")));

        send("/api/intent", "在吗")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_UNAVAILABLE"));
    }

    @Test
    void chatReplyIncludesIntent() throws Exception {
        model.willClassify(ORDER_STATUS);
        model.willReply("抱歉，我这边暂时查不到实时物流。");

        send("/api/chat", "我的订单 1849203385720832512 到哪了")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("抱歉，我这边暂时查不到实时物流。"))
                .andExpect(jsonPath("$.intent.intent").value("ORDER_STATUS"))
                .andExpect(jsonPath("$.intent.orderNo").value("1849203385720832512"));

        assertThat(model.classificationPrompts()).hasSize(1);
        assertThat(model.answerPrompts()).hasSize(1);
    }

    @Test
    void outOfScopeIsRefusedWithoutCallingAnswerModel() throws Exception {
        model.willClassify(OUT_OF_SCOPE);

        send("/api/chat", "帮我写一段 Python 冒泡排序")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent.intent").value("OUT_OF_SCOPE"))
                .andExpect(jsonPath("$.reply").value(containsString("示例商城")));

        assertThat(model.answerPrompts()).isEmpty();
    }

    /** OUT_OF_SCOPE 但置信度 0.3 低于拒答门槛（默认 0.6）：宁可多答一句，也不错拒正常问题。 */
    @Test
    void lowConfidenceOutOfScopeIsStillAnswered() throws Exception {
        model.willClassify("{\"intent\":\"OUT_OF_SCOPE\",\"confidence\":0.3,\"orderNo\":null,\"skuId\":null}");
        model.willReply("您好，请问想了解哪款商品？");

        send("/api/chat", "推荐一个")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("您好，请问想了解哪款商品？"));

        assertThat(model.answerPrompts()).hasSize(1);
    }

    @Test
    void streamStartsWithIntentEvent() throws Exception {
        model.willClassify(ORDER_STATUS);
        model.willStream("抱歉，", "暂时查不到物流。");

        List<ChatStreamApiTest.Sse> events = ChatStreamApiTest.parse(stream("我的订单 1849203385720832512 到哪了"));

        // 第 3 章起，查订单的意图会先直接查一次订单，多出 tool 事件；这里只看 intent 在最前、回答照常推完
        assertThat(events)
                .extracting(ChatStreamApiTest.Sse::event)
                .filteredOn(name -> !"tool".equals(name))
                .containsExactly("intent", "token", "token", "done");
        assertThat(events.getFirst().json().get("intent").asString()).isEqualTo("ORDER_STATUS");
        assertThat(events.getFirst().json().get("orderNo").asString()).isEqualTo("1849203385720832512");
    }

    @Test
    void streamRefusesOutOfScopeWithoutCallingAnswerModel() throws Exception {
        model.willClassify(OUT_OF_SCOPE);

        List<ChatStreamApiTest.Sse> events = ChatStreamApiTest.parse(stream("明天北京天气怎么样"));

        assertThat(events).extracting(ChatStreamApiTest.Sse::event).containsExactly("intent", "token", "done");
        assertThat(events.get(1).json().get("text").asString()).contains("示例商城");
        assertThat(model.answerPrompts()).isEmpty();
    }

    /** 意图识别这一步就失败了：连 intent 事件都没有，流里只有一个 error 事件。 */
    @Test
    void streamTurnsClassificationFailureIntoErrorEvent() throws Exception {
        model.willFailClassification(
                new ResourceAccessException("I/O error", new ConnectException("Connection refused")));

        List<ChatStreamApiTest.Sse> events = ChatStreamApiTest.parse(stream("在吗"));

        assertThat(events).extracting(ChatStreamApiTest.Sse::event).containsExactly("error");
        assertThat(events.getFirst().json().get("code").asString()).isEqualTo("MODEL_UNAVAILABLE");
    }

    private ResultActions send(String path, String message) throws Exception {
        return mockMvc.perform(
                post(path).contentType(MediaType.APPLICATION_JSON).content(json(message)));
    }

    private MvcResult stream(String message) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(json(message)))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(5_000);
        return result;
    }

    /** 手拼请求体：这里的测试消息不含引号和反斜杠，不需要转义。 */
    private static String json(String message) {
        return "{\"message\":\"" + message + "\"}";
    }
}
