package io.github.clarence404.assistant.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;

/**
 * 一轮对话的「账本」：谁问的、哪个会话、什么意图，以及这一轮里每次模型调用的 Token、RAG 的结果、Agent 走了几步（第 9 章引入）。
 *
 * <p>所在层：上线运维。{@code ChatController} 在一轮开始时 {@link UsageLedger#open} 一个，交给 {@code ChatService}；{@link UsageAdvisor}
 * 每次模型调用后往里加一条 {@link ModelCall}；这一轮结束时（成功、失败、页面关掉都算）{@link UsageLedger#commit} 记进总账、
 * {@link ChatMetrics#turn} 记进指标。
 *
 * <p><b>Advisor 怎么知道「这次调用属于哪一轮」</b>：模型调用散在意图识别、追问改写、回答、Agent 的每一步里，不想给每个方法都加一个参数，就用两种办法把它
 * 「带」到 advisor 那里：
 *
 * <ul>
 *   <li>阻塞调用：{@link #bind()} 把它放进当前线程的 {@link ThreadLocal}，advisor 在同一个线程里用 {@link #current()} 取到。用完一定要 close
 *       （try-with-resources），虚拟线程虽然不复用，平台线程池里的线程是复用的，忘了清就会串到下一个请求
 *   <li>流式调用：模型的流在别的线程上推数据，ThreadLocal 跟不过去；{@code ChatService} 在拼请求时用
 *       {@code .advisors(a -> a.param(TurnRecord.CONTEXT_KEY, record))} 把它放进这次请求的上下文，advisor 从 {@code request.context()} 里取
 * </ul>
 *
 * 两样都没有（如 {@code /api/intent} 调试接口、知识库的问题改写）时 advisor 只记指标，不进任何人的账。
 */
public final class TurnRecord {

    /** 放进 ChatClient 请求上下文里的键。 */
    public static final String CONTEXT_KEY = TurnRecord.class.getName();

    /** 没有调用方信息时的归属（测试、评测、调试接口）。 */
    public static final String UNATTRIBUTED = "unattributed";

    private static final ThreadLocal<TurnRecord> CURRENT = new ThreadLocal<>();

    private final String caller;
    private final String conversationId;
    private final long startNanos = System.nanoTime();
    private final List<ModelCall> calls = new CopyOnWriteArrayList<>();
    private final AtomicBoolean committed = new AtomicBoolean();

    // 审查修复：后台工作的计数与等它停下再执行的收尾动作，见 hold() / whenSettled()；都在 settleLock 里读写
    private final Object settleLock = new Object();
    private final List<Runnable> deferred = new ArrayList<>();
    private int holds;
    private volatile String intent;
    private volatile String ragResult;
    private volatile int blockedChunks;
    private volatile int agentSteps = -1;
    private volatile String agentStop;

    /**
     * 创建（一般经 {@link UsageLedger#open}）。
     *
     * @param caller 调用方：{@code user:<商城用户 ID>}、{@code ip:<地址>}，不知道时 {@link #UNATTRIBUTED}
     * @param conversationId 会话 ID，单轮时为 null
     */
    public TurnRecord(String caller, String conversationId) {
        this.caller = caller == null ? UNATTRIBUTED : caller;
        this.conversationId = conversationId;
    }

    /**
     * 不属于任何调用方的一轮（前几章的测试手工调 ChatService、评测运行器用）：照样收集每次调用的 Token，只是不进总账。
     *
     * @return 一轮
     */
    public static TurnRecord detached() {
        return new TurnRecord(UNATTRIBUTED, null);
    }

    /**
     * 当前线程上绑定的那一轮。
     *
     * @return 一轮；没有绑定时为 null
     */
    public static TurnRecord current() {
        return CURRENT.get();
    }

