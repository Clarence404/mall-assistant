package io.github.clarence404.assistant.agent;

import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.prompt.AssistantPrompts;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * 方案 A：把循环交给 Spring AI 原生的工具调用（{@code assistant.agent.mode=native}，第 7 章引入，用来和方案 B 对比）。
 *
 * <p>所在层：Agent 的另一种实现。上游是 {@link AgentService}；工具的执行、写操作的核对与登记和方案 B 完全共用（{@link AgentToolbox}、
 * {@link WriteActionGuard}、{@link ConfirmationService}），不同的只是「谁来转圈」。
 *
 * <p><b>怎么转圈</b>：和第 3 章一样，把工具（这里是 8 个 {@code @Tool} 方法）注册给这次请求，{@code call()} 一次。Spring AI 的
 * {@code ToolCallingAdvisor} 在内部循环：模型要调工具 → 执行 → 把结果作为工具消息追加进去 → 再问模型……直到模型直接回答。我们的代码只在每个工具方法里
 * 执行一步、记一步。
 *
 * <p><b>和方案 B 比</b>（实测见 docs/ch07.md）：
 *
 * <ul>
 *   <li>代码短：没有自己的循环、不用拼办事记录；模型一次可以并行调好几个工具
 *   <li>看不见「想法」：模型调工具时不输出文字，调试面板里每一步的 thought 是空的
 *   <li>刹车不在我们手里：一次请求里最多调几次工具由 Spring AI 的全局配置 {@code spring.ai.tools.limits.*} 决定（2.0.2 没有按请求设置的入口），
 *       本项目是共 4 次、每个工具 2 次，比方案 B 的 6 步少；这里的工具方法只能在超过 {@code max-steps} 后返回「别再调了」
 *   <li>要模型支持原生工具调用：命令行模型（{@code native-calling=false}）不行，{@link AgentService} 会退回方案 B
 * </ul>
 */
