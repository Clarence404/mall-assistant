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

/**
 * 第 1 章：把底层异常归成 MODEL_TIMEOUT / MODEL_UNAVAILABLE。
 *
 * <p>调模型可能在很多层失败：HTTP 客户端读超时、Reactor 的 {@code timeout} 操作符、JDK HttpClient 超时、连接被拒、厂商返回 4xx / 5xx
 * （Spring AI 包成 {@link NonTransientAiException} / {@link TransientAiException}，前者表示重试也没用，后者表示可以重试）。
 * 这里验证：异常链上任何一层是超时就归为 MODEL_TIMEOUT，其余都归为 MODEL_UNAVAILABLE；原始异常保留为 cause 供日志排查；
 * 已经归过类的异常原样返回，不重复包装。纯单元测试，直接构造异常对象。
 */
class ModelCallExceptionTest {

    /** 超时可能是最外层异常本身，也可能被包在 cause 里：都要认出来。 */
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
