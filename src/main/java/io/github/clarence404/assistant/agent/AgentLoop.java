package io.github.clarence404.assistant.agent;

import io.github.clarence404.assistant.error.ModelCallException;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 方案 B 的 Agent 循环：想 → 选工具 → 执行 → 看结果，直到办完、提出写操作、或者碰到刹车（第 7 章引入）。
 *
 * <p>所在层：Agent 的核心。上游是 {@link AgentService}；下游是 {@link AgentPlanner}（想）、{@link AgentAction#parse}（看懂、校验）、
 * {@link AgentToolbox}（执行只读工具）、{@link WriteActionGuard} 与 {@link ConfirmationService#create}（写操作只生成待确认）。
 *
 * <p><b>Agent 是什么</b>：前几章的客服是「一问一答」——代码决定查什么（第 3 章按意图直接查询）、模型只负责说人话。Agent 把「下一步干什么」也交给模型：
 * 每一步模型看着目前为止的办事记录，决定再查点什么、还是可以办了、还是该回答了；代码执行它选的工具，把结果追加进办事记录，再问它下一步。
 * 一件「订单买错了帮我取消」的事，典型的轨迹是：listMyOrders → queryOrder → cancelOrder（生成确认卡片，这一轮结束）。
 *
 * <p><b>为什么要刹车</b>：循环的出口全靠模型说「办完了」，而小模型经常不说——反复查同一笔订单、在两个工具之间来回、输出看不懂的东西。所以每一步开始前检查：
 *
 * <ul>
 *   <li>步数上限（{@code max-steps}，默认 6）：一件售后的事正常 2～4 步，6 步还没办完多半是在兜圈子
 *   <li>同一个调用的次数上限（{@code max-same-calls}，默认 2）：同样的工具、同样的参数，结果不会变，再执行一次没有意义；超了不执行，告诉模型换一步，
 *       被拦下后还在重复就直接停
 *   <li>总时长（{@code timeout}）：每一步都是一次模型调用，本地小模型一步几秒到十几秒
 *   <li>页面关了（流式被取消）：不再浪费模型调用
 * </ul>
 *
 * 停下来时回固定话术，并且<b>保证没有执行任何写操作</b>（写操作本来就不在循环里执行）。
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final AgentPlanner planner;
    private final AgentToolbox toolbox;
    private final WriteActionGuard guard;
    private final ConfirmationService confirmations;
    private final AgentProperties properties;

    /**
     * 创建。
     *
     * @param planner 让模型想下一步
     * @param toolbox 执行只读工具
     * @param guard 核对写操作
     * @param confirmations 登记待确认操作
     * @param properties {@code assistant.agent.*}（刹车）
     */
    public AgentLoop(
            AgentPlanner planner,
            AgentToolbox toolbox,
            WriteActionGuard guard,
            ConfirmationService confirmations,
            AgentProperties properties) {
        this.planner = planner;
        this.toolbox = toolbox;
        this.guard = guard;
        this.confirmations = confirmations;
        this.properties = properties;
    }

    /**
     * 跑一次办事循环（ch07-2）。
     *
     * <p>要求（测试按这个写），每一轮：
     *
     * <ol>
     *   <li>先检查刹车：已经走满 {@code max-steps} 步 → STEP_LIMIT；超过 {@code timeout} → TIMEOUT；{@code cancelled} 为 true → CANCELLED。
     *       停下时回 {@link AgentReplies} 的固定话术
     *   <li>{@code planner.decide(run)} 想下一步，{@link AgentAction#parse} 看懂；无效就记一步 REJECTED（结果是无效的原因），继续下一轮
     *   <li>finish：记一步 FINISH（结果是回答），停，原因 FINISHED，回答用模型的 answer（经 {@link AgentReplies#checkFinish}）
     *   <li>其余：这个调用（{@link AgentAction#key()}）已经执行过 {@code max-same-calls} 次 → 不执行，记一步 REJECTED 提醒它别重复；
     *       这种「被拦下后还在重复」累计到 2 次 → 停，原因 REPEATED。没超就 {@code run.called(key)}，然后：
     *       <ul>
     *         <li>只读：{@code toolbox.read} 执行，记一步 READ
     *         <li>写操作：{@code guard.check} 核对；不通过记一步 REJECTED（结果是原因），继续；通过则 {@code confirmations.create} 登记，
     *             记一步 PROPOSE，再 {@code run.propose(...)}（这两个顺序不能反：页面先看到这一步、再看到确认卡片），停，原因
     *             AWAITING_CONFIRMATION，回答用 {@link AgentReplies#awaitingConfirmation}
     *       </ul>
     * </ol>
     *
     * 每一步用 {@link #step} 生成（耗时从这一轮开始算起），用 {@code run.record} 记下（同时推给页面）。
     *
     * @param run 这次办事
     * @param cancelled 页面是否已经关掉（流式被取消）；阻塞接口传 {@code () -> false}
     * @return 结果
     * @throws ModelCallException 某一步调模型失败：整次办事失败，接口返回 503 / error 事件（这时一定没有执行任何写操作）
     */
    public AgentResult run(AgentRun run, BooleanSupplier cancelled) {
        int repeatedRejections = 0;
        while (true) {
            if (run.steps().size() >= properties.maxSteps()) {
                return stop(run, AgentReplies.stepLimit(properties.maxSteps()), AgentTrace.StopReason.STEP_LIMIT);
            }
            if (run.elapsedMs() > properties.timeout().toMillis()) {
                return stop(run, AgentReplies.timeout(properties.timeout().toSeconds()), AgentTrace.StopReason.TIMEOUT);
            }
            if (cancelled.getAsBoolean()) {
                return stop(run, AgentReplies.cancelled(), AgentTrace.StopReason.CANCELLED);
            }
            long start = System.nanoTime();
            AgentDecision decision = planner.decide(run);
            AgentAction.Parsed parsed = AgentAction.parse(decision);
            if (!parsed.valid()) {
                run.record(step(
                        run,
                        parsed.thought(),
                        decision == null ? null : decision.action(),
                        AgentStep.Kind.REJECTED,
                        "{}",
                        parsed.error(),
                        start));
                continue;
            }
            AgentAction action = parsed.action();
            if (action.tool() == AgentTool.FINISH) {
                String answer = action.argument("answer");
                run.record(step(run, action, AgentStep.Kind.FINISH, answer, start));
                return stop(run, AgentReplies.checkFinish(answer), AgentTrace.StopReason.FINISHED);
            }
            if (run.calls(action.key()) >= properties.maxSameCalls()) {
                run.record(step(
                        run,
                        action,
                        AgentStep.Kind.REJECTED,
                        "这个调用已经做过 " + run.calls(action.key()) + " 次，结果见上面的记录，不会变了；不要再重复，换一步或者用 finish 回答用户",
                        start));
                if (++repeatedRejections >= 2) {
                    return stop(run, AgentReplies.repeated(), AgentTrace.StopReason.REPEATED);
                }
                continue;
            }
            run.called(action.key());
            if (action.tool().kind() == AgentTool.Kind.READ) {
                run.record(step(run, action, AgentStep.Kind.READ, toolbox.read(run, action), start));
                continue;
            }
            WriteActionGuard.Proposal proposal = guard.check(run, action);
            if (!proposal.accepted()) {
                run.record(step(run, action, AgentStep.Kind.REJECTED, "没有生成确认：" + proposal.rejection(), start));
                continue;
            }
            PendingConfirmation pending = confirmations.create(
                    proposal.action(), run.task().owner(), run.task().conversationId());
            run.record(step(
                    run,
                    action,
                    AgentStep.Kind.PROPOSE,
                    "已生成待确认操作（" + pending.summary() + "），等用户在页面上点确认，这一轮到此结束",
                    start));
            run.propose(pending);
            return stop(run, AgentReplies.awaitingConfirmation(pending), AgentTrace.StopReason.AWAITING_CONFIRMATION);
        }
    }

    /** 停下：生成结果（调试信息里带上全部步骤）。 */
    private static AgentResult stop(AgentRun run, String reply, AgentTrace.StopReason reason) {
        log.info("Agent 停止：{}，共 {} 步，{} ms", reason, run.steps().size(), run.elapsedMs());
        return new AgentResult(reply, new AgentTrace("loop", run.steps(), reason, run.elapsedMs()), run.confirmation());
    }

    /** 一个有效动作的一步。 */
    private static AgentStep step(
            AgentRun run, AgentAction action, AgentStep.Kind kind, String observation, long start) {
        return step(
                run,
                action.thought(),
                action.tool().toolName(),
                kind,
                action.tool() == AgentTool.FINISH ? "{}" : action.argumentsJson(),
                observation,
                start);
    }

    /**
     * 生成一步。
     *
     * @param run 这次办事（取下一步的编号）
     * @param thought 想法
     * @param action 工具名
     * @param kind 结果类型
     * @param arguments 参数 JSON
     * @param observation 结果
     * @param start 这一轮开始的 {@link System#nanoTime()}
     * @return 这一步
     */
    static AgentStep step(
            AgentRun run,
            String thought,
            String action,
            AgentStep.Kind kind,
            String arguments,
            String observation,
            long start) {
        return new AgentStep(
                run.nextIndex(),
                thought,
                action,
                kind,
                arguments,
                observation,
                (System.nanoTime() - start) / 1_000_000);
    }
}
