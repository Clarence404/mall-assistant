package io.github.clarence404.assistant.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 只测把 SSE 事件折成结果这一段（发请求的部分在实测里验证）。 */
class AssistantClientTest {

    private final AssistantClient client = new AssistantClient("http://localhost:8088", "http://localhost:9000");

    @Test
    void doneEventCarriesUsage() {
        AssistantClient.Answer a = client.fromEvents(List.of(
                new String[] {"intent", "{\"intent\":\"POLICY\",\"confidence\":0.9}"},
                new String[] {"source", "{\"index\":1}"},
                new String[] {"token", "{\"text\":\"七天\"}"},
                new String[] {
                    "done",
                    "{\"reason\":\"stop\",\"usage\":{\"inputTokens\":1200,\"outputTokens\":80,\"todayTokens\":5300,\"dailyBudget\":100000}}"
                }));
        assertThat(a.ok()).isTrue();
        assertThat(a.intent()).isEqualTo("POLICY");
        assertThat(a.turnTokens()).isEqualTo(1280);
        assertThat(a.todayTokens()).isEqualTo(5300);
        assertThat(a.dailyBudget()).isEqualTo(100_000);
    }

    @Test
    void errorEventCarriesCode() {
        AssistantClient.Answer a = client.fromEvents(List.<String[]>of(
                new String[] {"error", "{\"code\":\"TOKEN_BUDGET_EXCEEDED\",\"message\":\"今天的智能客服额度已经用完了\"}"}));
        assertThat(a.ok()).isFalse();
        assertThat(a.errorCode()).isEqualTo("TOKEN_BUDGET_EXCEEDED");
    }

    @Test
    void confirmEventIsKept() {
        AssistantClient.Answer a = client.fromEvents(List.of(
                new String[] {"intent", "{\"intent\":\"AFTER_SALES\"}"},
                new String[] {"confirm", "{\"id\":\"c1\",\"action\":\"cancelOrder\",\"orderNo\":\"1001\"}"},
                new String[] {"done", "{\"reason\":\"stop\"}"}));
        assertThat(a.ok()).isTrue();
        assertThat(a.confirmation().path("action").asString()).isEqualTo("cancelOrder");
        assertThat(a.todayTokens()).isEqualTo(-1); // 没有 usage
    }

    @Test
    void streamCutInTheMiddleIsIncomplete() {
        AssistantClient.Answer a = client.fromEvents(
                List.of(new String[] {"intent", "{\"intent\":\"CHITCHAT\"}"}, new String[] {"token", "{}"}));
        assertThat(a.errorCode()).isEqualTo("STREAM_INCOMPLETE");
    }
}
