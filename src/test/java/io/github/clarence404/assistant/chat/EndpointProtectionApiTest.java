package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.assistant.knowledge.KnowledgeController;
import io.github.clarence404.assistant.knowledge.KnowledgeSearchService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 调试 / 辅助接口的保护（审查修复）：这几个接口原来绕过了第 9 章的进门检查。
 *
 * <ul>
 *   <li>{@code POST /api/knowledge/ingest}：只有本机回环地址能调，别的来源 403——原来任何人都能 {@code ?rebuild=true} 把知识库删掉重建
 *   <li>{@code POST /api/intent}、{@code GET /api/knowledge/search}、{@code POST /api/mall/login}：和对话接口共用每分钟次数的限流
 * </ul>
 *
 * <p>这里把限流打开、每分钟 3 次，每个测试用自己的来源 IP，互不影响。其余接口测试（{@code FakeModelWebTest} 的其他子类）里限流是关着的，
 * 请求都来自 MockMvc 默认的 127.0.0.1，所以它们不用改。
 */
@TestPropertySource(properties = {"assistant.guard.enabled=true", "assistant.guard.requests-per-minute=3"})
class EndpointProtectionApiTest extends FakeModelWebTest {

    private static final AtomicInteger IPS = new AtomicInteger(1);

    @Autowired
    KnowledgeSearchService search;

    /** 从别的机器来的入库请求：403，带错误码；库里什么都没变（连「先清空」都没发生）。 */
    @Test
    void ingestFromAnotherMachineIsForbidden() throws Exception {
        mockMvc.perform(post("/api/knowledge/ingest")).andExpect(status().isOk());
        int chunks = search.stats().chunkCount();
        assertThat(chunks).isPositive();
        int embeddings = embeddingModel.calls().size();

        mockMvc.perform(post("/api/knowledge/ingest").param("rebuild", "true").with(from("10.8.0.7")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_INGEST_FORBIDDEN"));

        assertThat(search.stats().chunkCount()).isEqualTo(chunks);
        assertThat(embeddingModel.calls()).as("被挡下的请求没有去调向量模型").hasSize(embeddings);
    }

    /** 本机（IPv4、IPv6 的回环地址）照常能入库：页面上的「入库」按钮、讲义里的 curl、CI 都是这种情况。 */
    @Test
    void ingestFromLoopbackStillWorks() throws Exception {
        mockMvc.perform(post("/api/knowledge/ingest").with(from("127.0.0.1"))).andExpect(status().isOk());
        mockMvc.perform(post("/api/knowledge/ingest").with(from("0:0:0:0:0:0:0:1")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/knowledge/ingest").with(from("::1"))).andExpect(status().isOk());
    }

    @Test
    void loopbackDetection() {
        assertThat(KnowledgeController.isLoopback("127.0.0.1")).isTrue();
        assertThat(KnowledgeController.isLoopback("127.8.9.10")).isTrue();
        assertThat(KnowledgeController.isLoopback("::1")).isTrue();
        assertThat(KnowledgeController.isLoopback("192.168.1.20")).isFalse();
        assertThat(KnowledgeController.isLoopback("10.0.0.1")).isFalse();
        assertThat(KnowledgeController.isLoopback("")).isFalse();
        assertThat(KnowledgeController.isLoopback(null)).isFalse();
    }

    /** 检索调试：同一个来源一分钟内第 4 次被限流（每次检索都要调一次向量模型）。 */
    @Test
    void knowledgeSearchIsRateLimited() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/knowledge/search").param("q", "退货运费").with(from(ip)))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/knowledge/search").param("q", "退货运费").with(from(ip)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        // 别的来源不受影响
        mockMvc.perform(get("/api/knowledge/search").param("q", "退货运费").with(from(nextIp())))
                .andExpect(status().isOk());
    }

    /** 登录代理：第 4 次被限流，而且这一次根本没有转给商城（不能拿它无限次试密码）。 */
    @Test
    void mallLoginIsRateLimited() throws Exception {
        String ip = nextIp();
        String wrong = "{\"phone\":\"nobody\",\"password\":\"wrong\"}";
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(login(wrong, ip)).andExpect(status().isUnauthorized());
        }
        int forwarded = MALL.requests().size();

        mockMvc.perform(login(wrong, ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        assertThat(MALL.requests()).as("被限流的这一次没有打到商城").hasSize(forwarded);
    }

    /** 意图识别调试接口：和对话接口一样进门，第 4 次被限流，不调模型。 */
    @Test
    void intentEndpointIsRateLimited() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(intent("能退货吗", ip)).andExpect(status().isOk());
        }
        int calls = model.prompts().size();

        mockMvc.perform(intent("能退货吗", ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        assertThat(model.prompts()).as("被挡下的请求不调模型").hasSize(calls);
    }

    private static String nextIp() {
        return "10.7.0." + IPS.getAndIncrement();
    }

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(
            String body, String ip) {
        return post("/api/mall/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(from(ip));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder intent(
            String message, String ip) {
        return post("/api/intent")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"" + message + "\"}")
                .with(from(ip));
    }
}