    /**
     * 把这一轮绑定到当前线程，close 时恢复成绑定之前的（可以嵌套）。
     *
     * @return 用 try-with-resources 关闭的句柄
     */
    public Binding bind() {
        TurnRecord previous = CURRENT.get();
        CURRENT.set(this);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /**
     * 声明「这一轮还有后台工作在跑」，返回的句柄关掉时表示它停了（审查修复时引入）。
     *
     * <p>为什么要它：流式回答被取消（页面关了）时，下游的收尾立刻就会执行——放开会话的「正在回答」标记、归还并发名额、把这一轮记账。
     * 但 Agent 是一串阻塞调用，跑在另一个线程上，取消只是让它「下一步不再继续」，正在等的那次模型调用还要跑完。如果收尾不等它：名额和会话锁
     * 已经放了（别的请求进来，实际并发超过上限；同一个会话可以再发一句，两轮同时在写），这次调用的 Token 也落在已经提交的账之外。
     *
     * <p>用法：干活的线程开头 {@code hold()}，退出时（finally / try-with-resources）关掉；收尾的一方不直接执行，而是交给
     * {@link #whenSettled}。没有人 hold 时 {@code whenSettled} 立刻执行，和原来完全一样（普通问答、RAG 都是这种情况）。
     *
     * @return 句柄；重复关闭只算一次
     */
    public Hold hold() {
        synchronized (settleLock) {
            holds++;
        }
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            List<Runnable> ready;
            synchronized (settleLock) {
                if (--holds > 0) {
                    return;
                }
                ready = List.copyOf(deferred);
                deferred.clear();
            }
            // 在锁外面执行：收尾里有网络调用（Redis），不能占着锁；一个收尾抛异常不能连累后面的。
            // 先把线程的「被打断」标记拿掉、执行完再放回去：取消订阅时 Reactor 会打断跑 Agent 的线程，带着这个标记去做同步的 Redis 调用
            // 会立刻以 InterruptedException 失败——名额和会话锁就还不回去了（要等租期到）
            boolean interrupted = Thread.interrupted();
            try {
                ready.forEach(TurnRecord::runQuietly);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        };
    }

    /**
     * 这一轮的后台工作都停了之后执行 {@code action}：没有后台工作时立刻在当前线程执行；有的话记下来，等最后一个 {@link Hold} 关掉时，
     * 在关它的那个线程上按登记的顺序执行。
     *
     * @param action 收尾动作（放开会话、归还名额、记账……）
     */
    public void whenSettled(Runnable action) {
        synchronized (settleLock) {
            if (holds > 0) {
                deferred.add(action);
                return;
            }
        }
        action.run();
    }

    private static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(TurnRecord.class).warn("这一轮的收尾动作出错：{}", e.toString());
        }
    }

    /** {@link #hold()} 的句柄：关掉表示后台工作停了。 */
    @FunctionalInterface
    public interface Hold extends AutoCloseable {
        @Override
        void close();
    }

    /** 绑定句柄：close 不抛受检异常。 */
    @FunctionalInterface
    public interface Binding extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * 记一次模型调用（{@link UsageAdvisor}）。
     *
     * @param call 调用
     */
    public void add(ModelCall call) {
        calls.add(call);
    }

    /** 这一轮到现在为止的用量。 */
    public UsageTrace usage() {
        return UsageTrace.of(calls);
    }

    /** 这一轮的每次调用。 */
    public List<ModelCall> calls() {
        return List.copyOf(calls);
    }

    /**
     * 识别出的意图（{@code ChatService#begin}）。
     *
     * @param intent 意图名，如 {@code POLICY}
     */
    public void intent(String intent) {
        this.intent = intent;
    }

    /**
     * RAG 的结果（{@code ChatService} 的 RAG 路径）。
     *
     * @param result {@code HIT}（有资料、调了模型）、{@code NO_MATCH}、{@code ALL_BLOCKED}、{@code KNOWLEDGE_UNAVAILABLE}、{@code DEGRADED}
     * @param blocked 被提示注入过滤挡下的资料块数
     */
    public void rag(String result, int blocked) {
        this.ragResult = result;
        this.blockedChunks = blocked;
    }

    /**
     * Agent 走了几步、为什么停（{@code ChatService} 的 Agent 路径）。
     *
     * @param steps 步数
     * @param stop 停止原因
     */
    public void agent(int steps, String stop) {
        this.agentSteps = steps;
        this.agentStop = stop;
    }

    /**
     * 标记已经记过账（{@link UsageLedger#commit} 用，保证一轮只记一次：流式接口在 usage 事件和 doFinally 里都会尝试记）。
     *
     * @return 第一次调用时为 true
     */
    boolean markCommitted() {
        return committed.compareAndSet(false, true);
    }

    public String caller() {
        return caller;
    }

    public String conversationId() {
        return conversationId;
    }

    /** 意图；还没识别出来时为 null。 */
    public String intent() {
        return intent;
    }

    /** RAG 结果；不走 RAG 时为 null。 */
    public String ragResult() {
        return ragResult;
    }

    public int blockedChunks() {
        return blockedChunks;
    }

    /** Agent 步数；不走 Agent 时为 -1。 */
    public int agentSteps() {
        return agentSteps;
    }

    /** Agent 停止原因；不走 Agent 时为 null。 */
    public String agentStop() {
        return agentStop;
    }

    /** 从开始到现在过了多少纳秒。 */
    public long elapsedNanos() {
        return System.nanoTime() - startNanos;
    }
}
