package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;

/** 第 1 章：阻塞接口 POST /api/chat 与错误映射（ProblemDetail）。 */
class ChatApiTest extends FakeModelWebTest {

    @Test
    void returnsModelReplyAsJson() throws Exception {
        model.willReply("您好，订单付款后一般 48 小时内发货。");

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"什么时候发货？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("您好，订单付款后一般 48 小时内发货。"));

        assertThat(model.lastPrompt().getSystemMessage().getText()).contains("客服");
        assertThat(model.lastPrompt().getUserMessage().getText()).isEqualTo("什么时候发货？");
    }

    @Test
    void blankMessageIsRejectedWithoutCallingModel() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(model.prompts()).isEmpty();
    }

    @Test
    void modelUnavailableBecomes503ProblemDetail() throws Exception {
        model.willFail(new ResourceAccessException(
                "I/O error on POST request for \"http://localhost:11434/api/chat\"",
                new ConnectException("Connection refused")));

        String body = mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"在吗\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("MODEL_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertNoInternals(body);
    }

    @Test
    void modelTimeoutBecomes503WithTimeoutCode() throws Exception {
        model.willFail(new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")));

        String body = mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"在吗\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_TIMEOUT"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertNoInternals(body);
    }

    /** 不把异常类名、堆栈、内部地址暴露给前端。 */
    private static void assertNoInternals(String body) {
        assertThat(body)
                .doesNotContain("Exception")
                .doesNotContain("\tat ")
                .doesNotContain("localhost:11434")
                .doesNotContain("Connection refused")
                .doesNotContain("trace");
    }
}
