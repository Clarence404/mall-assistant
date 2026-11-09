package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.assistant.mall.StubMallApi;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 第 6 章：会话接口与带会话 ID 的对话接口（整个应用跑起来，模型是假的，记忆是默认的内存存储）。
 *
 * <ul>
 *   <li>{@code POST /api/chat} 带 {@code conversationId}：响应多了 {@code memory} 字段；第二句的提示词里带着第一句；格式不对的 ID 400
 *   <li>{@code GET /api/conversations/{id}/messages}：按时间列出用户原话和回答；不存在、不是你的都 404；登录失效 401
 *   <li>{@code DELETE /api/conversations/{id}}：204，之后再查 404
 *   <li>流式：{@code intent → memory → token* → done}
 * </ul>
 *
 * <p>记忆在测试之间共用（同一个 Spring 上下文），所以每个测试用新的随机会话 ID。
 */
class ConversationApiTest extends FakeModelWebTest {

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static MockHttpServletRequestBuilder chat(String message, String conversationId) {
        return post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"" + message + "\",\"conversationId\":\"" + conversationId + "\"}");
    }

    private static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    /** 两句话：第二句带着第一句；memory 字段说明带了几轮。 */
    @Test
    void secondMessageCarriesTheFirst() throws Exception {
        String id = newId();
        model.willReply("您好，小王。");
        mockMvc.perform(chat("你好，我叫小王", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memory.conversationId").value(id))
                .andExpect(jsonPath("$.memory.store").value("memory"))
                .andExpect(jsonPath("$.memory.active").value(true))
                .andExpect(jsonPath("$.memory.historyTurns").value(0));

        model.willReply("您叫小王。");
        mockMvc.perform(chat("我叫什么", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memory.historyTurns").value(1))
                .andExpect(jsonPath("$.memory.droppedTurns").value(0))
                .andExpect(jsonPath("$.memory.historyChars").value("你好，我叫小王".length() + "您好，小王。".length()));

        assertThat(FakeChatModel.allText(model.answerPrompts().getLast())).contains("你好，我叫小王");
    }

    /** 不带会话 ID：和第 5 章一样，memory 不出现。 */
    @Test
    void withoutConversationIdThereIsNoMemory() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memory").doesNotExist());
    }

    /** 会话 ID 格式不对（不是小写 UUID）：400，不调模型。 */
    @Test
    void malformedConversationIdIsRejected() throws Exception {
        for (String bad : List.of("abc", "3F2B8C1E-9A4D-4E7B-8C21-5D6F7A8B9C0D", "../../etc/passwd")) {
            mockMvc.perform(chat("你好", bad)).andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/conversations/{id}/messages", "abc")).andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/conversations/{id}", "abc")).andExpect(status().isBadRequest());
        assertThat(model.prompts()).isEmpty();
    }

    /** 查看：按时间列出原话和回答（引用编号已去掉）；页面刷新后靠它恢复。 */
    @Test
    void messagesRestoreTheConversation() throws Exception {
        String id = newId();
        model.willReply("您好[1]。");
        mockMvc.perform(chat("你好", id)).andExpect(status().isOk());

        mockMvc.perform(get("/api/conversations/{id}/messages", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(id))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].text").value("你好"))
                .andExpect(jsonPath("$.messages[0].at").isString())
                .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.messages[1].text").value("您好。"));
    }

    /** 不存在的会话：404，带 code。 */
    @Test
    void unknownConversationIs404() throws Exception {
        mockMvc.perform(get("/api/conversations/{id}/messages", newId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));
        mockMvc.perform(delete("/api/conversations/{id}", newId())).andExpect(status().isNotFound());
    }

    /**
     * 越权：用户 10001 的会话，10002 和匿名访客拿着同一个 ID 都读不到、删不掉（和不存在一样 404，不暴露这个 ID 有没有人用）；
     * 10002 往这个 ID 里说话，写进的是他自己的会话。
     */
    @Test
    void otherUsersCannotReadOrDelete() throws Exception {
        String id = newId();
        model.willReply("好的。");
        mockMvc.perform(asUser(chat("我的收货地址是杭州", id), StubMallApi.TOKEN)).andExpect(status().isOk());

        mockMvc.perform(asUser(get("/api/conversations/{id}/messages", id), StubMallApi.NEW_USER_TOKEN))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/conversations/{id}/messages", id)).andExpect(status().isNotFound());
        mockMvc.perform(asUser(delete("/api/conversations/{id}", id), StubMallApi.NEW_USER_TOKEN))
                .andExpect(status().isNotFound());

        mockMvc.perform(asUser(chat("刚才说的地址是哪", id), StubMallApi.NEW_USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memory.historyTurns").value(0));
        assertThat(FakeChatModel.allText(model.answerPrompts().getLast())).doesNotContain("杭州");

        mockMvc.perform(asUser(get("/api/conversations/{id}/messages", id), StubMallApi.TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].text").value("我的收货地址是杭州"));
    }

    /** 令牌无效：查看 401；对话照常回答，只是降级成单轮（memory.active=false，带说明）。 */
    @Test
    void invalidTokenIs401ForQueriesAndDegradesChat() throws Exception {
        String id = newId();
        mockMvc.perform(asUser(get("/api/conversations/{id}/messages", id), "forged-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_EXPIRED"));

        mockMvc.perform(asUser(chat("你好", id), "forged-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memory.active").value(false))
                .andExpect(jsonPath("$.memory.warning").isString());
    }

    /** 清空：204，之后再查 404。 */
    @Test
    void deleteClearsConversation() throws Exception {
        String id = newId();
        mockMvc.perform(chat("你好", id)).andExpect(status().isOk());

        mockMvc.perform(delete("/api/conversations/{id}", id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/conversations/{id}/messages", id)).andExpect(status().isNotFound());
    }

    /** 流式：intent → memory → token × N → done；结束后记忆里有这一轮。 */
    @Test
    void streamSendsMemoryEventAndRemembers() throws Exception {
        String id = newId();
        model.willStream("您好，", "我是小示。");

        List<ChatStreamApiTest.Sse> events = stream("你好", id);

        assertThat(events)
                .extracting(ChatStreamApiTest.Sse::event)
                .containsExactly("intent", "memory", "token", "token", "done");
        assertThat(events.get(1).json().get("conversationId").asString()).isEqualTo(id);
        assertThat(events.get(1).json().get("historyTurns").asInt()).isZero();

        mockMvc.perform(get("/api/conversations/{id}/messages", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[1].text").value("您好，我是小示。"));
    }

    private List<ChatStreamApiTest.Sse> stream(String message, String conversationId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"" + message + "\",\"conversationId\":\"" + conversationId + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(5_000);
        return ChatStreamApiTest.parse(result);
    }
}
