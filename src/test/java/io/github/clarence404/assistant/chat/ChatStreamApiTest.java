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

/**
 * 第 1 章：流式接口 POST /api/chat/stream（SSE）。第 2 章起最前面多一个 intent 事件，这里只看它后面的事件序列。
 *
 * <p>验证：响应类型是 {@code text/event-stream}；模型的每个片段变成一个 token 事件，最后一个 done 事件；模型中途出错时，
 * 以一个 error 事件（带错误码）收尾、不再发 done。为什么错误要做成事件：流式响应一开始就已经回了 200 和响应头，之后无法再改成 503，
 * 只能在流里告诉前端「出错了」。
 *
 * <p>手法：MockMvc 处理返回 Flux 的接口时走 Servlet 异步请求，先断言 {@code asyncStarted()}，再用 {@code getAsyncResult} 等 Flux 结束，
 * 最后把整个响应体按 SSE 格式自己解析成事件列表来断言。解析工具 {@link #parse} / {@link #afterIntent} 也给其他测试类复用。
 */
class ChatStreamApiTest extends FakeModelWebTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void streamsTokenEventsThenDone() throws Exception {
        model.willStream("您好，", "订单付款后", "\n一般 48 小时内发货。");

        MvcResult result = stream("什么时候发货？");

        assertThat(result.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        List<Sse> events = afterIntent(parse(result));
        assertThat(events).hasSizeGreaterThanOrEqualTo(3);
        List<Sse> tokens = events.subList(0, events.size() - 1);
        assertThat(tokens).allSatisfy(e -> assertThat(e.event()).isEqualTo("token"));
        assertThat(tokens.stream().map(e -> e.json().get("text").asString()))
                .containsExactly("您好，", "订单付款后", "\n一般 48 小时内发货。");
        assertThat(events.getLast().event()).isEqualTo("done");
        assertThat(model.lastPrompt().getSystemMessage().getText()).contains("客服");
    }

    @Test
    void failureEndsStreamWithErrorEventAndNoDone() throws Exception {
        model.willStream("您好，");
        model.willFail(new ResourceAccessException("I/O error", new ConnectException("Connection refused")));

        MvcResult result = stream("在吗");

        List<Sse> events = parse(result);
        assertThat(events).extracting(Sse::event).containsExactly("intent", "token", "error");
        JsonNode error = events.getLast().json();
        assertThat(error.get("code").asString()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(error.get("message").asString()).isNotBlank();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("Exception")
                .doesNotContain("Connection refused");
    }

    /** 去掉最前面的 intent 事件（第 2 章新增）。 */
    static List<Sse> afterIntent(List<Sse> events) {
        assertThat(events).isNotEmpty();
        assertThat(events.getFirst().event()).isEqualTo("intent");
        return events.subList(1, events.size());
    }

    /** 发一个流式请求并等它结束，返回完整的响应。 */
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
    static List<Sse> parse(MvcResult result) throws Exception {
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
