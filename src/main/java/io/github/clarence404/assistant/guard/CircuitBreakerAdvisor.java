package io.github.clarence404.assistant.guard;

import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.error.ModelFailure;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 把 {@link ModelCircuitBreaker} 挂到每一次模型调用上（第 9 章引入）。
 *
 * <p>所在层：模型接入。{@code ops.OpsConfig} 用 {@code ChatClientCustomizer} 把它加进共享 ChatClient 的默认 advisor，所以意图识别、追问改写、回答、
 * RAG、Agent 的每一步都经过它，业务代码一行不用改。
 *
 * <p><b>Advisor 是什么</b>：Spring AI 的 ChatClient 把一次调用做成一条「链」，请求从外往里经过每个 advisor，最里面是真正调模型的
 * {@code ChatModelCallAdvisor}，响应再从里往外回来——和 Servlet 的 Filter、Spring 的 AOP 是一个思路。记忆、工具调用循环（2.0 起是
 * {@code ToolCallingAdvisor}）、RAG 的现成组件都是 advisor。{@link #getOrder()} 越小越靠外。
 *
 * <p>它排在最外层（{@link #ORDER}）：熔断时连 Token 记账（{@code ops.UsageAdvisor}）都不经过，也不会进到工具调用循环里。一次 ChatClient 调用算一次，
 * 原生工具调用的几轮模型请求合起来算一次成功或失败。
 *
 * <p>流式调用这里只挂了「正常结束 → 成功」「出错 → 失败」。还有第三种结束方式——下游取消：用户关了页面，或者 {@code ChatService} 的空闲超时
 * 到点。在这一层两者长得一样，分不出来，所以取消在这里不计；空闲超时由发起取消的 {@code ChatService#idleTimeout} 自己报给熔断器（审查修复，
 * 之前流式超时完全不计入熔断）。
 */
@Component
public class CircuitBreakerAdvisor implements CallAdvisor, StreamAdvisor {

    /** 顺序：比 Token 记账（+200）和工具调用循环（{@code ToolCallingAdvisor} 默认 +300）都靠外。 */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    private final ModelCircuitBreaker breaker;

    /**
     * 创建。
     *
     * @param breaker 熔断器
     */
    public CircuitBreakerAdvisor(ModelCircuitBreaker breaker) {
        this.breaker = breaker;
    }

    /** 阻塞调用：先问能不能调，不能就直接抛 MODEL_CIRCUIT_OPEN；调了按结果报告成功或失败。 */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (!breaker.tryAcquire()) {
            throw new ModelCallException(ModelFailure.MODEL_CIRCUIT_OPEN, null);
        }
        try {
            ChatClientResponse response = chain.nextCall(request);
            breaker.onSuccess();
            return response;
        } catch (RuntimeException e) {
            breaker.onFailure();
            throw e;
        }
    }

    /**
     * 流式调用：订阅时才问（{@link Flux#defer}），流正常结束算成功、出错算失败；页面中途关掉（取消）两样都不算，半开时由熔断器自己的超时兜底。
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            if (!breaker.tryAcquire()) {
                return Flux.error(new ModelCallException(ModelFailure.MODEL_CIRCUIT_OPEN, null));
            }
            return chain.nextStream(request).doOnComplete(breaker::onSuccess).doOnError(e -> breaker.onFailure());
        });
    }

    @Override
    public String getName() {
        return "circuitBreaker";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
