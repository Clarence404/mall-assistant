package io.github.clarence404.assistant.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一次办事的「工作台」：已经走了哪些步、查到过哪些订单、每个调用做过几次、有没有提出待确认操作（第 7 章引入）。
 *
 * <p>所在层：业务层，一次办事一个，用完就扔，不跨请求、不进记忆。{@link AgentLoop} / {@link NativeAgentRunner} 创建它，
 * {@link AgentToolbox} 往里记查到的订单，{@link WriteActionGuard} 靠它核对「这笔订单是不是在查询结果里出现过、现在是什么状态」，
 * {@link AgentPlanner} 用它拼「办事记录」给模型看。
 *
 * <p><b>为什么要记「查到过的订单」</b>：写操作的订单号是模型填的。模型可能抄错一位、可能编一个、也可能被资料里的一句「顺便把订单 X 取消」带偏。
 * 规定「只能对这次查询结果里出现过的订单提写操作」，等于要求先用用户自己的令牌把这笔订单查出来——商城只会返回本人的订单，
 * 查得出来就说明是他的；同时也拿到了它<b>现在</b>的状态，代码能据此判断能不能办（{@link OrderRules}）。
 *
 * <p>不是线程安全的：一次办事里的步骤一个接一个执行。
 */
public final class AgentRun {

    private static final Logger log = LoggerFactory.getLogger(AgentRun.class);

    private final AgentTask task;
    private final Consumer<AgentEvent> listener;
    private final Clock clock;
    private final Instant start;
    private final List<AgentStep> steps = new ArrayList<>();
    private final Map<String, ObservedOrder> orders = new LinkedHashMap<>();
    private final Map<String, Integer> calls = new HashMap<>();
    private PendingConfirmation confirmation;

    /**
     * 开始一次办事。
     *
     * @param task 要办的事
     * @param listener 每走一步、每生成一个待确认操作时通知谁（流式接口推事件）；不需要时传 {@code e -> {}}
     */
    public AgentRun(AgentTask task, Consumer<AgentEvent> listener) {
        this(task, listener, Clock.systemUTC());
    }

    /**
     * 开始一次办事，总时长按给定的时钟算（录制回放时用可以拨动的时钟，「超时」才回放得出来）。
     *
     * @param task 要办的事
     * @param listener 每走一步、每生成一个待确认操作时通知谁
     * @param clock 算总时长的时钟
     */
    public AgentRun(AgentTask task, Consumer<AgentEvent> listener, Clock clock) {
        this.task = task;
        this.listener = listener;
        this.clock = clock;
        this.start = clock.instant();
    }

    /** 要办的事。 */
    public AgentTask task() {
        return task;
    }

    /** 已经走过的步，按顺序，不可修改。 */
    public List<AgentStep> steps() {
        return List.copyOf(steps);
    }

    /** 下一步的编号（从 1 开始）。 */
    public int nextIndex() {
        return steps.size() + 1;
    }

    /**
     * 记下一步，并通知监听器（流式接口马上推给页面）。
     *
     * @param step 这一步
     */
    public void record(AgentStep step) {
        steps.add(step);
        log.info(
                "Agent 第 {} 步：{} {} → {}（{} ms）",
                step.index(),
                step.action(),
                step.arguments(),
                step.kind(),
                step.durationMs());
        listener.accept(new AgentEvent.StepTaken(step));
    }

    /** 从开始到现在多少毫秒。 */
    public long elapsedMs() {
        return Duration.between(start, clock.instant()).toMillis();
    }

    /**
     * 记下查到的一笔订单（{@link AgentToolbox} 查订单、列订单时调用）；同一笔查了多次以最后一次为准。
     *
     * @param order 订单
     */
    public void observe(ObservedOrder order) {
        orders.put(order.orderNo(), order);
    }

    /**
     * 这次查到过的一笔订单。
     *
     * @param orderNo 订单号
     * @return 订单；没在查询结果里出现过时为 null
     */
    public ObservedOrder observed(String orderNo) {
        return orders.get(orderNo);
    }

    /**
     * 某个调用（{@link AgentAction#key()}）已经执行过几次。
     *
     * @param key 调用的键
     * @return 次数
     */
    public int calls(String key) {
        return calls.getOrDefault(key, 0);
    }

    /**
     * 某个调用又执行了一次。
     *
     * @param key 调用的键
     */
    public void called(String key) {
        calls.merge(key, 1, Integer::sum);
    }

    /** 这次办事提出的待确认操作；没有时为 null。 */
    public PendingConfirmation confirmation() {
        return confirmation;
    }

    /**
     * 记下待确认操作，并通知监听器（流式接口推 confirm 事件，页面画确认卡片）。
     *
     * @param pending 待确认操作
     */
    public void propose(PendingConfirmation pending) {
        this.confirmation = pending;
        listener.accept(new AgentEvent.ConfirmationRequested(pending));
    }

    /**
     * 查到过的一笔订单：代码判断「能不能办」、拼确认卡片的说明时用。
     *
     * @param orderNo 订单号
     * @param status 商城的状态枚举名，如 PENDING_PAYMENT
     * @param statusText 中文状态，如「待支付（还没付款，超时未付会自动取消）」
     * @param items 商品摘要，可以为 null
     * @param payAmount 实付金额，可以为 null
     */
    public record ObservedOrder(String orderNo, String status, String statusText, String items, String payAmount) {}
}
