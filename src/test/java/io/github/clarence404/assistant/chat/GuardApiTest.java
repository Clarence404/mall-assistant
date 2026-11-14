package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.error.ModelFailure;
import io.github.clarence404.assistant.guard.ModelCircuitBreaker;
import io.github.clarence404.assistant.intent.IntentResult;
import io.github.clarence404.assistant.knowledge.KnowledgeIngestionService;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * 第 9 章：上线保护在接口上的样子——限流 429 与 Retry-After、每日预算、流式被挡下是一个 error 事件、熔断 503 MODEL_CIRCUIT_OPEN、
 * 规则类问题降级给原文、回答里带 Token 用量、响应头带 traceId、用量查询接口、Prometheus 指标与 {@code /actuator/tokens}。
 *
 * <p>手法同 {@link FakeModelWebTest}，另外用 {@code @TestPropertySource} 打开测试里默认关掉的限流与熔断（见 {@code src/test/resources/config/application.yml}），
 * 并把上限调小。每个测试用不同的来源 IP（匿名按 IP 限流、按 IP 记账），互不影响；熔断器在测试之间复位。
 */
@TestPropertySource(
        properties = {
            "assistant.guard.enabled=true",
            "assistant.guard.requests-per-minute=3",
            "assistant.guard.circuit.enabled=true",
            "assistant.guard.circuit.failure-threshold=2",
            "assistant.guard.degrade.enabled=true",
            "assistant.chat.stream-timeout=500ms",
            "assistant.usage.daily-token-budget=20000"
        })
class GuardApiTest extends FakeModelWebTest {

    private static final String POLICY = "{\"intent\":\"POLICY\",\"confidence\":0.9,\"orderNo\":null,\"skuId\":null}";
    private static final AtomicInteger IPS = new AtomicInteger(1);

    @Autowired
    ModelCircuitBreaker breaker;

    @Autowired
    ChatService chatService;

    @Autowired
    KnowledgeIngestionService ingestion;

    /** 降级要用到检索：先把 6 篇规则文档入库（增量，第二次起什么都不做）。 */
    @BeforeEach
    void ingest() {
        ingestion.ingest(false);
    }

    @AfterEach
    void closeCircuit() {
        breaker.onSuccess();
    }

