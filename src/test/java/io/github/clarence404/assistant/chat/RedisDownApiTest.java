package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * 第 10 章：{@code assistant.state.store=redis}，而 Redis 根本连不上（指向一个没人监听的端口）、策略是拒绝时，整个应用的样子。
 *
 * <ul>
 *   <li>应用照样启动，{@code /actuator/health} 是 UP（没有用 Spring Boot 的 Redis Starter，不会因为 Redis 挂了把整个实例标成不健康、被摘掉流量）
 *   <li>对话接口 503 {@code STATE_UNAVAILABLE}，带 {@code Retry-After}，模型一次都没调
 *   <li>确认接口 503 {@code CONFIRMATION_UNAVAILABLE}：写操作不「出错就放行」
 *   <li>指标里有 {@code assistant_state_errors_total} 和 {@code assistant_state_store_info{store="redis"}}，告警和面板靠它们
 * </ul>
 */
@TestPropertySource(
        properties = {
            "assistant.guard.enabled=true",
            "assistant.usage.daily-token-budget=20000",
            "assistant.state.store=redis",
            "assistant.state.on-redis-error=reject",
            "assistant.state.redis.url=redis://127.0.0.1:1/0",
            "assistant.state.redis.timeout=300ms",
            "assistant.state.redis.cooldown=0s"
        })
class RedisDownApiTest extends FakeModelWebTest {

    @Test
    void applicationStartsAndReportsHealthy() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void chatIsRefusedWithStateUnavailable() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"七天无理由退货要什么条件\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("STATE_UNAVAILABLE"));

        assertThat(model.prompts()).as("被挡下的请求不调模型").isEmpty();
    }

    @Test
    void confirmationsFailClosed() throws Exception {
        mockMvc.perform(post("/api/agent/confirmations/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CONFIRMATION_UNAVAILABLE"));
    }

    @Test
    void metricsShowTheStoreAndTheErrors() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isServiceUnavailable());

        String metrics = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(metrics)
                .contains("assistant_state_store_info{application=\"mall-assistant\",store=\"redis\"} 1")
                .contains("assistant_state_errors_total{application=\"mall-assistant\",component=\"usage\"}")
                .contains(
                        "assistant_guard_rejections_total{application=\"mall-assistant\",reason=\"STATE_UNAVAILABLE\"}");
    }
}
