package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 第 1 章：流式接口 POST /api/chat/stream（SSE）。 */
class ChatStreamApiTest extends FakeModelWebTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void streamsTokenEventsThenDone() throws Exception {
        model.willStream("您好，", "订单付款后", "\n一般 48 小时内发货。");

        MvcResult result = stream("什么时候发货？");

        assertThat(result.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        List<Sse> events = parse(result);
        assertThat(events).hasSizeGreaterThanOrEqualTo(3);
        List<Sse> tokens = events.subList(0, events.size() - 1);
        assertThat(tokens).allSatisfy(e -> assertThat(e.event()).isEqualTo("token"));
        assertThat(tokens.stream().map(e -> e.json().get("text").asString()))
                .containsExactly("您好，", "订单付款后", "\n一般 48 小时内发货。");
        assertThat(events.getLast().event()).isEqualTo("done");
        assertThat(model.lastPrompt().getSystemMessage().getText()).contains("示例商城");
    }

    @Test
    void failureEndsStreamWithErrorEventAndNoDone() throws Exception {
        model.willStream("您好，");
        model.willFail(new ResourceAccessException("I/O error", new ConnectException("Connection refused")));

        MvcResult result = stream("在吗");

        List<Sse> events = parse(result);
        assertThat(events).extracting(Sse::event).containsExactly("token", "error");
        JsonNode error = events.getLast().json();
        assertThat(error.get("code").asString()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(error.get("message").asString()).isNotBlank();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("Exception")
                .doesNotContain("Connection refused");
    }

    private MvcResult stream(String message) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(JSON.writeValueAsString(new ChatRequest(message))))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(5_000); // 等 Flux 结束
        return result;
    }

    /** 一条 SSE 事件：event 名 + data（JSON）。 */
    record Sse(String event, JsonNode json) {}

    /** 按 SSE 规范解析：事件之间空行分隔，多行 data 用换行拼接。 */
    private static List<Sse> parse(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Sse> events = new ArrayList<>();
        for (String block : body.replace("\r\n", "\n").split("\n\n")) {
            if (block.isBlank()) {
                continue;
            }
            String event = "message";
            List<String> data = new ArrayList<>();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.add(line.substring(5).replaceFirst("^ ", ""));
                }
            }
            events.add(new Sse(event, JSON.readTree(String.join("\n", data))));
        }
        return events;
    }
}