    @Test
    void replyCarriesUsageAndTraceId() throws Exception {
        String ip = nextIp();

        mockMvc.perform(chat("你好", ip))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.usage.calls.length()").value(2))
                .andExpect(jsonPath("$.usage.calls[0].stage").value("intent"))
                .andExpect(jsonPath("$.usage.calls[1].stage").value("answer"))
                .andExpect(jsonPath("$.usage.estimated").value(true))
                .andExpect(jsonPath("$.usage.inputTokens").value(greaterThan(0)))
                .andExpect(jsonPath("$.usage.todayTokens").value(greaterThan(0)))
                .andExpect(jsonPath("$.usage.dailyBudget").value(20000));

        mockMvc.perform(get("/api/usage/me").with(from(ip)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caller").value("ip:" + ip))
                .andExpect(jsonPath("$.todayTokens").value(greaterThan(0)))
                .andExpect(jsonPath("$.dailyBudget").value(20000));
    }

    @Test
    void fourthRequestInAMinuteIsRateLimited() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(chat("你好", ip)).andExpect(status().isOk());
        }
        int calls = model.prompts().size();

        mockMvc.perform(chat("你好", ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.detail").value(startsWith("提问太频繁")));
        assertThat(model.prompts()).as("被挡下的请求不调模型").hasSize(calls);
        // 别的 IP 不受影响
        mockMvc.perform(chat("你好", nextIp())).andExpect(status().isOk());
    }

    @Test
    void streamRejectionIsASingleErrorEvent() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(chat("你好", ip)).andExpect(status().isOk());
        }

        List<ChatStreamApiTest.Sse> events = ChatStreamApiTest.parse(stream("你好", ip));

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.event()).isEqualTo("error");
            assertThat(e.json().get("code").asString()).isEqualTo("RATE_LIMITED");
        });
    }

    @Test
    void streamDoneEventCarriesUsage() throws Exception {
        List<ChatStreamApiTest.Sse> events = ChatStreamApiTest.parse(stream("你好", nextIp()));

        ChatStreamApiTest.Sse done = events.getLast();
        assertThat(done.event()).isEqualTo("done");
        assertThat(done.json().get("reason").asString()).isEqualTo("stop");
        assertThat(done.json().get("usage").get("calls").size()).isEqualTo(2);
        assertThat(done.json().get("usage").get("todayTokens").asLong()).isPositive();
    }

    @Test
    void dailyBudgetIsEnforced() throws Exception {
        String ip = nextIp();
        model.willReply("好的".repeat(15000)); // 一轮估算下来就超过 20000 Token 的预算

        mockMvc.perform(chat("你好", ip)).andExpect(status().isOk());

        mockMvc.perform(chat("你好", ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("TOKEN_BUDGET_EXCEEDED"));
    }

    @Test
    void circuitOpensAfterConsecutiveFailuresAndFailsFast() throws Exception {
        model.willFailClassification(new ResourceAccessException("I/O error", new ConnectException("refused")));
        // 用一句知识库里检索不到的话：检索得到的话会被降级成规则原文（见下一个测试），就不是 503 了
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(chat("zzz", nextIp()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("MODEL_UNAVAILABLE"));
        }
        int calls = model.prompts().size();

        mockMvc.perform(chat("zzz", nextIp()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_CIRCUIT_OPEN"));
        assertThat(model.prompts()).as("熔断后不再调模型").hasSize(calls);
        assertThat(breaker.state()).isEqualTo(ModelCircuitBreaker.State.OPEN);
    }

    /**
     * 审查修复：流式回答一直等不到下一段（模型挂起）——超时要计入熔断。修复前流式超时在熔断器那一层只是一次「取消」，和用户关页面分不出来，
     * 完全不计，模型挂起时每个用户都要各等满一个超时。这里连续两次超时（门槛是 2）之后熔断器打开，第三次直接 MODEL_CIRCUIT_OPEN。
     */
    @Test
    void streamIdleTimeoutsCountTowardsTheCircuit() {
        model.willStream(Flux.never());

        for (int i = 0; i < 2; i++) {
            StepVerifier.create(chatService.answerStream("zzz", IntentResult.unknown(), null))
                    .expectErrorSatisfies(e ->
                            assertThat(ModelCallException.from(e).failure()).isEqualTo(ModelFailure.MODEL_TIMEOUT))
                    .verify(Duration.ofSeconds(5));
        }

        assertThat(breaker.state()).isEqualTo(ModelCircuitBreaker.State.OPEN);
        StepVerifier.create(chatService.answerStream("zzz", IntentResult.unknown(), null))
                .expectErrorSatisfies(e ->
                        assertThat(ModelCallException.from(e).failure()).isEqualTo(ModelFailure.MODEL_CIRCUIT_OPEN))
                .verify(Duration.ofSeconds(5));
    }

    /** 用户主动取消（页面关了）不是模型的错：取消多少次熔断器都不动。 */
    @Test
    void cancelledStreamsDoNotCountTowardsTheCircuit() {
        model.willStream(Flux.never());

        for (int i = 0; i < 3; i++) {
            StepVerifier.create(chatService.answerStream("zzz", IntentResult.unknown(), null))
                    .thenAwait(Duration.ofMillis(50))
                    .thenCancel()
                    .verify(Duration.ofSeconds(5));
        }

        assertThat(breaker.state()).isEqualTo(ModelCircuitBreaker.State.CLOSED);
        // 再来一次真失败也只算第 1 次（门槛 2），说明前面的取消没有被悄悄记成失败
        breaker.onFailure();
        assertThat(breaker.state()).isEqualTo(ModelCircuitBreaker.State.CLOSED);
    }

    /** 规则类问题：回答模型挂了，检索到的原文照样给用户（200，不是 503）。 */
    @Test
    void ragQuestionDegradesToOriginalText() throws Exception {
        model.willClassify(POLICY);
        model.willFail(new ResourceAccessException("I/O error", new ConnectException("refused")));

        mockMvc.perform(chat("七天无理由退货要什么条件", nextIp()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(startsWith(ChatService.DEGRADED_HEAD)))
                .andExpect(jsonPath("$.rag.shortCircuit").value("MODEL_UNAVAILABLE"))
                .andExpect(jsonPath("$.sources.length()").value(greaterThan(0)));
    }

    /** 模型整个挂了（连意图识别都做不了）：拿原话去知识库检索，检索到了就当规则类问题，照样给原文。 */
    @Test
    void modelDownBeforeIntentStillDegradesRagQuestions() throws Exception {
        ResourceAccessException down = new ResourceAccessException("I/O error", new ConnectException("refused"));
        model.willFailClassification(down);
        model.willFail(down);

        mockMvc.perform(chat("七天无理由退货要什么条件", nextIp()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent.intent").value("POLICY"))
                .andExpect(jsonPath("$.intent.confidence").value(0.0))
                .andExpect(jsonPath("$.reply").value(startsWith(ChatService.DEGRADED_HEAD)))
                .andExpect(jsonPath("$.rag.shortCircuit").value("MODEL_UNAVAILABLE"));
    }

    @Test
    void prometheusAndTokensEndpointsExposeMetrics() throws Exception {
        mockMvc.perform(chat("你好", nextIp())).andExpect(status().isOk());

        String metrics = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(metrics)
                .contains("assistant_chat_requests_total{")
                .contains("assistant_chat_duration_seconds_bucket{")
                .contains("assistant_model_tokens_total{")
                .contains("assistant_model_circuit_state")
                .contains("assistant_chat_inflight");

        mockMvc.perform(get("/actuator/tokens"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyBudget").value(20000))
                .andExpect(jsonPath("$.total.turns").value(greaterThan(0)));
    }

    private static String nextIp() {
        return "10.9.0." + IPS.getAndIncrement();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static MockHttpServletRequestBuilder chat(String message, String ip) {
        return post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"" + message + "\"}")
                .with(from(ip));
    }

    private MvcResult stream(String message, String ip) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"" + message + "\"}")
                        .with(from(ip)))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(5_000); // 等 Flux 结束
        return result;
    }
}
