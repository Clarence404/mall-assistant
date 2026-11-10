package io.github.clarence404.assistant.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.chat.FakeChatModel;
import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.error.ModelFailure;
import io.github.clarence404.assistant.ops.ManualClock;
import java.net.ConnectException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.client.ResourceAccessException;
import reactor.test.StepVerifier;

/**
 * 第 9 章 TODO ch09-7：模型熔断——连续失败 N 次熔断、过一段时间放一个去试、试成了恢复、试失败了再熔断。
 */
class ModelCircuitBreakerTest {

    private final ManualClock clock = ManualClock.standard();
    private final ModelCircuitBreaker breaker =
            new ModelCircuitBreaker(new GuardProperties.Circuit(true, 3, Duration.ofSeconds(30)), clock);

    @Test
    void opensAfterConsecutiveFailures() {
        for (int i = 0; i < 2; i++) {
            assertThat(breaker.tryAcquire()).isTrue();
            breaker.onFailure();
        }
        assertThat(breaker.state()).isEqualTo(ModelCircuitBreaker.State.CLOSED);

        assertThat(breaker.tryAcquire()).isTrue();
        breaker.onFailure();

        assertThat(breaker.state()).isEqualTo(ModelCircuitBreaker.State.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
        assertThat(breaker.remaining()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void successResetsTheCount() {
        breaker.onFailure();
        breaker.onFailure();
        breaker.onSuccess();
        breaker.onFailure();
        breaker.onFailure();

        assertThat(breaker.state()).isEqualTo(ModelCircuitBreaker.State.CLOSED);
    }

    @Test
    void halfOpenLetsOneProbeThroughAndRecoversOnSuccess() {
        open();
        clock.advance(Duration.ofSeconds(29));
        assertThat(breaker.tryAcquire()).isFalse();

        clock.advance(Duration.ofSeconds(1));
        assertThat(breaker.tryAcquire()).isTrue();
        assertThat(breaker.state()).isEqualTo(ModelCircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.tryAcquire()).as("试探期间其他请求照样拒绝").isFalse();

        breaker.onSuccess();
        assertThat(breaker.state()).isEqualTo(ModelCircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void failedProbeOpensAgainWithANewTimer() {
        open();
        clock.advance(Duration.ofSeconds(30));
        assertThat(breaker.tryAcquire()).isTrue();

        breaker.onFailure();

        assertThat(breaker.state()).isEqualTo(ModelCircuitBreaker.State.OPEN);
        assertThat(breaker.remaining()).isEqualTo(Duration.ofSeconds(30));
        assertThat(breaker.tryAcquire()).isFalse();
    }

    /** 试探的请求没有结果（页面关了、卡住了）：过了熔断时长再放一个去试，不会永远卡在半开。 */
    @Test
    void stuckProbeIsReplacedAfterOpenDuration() {
        open();
        clock.advance(Duration.ofSeconds(30));
        assertThat(breaker.tryAcquire()).isTrue();

        clock.advance(Duration.ofSeconds(29));
        assertThat(breaker.tryAcquire()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void failuresWhileOpenDoNotRestartTheTimer() {
        open();
        clock.advance(Duration.ofSeconds(20));
        breaker.onFailure(); // 熔断前就发出去的请求，这时才失败

        assertThat(breaker.remaining()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void disabledBreakerAlwaysAllows() {
        ModelCircuitBreaker off =
                new ModelCircuitBreaker(new GuardProperties.Circuit(false, 1, Duration.ofSeconds(30)), clock);
        off.onFailure();
        off.onFailure();

        assertThat(off.tryAcquire()).isTrue();
    }

    /** 挂到 ChatClient 上：熔断之后不再调模型，直接 MODEL_CIRCUIT_OPEN；阻塞、流式都一样。 */
    @Test
    void advisorFailsFastWithoutCallingTheModel() {
        FakeChatModel model = new FakeChatModel();
        model.willFail(new ResourceAccessException("连不上", new ConnectException("refused")));
        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(new CircuitBreakerAdvisor(breaker))
                .build();

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.prompt().user("你好").call().content())
                    .isInstanceOf(ResourceAccessException.class);
        }
        int calls = model.prompts().size();

        assertThatThrownBy(() -> client.prompt().user("你好").call().content())
                .isInstanceOfSatisfying(
                        ModelCallException.class,
                        e -> assertThat(e.failure()).isEqualTo(ModelFailure.MODEL_CIRCUIT_OPEN));
        StepVerifier.create(client.prompt().user("你好").stream().content())
                .expectErrorSatisfies(
                        e -> assertThat(((ModelCallException) e).failure()).isEqualTo(ModelFailure.MODEL_CIRCUIT_OPEN))
                .verify();
        assertThat(model.prompts()).hasSize(calls);

        // 半开：放一个流式请求去试，成功就恢复
        model.reset();
        clock.advance(Duration.ofSeconds(30));
        StepVerifier.create(client.prompt().user("你好").stream().content())
                .expectNextCount(3)
                .verifyComplete();
        assertThat(breaker.state()).isEqualTo(ModelCircuitBreaker.State.CLOSED);
    }

    private void open() {
        for (int i = 0; i < 3; i++) {
            breaker.onFailure();
        }
        assertThat(breaker.state()).isEqualTo(ModelCircuitBreaker.State.OPEN);
    }
}
