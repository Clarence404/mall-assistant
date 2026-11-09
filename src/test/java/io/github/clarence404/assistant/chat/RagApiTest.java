package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 第 5 章：对话接口里的 RAG。阻塞接口多了 {@code sources}（引用来源）和 {@code rag}（调试信息）两个字段；流式接口在文字之前推
 * {@code source} 事件；检索不到时不调回答模型、直接回固定话术；向量模型挂了也照常返回 200。
 *
 * <p>手法：同 {@link FakeModelWebTest}，整个应用跑起来，对话模型和向量模型都是假的；每个测试前先入库（没变的文档会跳过）。
 */
class RagApiTest extends FakeModelWebTest {

    private static final String POLICY = "{\"intent\":\"POLICY\",\"confidence\":0.9,\"orderNo\":null,\"skuId\":null}";

    @BeforeEach
    void ingest() throws Exception {
        mockMvc.perform(post("/api/knowledge/ingest")).andExpect(status().isOk());
        embeddingModel.reset();
    }

    @Test
    void chatReturnsSourcesAndRagTrace() throws Exception {
        model.willClassify(POLICY);
        model.willReply("签收后 7 天内可以申请无理由退货[1]，详见规则[7]。");

        mockMvc.perform(chat("七天无理由退货要什么条件"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("签收后 7 天内可以申请无理由退货[1]，详见规则。"))
                .andExpect(jsonPath("$.intent.intent").value("POLICY"))
                .andExpect(jsonPath("$.tools.length()").value(0))
                .andExpect(jsonPath("$.sources.length()").value(greaterThan(0)))
                .andExpect(jsonPath("$.sources[0].index").value(1))
                .andExpect(jsonPath("$.sources[0].source").value("returns.md"))
                .andExpect(jsonPath("$.sources[0].headingPath").value(containsString("七天无理由退货")))
                .andExpect(jsonPath("$.sources[0].fusedScore").isNumber())
                .andExpect(jsonPath("$.sources[0].snippet").isString())
                .andExpect(jsonPath("$.sources[0].content").value(containsString("七天无理由退货")))
                .andExpect(jsonPath("$.rag.mode").value("hybrid"))
                .andExpect(jsonPath("$.rag.cited[0]").value(1))
                .andExpect(jsonPath("$.rag.invalidCitations[0]").value(7))
                .andExpect(jsonPath("$.rag.uncited").value(false))
                .andExpect(jsonPath("$.rag.shortCircuit").doesNotExist());
    }

    /** 检索不到：固定话术，回答模型一次都没调（只调了意图识别）。 */
    @Test
    void noMatchShortCircuitsWithoutAnswerModel() throws Exception {
        model.willClassify(POLICY);

        mockMvc.perform(chat("火星探测器"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(containsString("没有找到")))
                .andExpect(jsonPath("$.sources.length()").value(0))
                .andExpect(jsonPath("$.rag.shortCircuit").value("NO_MATCH"));

        assertThat(model.answerPrompts()).isEmpty();
    }

    /** 向量模型挂了：规则类问题回「规则库暂时查不到」，还是 200，不是 503；对话模型没被调用。 */
    @Test
    void knowledgeDownStillAnswers200() throws Exception {
        model.willClassify(POLICY);
        embeddingModel.failWith(new IllegalStateException("Connection refused"));

        mockMvc.perform(chat("七天无理由退货要什么条件"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(containsString("暂时查不到")))
                .andExpect(jsonPath("$.rag.shortCircuit").value("KNOWLEDGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.rag.warning").value(not(containsString("Connection refused"))));

        assertThat(model.answerPrompts()).isEmpty();
    }

    /** 不走 RAG 的回答：sources 是空数组，rag 为 null，和第 3 章的响应兼容。 */
    @Test
    void nonRagReplyHasEmptySources() throws Exception {
        mockMvc.perform(chat("你好"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources.length()").value(0))
                .andExpect(jsonPath("$.rag").doesNotExist());
    }

    /** 流式：intent → source × N（编号从 1 开始）→ token × M → done。 */
    @Test
    void streamSendsSourceEventsBeforeTokens() throws Exception {
        model.willClassify(POLICY);
        model.willStream("签收后 7 天内", "可以退[1]。");

        List<ChatStreamApiTest.Sse> events = stream("七天无理由退货要什么条件");

        List<String> names = events.stream().map(ChatStreamApiTest.Sse::event).toList();
        int sources = (int) names.stream().filter("source"::equals).count();
        assertThat(sources).isPositive();
        assertThat(names.getFirst()).isEqualTo("intent");
        assertThat(names.subList(1, 1 + sources)).containsOnly("source");
        assertThat(names.subList(1 + sources, names.size())).containsExactly("token", "token", "done");
        assertThat(events.get(1).json().get("index").asInt()).isEqualTo(1);
        assertThat(events.get(1).json().get("headingPath").asString()).contains("七天无理由退货");
    }

    @Test
    void streamShortCircuitSendsOnlyFallback() throws Exception {
        model.willClassify(POLICY);

        List<ChatStreamApiTest.Sse> events = stream("火星探测器");

        assertThat(events).extracting(ChatStreamApiTest.Sse::event).containsExactly("intent", "token", "done");
        assertThat(events.get(1).json().get("text").asString()).contains("没有找到");
        assertThat(model.answerPrompts()).isEmpty();
    }

    private List<ChatStreamApiTest.Sse> stream(String message) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"" + message + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(5_000);
        return ChatStreamApiTest.parse(result);
    }

    private static MockHttpServletRequestBuilder chat(String message) {
        return post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"" + message + "\"}");
    }
}
