package io.github.clarence404.assistant.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BackoffTest {

    @Test
    void doublesWithJitterUpToMaxAndResetsOnSuccess() {
        Backoff backoff = new Backoff(Duration.ofSeconds(5), Duration.ofMinutes(1));
        Random random = new Random(7);
        assertThat(backoff.onFailure(random)).isBetween(Duration.ofMillis(2500), Duration.ofSeconds(5));
        assertThat(backoff.onFailure(random)).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(10));
        assertThat(backoff.onFailure(random)).isBetween(Duration.ofSeconds(10), Duration.ofSeconds(20));
        for (int i = 0; i < 50; i++) { // 次数再多也不超过 max，也不会溢出
            assertThat(backoff.onFailure(random)).isBetween(Duration.ofSeconds(30), Duration.ofMinutes(1));
        }
        assertThat(backoff.failures()).isEqualTo(53);

        backoff.onSuccess();
        assertThat(backoff.failures()).isZero();
        assertThat(backoff.onFailure(random)).isLessThanOrEqualTo(Duration.ofSeconds(5));
    }
}
