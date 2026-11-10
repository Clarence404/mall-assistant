package io.github.clarence404.assistant.ops;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Token 记账的 advisor：每次模型调用结束后取出用了多少 Token（取不到就估算），记进这一轮的 {@link TurnRecord} 和指标（第 9 章引入）。
 *
 * <p>所在层：模型接入。和 {@code guard.CircuitBreakerAdvisor} 一起由 {@link OpsConfig} 加进共享 ChatClient 的默认 advisor；顺序在熔断之内、
 * 工具调用循环（{@code ToolCallingAdvisor}）之外，所以原生工具调用的几轮模型请求合起来记一次（Spring AI 2.0 会把几轮的用量累加好再交出来）。
 *
 * <p><b>用量从哪来</b>：每家模型在响应里都会报告这一次用了多少 Token——Ollama 是 {@code prompt_eval_count} / {@code eval_count}，OpenAI 兼容接口是
 * {@code usage.prompt_tokens} / {@code completion_tokens}，Claude CLI 的 JSON 里有 {@code usage}。Spring AI 把它们统一成
 * {@code ChatResponse.getMetadata().getUsage()}。流式调用时用量只在最后一段里（Ollama 的 {@code done: true} 那一段）。
 *
 * <p><b>Spring AI 自己也记</b>：有 Actuator 时，Spring AI 的模型调用自带观测，Prometheus 里能看到 {@code gen_ai_client_token_usage_total}（按模型、输入 / 输出分）
 * 和 {@code gen_ai_client_operation_seconds}。那是「模型」视角；这里要的是「业务」视角——这一轮是谁问的、哪个意图、哪一步花的，所以还得自己记一份。
 * 另外 {@code CliChatModel} 是本项目自己写的，没有接 Spring AI 的观测，它的用量只有这里记得到。
 */
@Component
public class UsageAdvisor implements CallAdvisor, StreamAdvisor {

    /** 顺序：熔断（+100）之内，工具调用循环（+300）之外。 */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 200;

    /** 请求上下文里「这是哪一步」的键。 */
    public static final String STAGE_KEY = "assistant.stage";

    /** 没标步骤的调用。 */
    public static final String OTHER = "other";

    private final ChatMetrics metrics;