@Component
public class NativeAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(NativeAgentRunner.class);

    /** ToolContext 里放这次办事的键：工具方法从这里拿到 {@link AgentRun}（令牌也在里面，只给代码用，不发给模型）。 */
    static final String RUN = "agentRun";

    private final ChatClient chatClient;
    private final AssistantPrompts prompts;
    private final AgentToolbox toolbox;
    private final WriteActionGuard guard;
    private final ConfirmationService confirmations;
    private final AgentProperties properties;
    private final Resource systemTemplate;
    private final List<ToolCallback> callbacks;

    /**
     * 创建。
     *
     * @param chatClient 共享的 ChatClient
     * @param prompts 模板变量
     * @param toolbox 只读工具
     * @param guard 写操作核对
     * @param confirmations 待确认操作登记
     * @param properties {@code assistant.agent.*}
     */
    public NativeAgentRunner(
            ChatClient chatClient,
            AssistantPrompts prompts,
            AgentToolbox toolbox,
            WriteActionGuard guard,
            ConfirmationService confirmations,
            AgentProperties properties) {
        this.chatClient = chatClient;
        this.prompts = prompts;
        this.toolbox = toolbox;
        this.guard = guard;
        this.confirmations = confirmations;
        this.properties = properties;
        this.systemTemplate = new ClassPathResource("prompts/" + properties.nativePrompt() + ".st");
        // 按名字排序：工具清单的顺序就是提示词的一部分，顺序固定，录制回放才稳定（同第 3 章 MallTools）
        this.callbacks = Arrays.stream(ToolCallbacks.from(new Tools()))
                .sorted(Comparator.comparing(c -> c.getToolDefinition().name()))
                .toList();
    }

    /**
     * 跑一次办事：一次 {@code call()}，Spring AI 在里面循环调工具。
     *
     * @param run 这次办事
     * @return 结果
     * @throws ModelCallException 模型调用失败
     */
    public AgentResult run(AgentRun run) {
        long start = System.nanoTime();
        String content;
        try {
            content = chatClient
                    .prompt()
                    .system(s -> s.text(systemTemplate).params(prompts.variables()))
                    .messages(run.task().history())
                    .user(run.task().message())
                    .tools(callbacks.toArray())
                    .toolContext(Map.of(RUN, run))
                    .options(ChatOptions.builder().temperature(0.0))
                    .call()
                    .content();
        } catch (RuntimeException e) {
            throw ModelCallException.from(e);
        }
        String answer = Objects.requireNonNullElse(content, "").strip();
        AgentTrace.StopReason reason;
        String reply;
        if (run.confirmation() != null) {
            reason = AgentTrace.StopReason.AWAITING_CONFIRMATION;
            reply = AgentReplies.awaitingConfirmation(run.confirmation());
        } else {
            reason = AgentTrace.StopReason.FINISHED;
            reply = AgentReplies.checkFinish(answer);
            run.record(
                    AgentLoop.step(run, null, AgentTool.FINISH.toolName(), AgentStep.Kind.FINISH, "{}", answer, start));
        }
        log.info("Agent（native）停止：{}，共 {} 步，{} ms", reason, run.steps().size(), run.elapsedMs());
        return new AgentResult(
                reply, new AgentTrace("native", run.steps(), reason, run.elapsedMs()), run.confirmation());
    }

    /**
     * 执行模型选的一个工具：和方案 B 同样的校验（看懂参数、重复次数、只读直接执行、写操作只登记待确认），记一步。
     *
     * @param context 这次请求的 ToolContext（里面是 {@link AgentRun}）
     * @param decision 模型的调用（工具名 + 参数，拼成和方案 B 一样的 {@link AgentDecision}）
     * @return 交还给模型的工具结果
     */
    String perform(ToolContext context, AgentDecision decision) {
        AgentRun run = (AgentRun) context.getContext().get(RUN);
        long start = System.nanoTime();
        if (run.confirmation() != null) {
            return "这一轮已经提出了待确认操作，不要再调用工具，直接告诉用户去点确认卡片";
        }
        if (run.steps().size() >= properties.maxSteps()) {
            return "已经到了步数上限，不要再调用工具，直接告诉用户没办完";
        }
        AgentAction.Parsed parsed = AgentAction.parse(decision);
        if (!parsed.valid()) {
            run.record(
                    AgentLoop.step(run, null, decision.action(), AgentStep.Kind.REJECTED, "{}", parsed.error(), start));
            return parsed.error();
        }
        AgentAction action = parsed.action();
        String observation;
        AgentStep.Kind kind;
        if (run.calls(action.key()) >= properties.maxSameCalls()) {
            kind = AgentStep.Kind.REJECTED;
            observation = "这个调用已经做过了，结果不会变，不要再重复";
        } else if (action.tool().kind() == AgentTool.Kind.READ) {
            run.called(action.key());
            kind = AgentStep.Kind.READ;
            observation = toolbox.read(run, action);
        } else {
            run.called(action.key());
            WriteActionGuard.Proposal proposal = guard.check(run, action);
            if (!proposal.accepted()) {
                kind = AgentStep.Kind.REJECTED;
                observation = "没有生成确认：" + proposal.rejection();
            } else {
                PendingConfirmation pending = confirmations.create(
                        proposal.action(), run.task().owner(), run.task().conversationId());
                run.record(AgentLoop.step(
                        run,
                        null,
                        action.tool().toolName(),
                        AgentStep.Kind.PROPOSE,
                        action.argumentsJson(),
                        "已生成待确认操作（" + pending.summary() + "），等用户在页面上点确认",
                        start));
                run.propose(pending);
                return "已生成待确认操作，还没有执行：用一句话请用户在页面的确认卡片上点「确认」";
            }
        }
        run.record(
                AgentLoop.step(run, null, action.tool().toolName(), kind, action.argumentsJson(), observation, start));
        return observation;
    }

    /** 注册给模型的 8 个工具：只是把模型的调用转成 {@link AgentDecision}，交给 {@link #perform}。说明写给模型看。 */
    public final class Tools {

        @Tool(name = "listMyOrders", description = "列出当前用户最近的订单。用户没给订单号时先用它。")
        public String listMyOrders(ToolContext context) {
            return perform(context, AgentDecision.of(null, "listMyOrders", null));
        }

        @Tool(name = "queryOrder", description = "查一笔订单的状态、商品、收货信息和 allowedActions（这笔订单现在能办的操作）。")
        public String queryOrder(
                @ToolParam(description = "订单号，19 位或 23 位数字，照抄用户的话或查询结果") String orderNo, ToolContext context) {
            return perform(context, AgentDecision.of(null, "queryOrder", orderNo));
        }

        @Tool(name = "queryPayment", description = "查一笔订单的支付状态。")
        public String queryPayment(@ToolParam(description = "订单号") String orderNo, ToolContext context) {
            return perform(context, AgentDecision.of(null, "queryPayment", orderNo));
        }

        @Tool(name = "queryProduct", description = "按商品编号查商品的名称、价格、是否在售、是否有货。")
        public String queryProduct(@ToolParam(description = "商品编号，一串数字") String skuId, ToolContext context) {
            return perform(context, new AgentDecision(null, "queryProduct", null, skuId));
        }

        @Tool(name = "searchPolicy", description = "查商城的售后规则（退换货、退款、运费等），返回编了号的规则资料。")
        public String searchPolicy(@ToolParam(description = "要查的规则，一句话") String query, ToolContext context) {
            return perform(context, new AgentDecision(null, "searchPolicy", null, query));
        }

        @Tool(name = "cancelOrder", description = "取消一笔待支付的订单。不会马上执行，只生成待确认操作，要用户点按钮确认。")
        public String cancelOrder(@ToolParam(description = "订单号") String orderNo, ToolContext context) {
            return perform(context, AgentDecision.of(null, "cancelOrder", orderNo));
        }

        @Tool(name = "changeAddress", description = "修改一笔订单的收货地址（发货前）。不会马上执行，只生成待确认操作，要用户点按钮确认。")
        public String changeAddress(
                @ToolParam(description = "订单号") String orderNo,
                @ToolParam(description = "新的详细地址，照抄用户的原话") String address,
                ToolContext context) {
            return perform(context, new AgentDecision(null, "changeAddress", orderNo, address));
        }

        @Tool(name = "applyAfterSales", description = "为一笔订单提交退款、退货等售后申请，由人工审核。不会马上执行，只生成待确认操作，要用户点按钮确认。")
        public String applyAfterSales(
                @ToolParam(description = "订单号") String orderNo,
                @ToolParam(description = "售后原因，用户说的") String reason,
                ToolContext context) {
            return perform(context, new AgentDecision(null, "applyAfterSales", orderNo, reason));
        }
    }
}
