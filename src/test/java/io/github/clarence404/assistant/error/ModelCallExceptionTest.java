package io.github.clarence404.assistant.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

/** 第 1 章：把底层异常归成 MODEL_TIMEOUT / MODEL_UNAVAILABLE。 */
class ModelCallExceptionTest {

    @Test
    void timeoutsAnywhereInCauseChainAreModelTimeout() {
        assertThat(failureOf(new TimeoutException("Did not observe any item within 30000ms")))
                .isEqualTo(ModelFailure.MODEL_TIMEOUT);
        assertThat(failureOf(new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out"))))
                .isEqualTo(ModelFailure.MODEL_TIMEOUT);
        assertThat(failureOf(new RuntimeException("wrapped", new HttpTimeoutException("request timed out"))))
                .isEqualTo(ModelFailure.MODEL_TIMEOUT);
    }

    @Test
    void otherFailuresAreModelUnavailable() {
        assertThat(failureOf(new ResourceAccessException("I/O error", new ConnectException("Connection refused"))))
                .isEqualTo(ModelFailure.MODEL_UNAVAILABLE);
        assertThat(failureOf(new NonTransientAiException("HTTP 404 - model \"qwen2.5:0.5b\" not found")))
                .isEqualTo(ModelFailure.MODEL_UNAVAILABLE);
        assertThat(failureOf(new TransientAiException("HTTP 503 - overloaded")))
                .isEqualTo(ModelFailure.MODEL_UNAVAILABLE);
    }

    @Test
    void keepsOriginalCauseForLogging() {
        ConnectException root = new ConnectException("Connection refused");

        ModelCallException e = ModelCallException.from(root);

        assertThat(e.getCause()).isSameAs(root);
    }

    @Test
    void alreadyClassifiedExceptionIsReturnedAsIs() {
        ModelCallException classified = new ModelCallException(ModelFailure.MODEL_TIMEOUT, new TimeoutException());

        assertThat(ModelCallException.from(classified)).isSameAs(classified);
    }

    private static ModelFailure failureOf(Throwable error) {
        return ModelCallException.from(error).failure();
    }
}