    /**
     * 创建。
     *
     * @param metrics 指标
     */
    public UsageAdvisor(ChatMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * 给一次 ChatClient 调用标上步骤：{@code chatClient.prompt()...advisors(UsageAdvisor.stage("intent")).call()}。
     *
     * @param stage 步骤名，见 {@link ModelCall#stage()}
     * @return advisor 参数
     */
    public static Consumer<ChatClient.AdvisorSpec> stage(String stage) {
        return a -> a.param(STAGE_KEY, stage);
    }

    /**
     * 给一次 ChatClient 调用标上步骤并带上这一轮（流式调用用：流在别的线程上跑，ThreadLocal 跟不过去）。
     *
     * @param stage 步骤名
     * @param record 这一轮
     * @return advisor 参数
     */
    public static Consumer<ChatClient.AdvisorSpec> stage(String stage, TurnRecord record) {
        return a -> a.params(Map.of(STAGE_KEY, stage, TurnRecord.CONTEXT_KEY, record));
    }

    /** 阻塞调用：调完取用量、记账；失败只记一次失败的调用指标。 */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        TurnRecord record = recordOf(request);
        String stage = stageOf(request);
        long start = System.nanoTime();
        ChatClientResponse response;
        try {
            response = chain.nextCall(request);
        } catch (RuntimeException e) {
            metrics.modelCallFailed(stage);
            throw e;
        }
        ChatResponse chat = response.chatResponse();
        record(record, stage, modelOf(chat), usageOf(chat, request.prompt()), start);
        return response;
    }

    /**
     * 流式调用：边推边把文字拼起来、记下带用量的那一段，流正常结束时记账（中途出错、取消的不记 Token，只记失败指标）。
     * 这一轮在订阅时确定：请求上下文里有就用它，没有就看订阅时的线程上有没有绑定。
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            TurnRecord record = recordOf(request);
            String stage = stageOf(request);
            long start = System.nanoTime();
            StringBuilder text = new StringBuilder();
            AtomicReference<ChatResponse> withUsage = new AtomicReference<>();
            return chain.nextStream(request)
                    .doOnNext(r -> {
                        ChatResponse chat = r.chatResponse();
                        if (chat == null) {
                            return;
                        }
                        String piece = textOf(chat);
                        if (piece != null) {
                            text.append(piece);
                        }
                        if (hasUsage(chat.getMetadata().getUsage())) {
                            withUsage.set(chat);
                        }
                    })
                    .doOnError(e -> metrics.modelCallFailed(stage))
                    .doOnComplete(() -> {
                        ChatResponse last = withUsage.get();
                        TokenUsage usage = last != null
                                ? usageOf(last, request.prompt())
                                : new TokenUsage(
                                        TokenEstimator.estimate(request.prompt().getInstructions()),
                                        TokenEstimator.estimate(text.toString()),
                                        true);
                        record(record, stage, modelOf(last), usage, start);
                    });
        });
    }

    /**
     * 从一次响应里取 Token 用量，取不到就估算（ch09-4 之二）。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>{@code response} 不为 null、{@code getMetadata().getUsage()} 的输入或输出 Token 有一个大于 0：用它（为 null 的那一项按 0），
     *       {@code estimated=false}
     *   <li>否则估算：输入 = {@link TokenEstimator#estimate(java.util.List)}（{@code prompt.getInstructions()}）；输出 =
     *       {@link TokenEstimator#estimate(String)}（第一个结果的文字，没有结果按空串）；{@code estimated=true}
     * </ul>
     *
     * Spring AI 在模型没报告用量时给的是 {@code EmptyUsage}（两项都是 0），不是 null，所以要按「大于 0」判断。
     *
     * @param response 模型响应，可以为 null
     * @param prompt 这次发出去的提示词
     * @return 用量
     */
    public static TokenUsage usageOf(ChatResponse response, Prompt prompt) {
        Usage usage = response == null ? null : response.getMetadata().getUsage();
        if (hasUsage(usage)) {
            return new TokenUsage(orZero(usage.getPromptTokens()), orZero(usage.getCompletionTokens()), false);
        }
        String output = response == null ? null : textOf(response);
        return new TokenUsage(TokenEstimator.estimate(prompt.getInstructions()), TokenEstimator.estimate(output), true);
    }

    /** 记进这一轮（如果有）和指标。 */
    private void record(TurnRecord record, String stage, String model, TokenUsage usage, long start) {
        ModelCall call = new ModelCall(
                stage,
                model,
                usage.input(),
                usage.output(),
                usage.estimated(),
                (System.nanoTime() - start) / 1_000_000);
        if (record != null) {
            record.add(call);
        }
        metrics.modelCall(call);
    }

    private static boolean hasUsage(Usage usage) {
        return usage != null && (orZero(usage.getPromptTokens()) > 0 || orZero(usage.getCompletionTokens()) > 0);
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String textOf(ChatResponse response) {
        Generation result = response.getResult();
        return result == null || result.getOutput() == null
                ? null
                : result.getOutput().getText();
    }

    private static String modelOf(ChatResponse response) {
        if (response == null) {
            return null;
        }
        String model = response.getMetadata().getModel();
        return model == null || model.isBlank() ? null : model;
    }

    /** 这一轮：请求上下文里的优先（流式），其次是当前线程上绑定的（阻塞）。 */
    private static TurnRecord recordOf(ChatClientRequest request) {
        return request.context().get(TurnRecord.CONTEXT_KEY) instanceof TurnRecord record
                ? record
                : TurnRecord.current();
    }

    private static String stageOf(ChatClientRequest request) {
        return request.context().get(STAGE_KEY) instanceof String stage ? stage : OTHER;
    }

    @Override
    public String getName() {
        return "tokenUsage";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
