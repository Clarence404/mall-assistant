package io.github.clarence404.assistant.ops;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 智能客服的业务指标：请求数与错误码、耗时分位、Token、限流次数、RAG 命中、注入拦截、Agent 步数（第 9 章引入）。
 *
 * <p>所在层：上线运维。{@code ChatController}（每轮结束 {@link #turn}）、{@code guard.ChatGuard}（被挡 {@link #rejected}）、{@link UsageAdvisor}
 * （每次模型调用 {@link #modelCall}）、{@code ChatService} / {@code ExternalMcpTools}（注入拦截 {@link #injectionBlocked}）调它。
 * 指标经 Micrometer 注册到 Spring Boot 的 {@code MeterRegistry}，{@code /actuator/prometheus} 按 Prometheus 文本格式输出（点换成下划线、计数器加
 * {@code _total}、计时器是 {@code _seconds_bucket / _count / _sum}）。仓库根目录 {@code monitoring/grafana/} 下的面板和 {@code monitoring/prometheus/} 下的告警规则就是按这些名字写的。
 *
 * <p><b>标签只用几个值的维度</b>：端点（chat / stream）、意图（9 个）、结果（OK 或错误码）、步骤、原因。<b>不用</b>用户 ID、IP、会话 ID、问题原文：
 * Prometheus 里每个标签组合是一条时间序列，值无限多的标签会把它撑爆（「高基数」）。「谁用了多少」看 {@link UsageLedger}。
 *
 * <table>
 *   <caption>指标一览</caption>
 *   <tr><th>Micrometer 名字</th><th>类型</th><th>标签</th><th>回答什么问题</th></tr>
 *   <tr><td>assistant.chat.requests</td><td>计数</td><td>endpoint、intent、outcome</td><td>每秒多少请求、错误率、各错误码占比</td></tr>
 *   <tr><td>assistant.chat.duration</td><td>计时（直方图）</td><td>endpoint、intent</td><td>p50 / p95 / p99 一轮多久</td></tr>
 *   <tr><td>assistant.guard.rejections</td><td>计数</td><td>reason</td><td>限流、太忙、超预算各挡了多少</td></tr>
 *   <tr><td>assistant.model.tokens</td><td>计数</td><td>type（input / output）、stage、estimated</td><td>Token 消耗速度，哪一步最费</td></tr>
 *   <tr><td>assistant.model.calls</td><td>计数</td><td>stage、outcome</td><td>模型调用次数与失败</td></tr>
 *   <tr><td>assistant.rag.retrievals</td><td>计数</td><td>result</td><td>规则问题有多少检索到了、多少没找到、多少降级</td></tr>
 *   <tr><td>assistant.injection.blocked</td><td>计数</td><td>where（rag / mcp）、rule</td><td>注入过滤挡了多少（突然变多 = 知识库被人改了，或误杀）</td></tr>
 *   <tr><td>assistant.agent.steps</td><td>分布</td><td>stop</td><td>Agent 一件事走几步、为什么停</td></tr>
 *   <tr><td>assistant.model.circuit.state</td><td>仪表</td><td></td><td>熔断器状态（{@code guard.ModelCircuitBreaker} 注册）</td></tr>
 *   <tr><td>assistant.chat.inflight</td><td>仪表</td><td></td><td>正在处理的对话数（{@code guard.ChatGuard} 注册）</td></tr>
 * </table>
 */
@Component
public class ChatMetrics {

    /** 成功的结果标签。 */
    public static final String OK = "OK";

    private final MeterRegistry registry;

    /**
     * 创建（Spring 注入）：有 Actuator 时用它的注册表，没有时（极少数精简的测试上下文）用一个只在内存里的。
     *
     * @param registry 注册表
     */
    @Autowired
    public ChatMetrics(ObjectProvider<MeterRegistry> registry) {
        this(registry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * 测试用。
     *
     * @param registry 注册表
     */
    public ChatMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 不接任何监控的（测试里手工组装时用）。
     *
     * @return 指标
     */
    public static ChatMetrics noop() {
        return new ChatMetrics(new SimpleMeterRegistry());
    }

    /** 注册表（{@code ChatGuard} 注册仪表用）。 */
    public MeterRegistry registry() {
        return registry;
    }

    /**
     * 一轮对话结束：请求数、耗时；走了 RAG 的记检索结果，走了 Agent 的记步数。
     *
     * @param endpoint {@code chat}（阻塞）或 {@code stream}（流式）
     * @param record 这一轮
     * @param outcome {@link #OK} 或错误码
     */
    public void turn(String endpoint, TurnRecord record, String outcome) {
        String intent = record.intent() == null ? UsageLedger.NO_INTENT : record.intent();
        Counter.builder("assistant.chat.requests")
                .description("对话请求数")
                .tags("endpoint", endpoint, "intent", intent, "outcome", outcome)
                .register(registry)
                .increment();
        Timer.builder("assistant.chat.duration")
                .description("一轮对话的耗时（从进门到最后一个字）")
                .tags("endpoint", endpoint, "intent", intent)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(50))
                .maximumExpectedValue(Duration.ofMinutes(3))
                .register(registry)
                .record(Duration.ofNanos(record.elapsedNanos()));
        if (record.ragResult() != null) {
            Counter.builder("assistant.rag.retrievals")
                    .description("RAG 检索结果")
                    .tag("result", record.ragResult())
                    .register(registry)
                    .increment();
        }
        if (record.agentSteps() >= 0) {
            DistributionSummary.builder("assistant.agent.steps")
                    .description("Agent 一件事走了几步")
                    .tag("stop", record.agentStop() == null ? "UNKNOWN" : record.agentStop())
                    .register(registry)
                    .record(record.agentSteps());
        }
    }

    /**
     * 进门时被挡下（也算一次请求，结果是原因）。
     *
     * @param endpoint {@code chat} 或 {@code stream}
     * @param reason 原因（错误码）
     */
    public void rejected(String endpoint, String reason) {
        Counter.builder("assistant.guard.rejections")
                .description("被限流、并发上限、每日预算挡下的请求")
                .tag("reason", reason)
                .register(registry)
                .increment();
        Counter.builder("assistant.chat.requests")
                .tags("endpoint", endpoint, "intent", UsageLedger.NO_INTENT, "outcome", reason)
                .register(registry)
                .increment();
    }

    /**
     * 一次模型调用成功：Token 与调用次数。
     *
     * @param call 调用
     */
    public void modelCall(ModelCall call) {
        String estimated = String.valueOf(call.estimated());
        tokens("input", call.stage(), estimated).increment(call.inputTokens());
        tokens("output", call.stage(), estimated).increment(call.outputTokens());
        calls(call.stage(), OK).increment();
    }

    /**
     * 一次模型调用失败。
     *
     * @param stage 步骤
     */
    public void modelCallFailed(String stage) {
        calls(stage, "ERROR").increment();
    }

    /**
     * 提示注入过滤挡下了一块。
     *
     * @param where {@code rag}（知识库资料）或 {@code mcp}（外部 MCP 工具）
     * @param rule 规则编号
     */
    public void injectionBlocked(String where, String rule) {
        Counter.builder("assistant.injection.blocked")
                .description("被提示注入过滤挡下的资料块 / 工具输出")
                .tags("where", where, "rule", rule)
                .register(registry)
                .increment();
    }

    private Counter tokens(String type, String stage, String estimated) {
        return Counter.builder("assistant.model.tokens")
                .description("模型调用的 Token 数")
                .tags("type", type, "stage", stage, "estimated", estimated)
                .register(registry);
    }

    private Counter calls(String stage, String outcome) {
        return Counter.builder("assistant.model.calls")
                .description("模型调用次数")
                .tags("stage", stage, "outcome", outcome)
                .register(registry);
    }
}
