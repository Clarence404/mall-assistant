package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 第 4 章：知识库的三个调试接口（{@code KnowledgeController}）。
 *
 * <p>验证：{@code POST /api/knowledge/ingest} 返回入库报告、第二次全部跳过、{@code rebuild=true} 全部重来；
 * {@code GET /api/knowledge/search} 返回块内容、来源、标题路径和分数，topK 收紧到上限，q 为空 400；{@code GET /api/knowledge/stats}
 * 返回块数、文档数、模型、维度、库类型；向量模型挂了返回 503 + {@code EMBEDDING_UNAVAILABLE}（和第 1 章的 ProblemDetail 同一格式）。
 *
 * <p>手法：同 {@link FakeModelWebTest}，整个应用跑起来，向量模型换成假的 {@code FakeEmbeddingModel}，向量库是默认的内存库。
 * 上下文在测试之间共用，每个测试先 {@code rebuild=true} 清空重建，保证从同一个状态开始。
 */
class KnowledgeApiTest extends FakeModelWebTest {

    @BeforeEach
    void rebuild() throws Exception {
        mockMvc.perform(post("/api/knowledge/ingest").param("rebuild", "true")).andExpect(status().isOk());
        embeddingModel.reset();
    }

    @Test
    void ingestReturnsReportAndSecondRunSkipsEverything() throws Exception {
        mockMvc.perform(post("/api/knowledge/ingest").param("rebuild", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rebuild").value(true))
                .andExpect(jsonPath("$.documentsAdded").value(6))
                .andExpect(jsonPath("$.chunksAdded").value(greaterThan(20)))
                .andExpect(jsonPath("$.embeddingModel").value("fake-ngram"))
                .andExpect(jsonPath("$.store").value("memory"))
                .andExpect(jsonPath("$.documents.length()").value(6))
                .andExpect(jsonPath("$.durationMs").isNumber());
        embeddingModel.reset();

        mockMvc.perform(post("/api/knowledge/ingest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentsSkipped").value(6))
                .andExpect(jsonPath("$.chunksAdded").value(0));
        assertThat(embeddingModel.calls()).isEmpty();
    }

    @Test
    void searchReturnsChunksWithSourceHeadingAndScore() throws Exception {
        mockMvc.perform(get("/api/knowledge/search").param("q", "七天无理由退货要什么条件").param("topK", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("七天无理由退货要什么条件"))
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.hits[0].source").value("returns.md"))
                .andExpect(jsonPath("$.hits[0].title").value("退换货规则"))
                .andExpect(jsonPath("$.hits[0].headingPath").value(startsWith("退换货规则 > 七天无理由退货")))
                .andExpect(jsonPath("$.hits[0].content").isString())
                .andExpect(jsonPath("$.hits[0].score").isNumber())
                .andExpect(jsonPath("$.hits[0].distance").isNumber());
        assertThat(embeddingModel.calls()).containsExactly("七天无理由退货要什么条件");
    }

    @Test
    void topKIsClampedToMax() throws Exception {
        mockMvc.perform(get("/api/knowledge/search").param("q", "退货").param("topK", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(10));
    }

    @Test
    void blankQueryIs400() throws Exception {
        mockMvc.perform(get("/api/knowledge/search").param("q", "  ")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/knowledge/search")).andExpect(status().isBadRequest());
        assertThat(embeddingModel.calls()).isEmpty();
    }

    @Test
    void statsShowCountsModelDimensionsAndStore() throws Exception {
        mockMvc.perform(get("/api/knowledge/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.store").value("memory"))
                .andExpect(jsonPath("$.embeddingModel").value("fake-ngram"))
                .andExpect(jsonPath("$.dimensions").value(256))
                .andExpect(jsonPath("$.documentCount").value(6))
                .andExpect(jsonPath("$.chunkCount").value(greaterThan(20)))
                .andExpect(jsonPath("$.documents[0].source").value("account-security.md"))
                .andExpect(jsonPath("$.queryPrefix").value(""));
    }

    @Test
    void embeddingFailureIs503ProblemDetail() throws Exception {
        embeddingModel.failWith(new IllegalStateException("Connection refused"));

        mockMvc.perform(get("/api/knowledge/search").param("q", "退货"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("EMBEDDING_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value(startsWith("向量模型 fake-ngram")))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    /** 知识库出问题不影响对话：向量模型挂了，聊天接口照常回答（本章还没把知识库接进对话）。 */
    @Test
    void chatStillWorksWhenEmbeddingIsDown() throws Exception {
        embeddingModel.failWith(new IllegalStateException("Connection refused"));
        model.willReply("您好，请问有什么可以帮您？");

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("您好，请问有什么可以帮您？"));
    }
}
