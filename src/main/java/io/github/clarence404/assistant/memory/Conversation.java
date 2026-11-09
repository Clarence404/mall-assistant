package io.github.clarence404.assistant.memory;

import io.github.clarence404.assistant.intent.IntentResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.ai.chat.messages.Message;

/**
 * 正在进行的「一轮」对话的会话句柄：这一轮要带给模型的历史、回答完怎么写回去、用完释放（第 6 章引入）。
 *
 * <p>所在层：业务层。由 {@link ConversationMemory#open} 打开，{@code ChatService} 在一轮对话里使用：取 {@link #history()} 拼进提示词，
 * 用 {@link #carryOver} 沿用订单号，记下 {@link #condensed}，回答完 {@link #save}，最后 {@link #close()}（放开「这个会话正在回答」的标记）。
 * 用 try-with-resources 或 {@code doFinally} 保证一定会关。
 *
 * <p>三种状态：
 *
 * <ul>
 *   <li>{@link #none()}：请求没带会话 ID（或记忆总开关关着），和第 5 章一样单轮回答，不读不写，调试信息为 null
 *   <li>降级（{@link #degraded}）：带了会话 ID，但登录失效、商城不可用或记忆库挂了——这一轮照常回答，只是不带历史、也不写回去，原因写进调试信息。
 *       记忆是锦上添花，它挂了不该让对话也挂掉（和第 4 章「知识库不可用不影响对话」一个道理）
 *   <li>正常：读到了历史（可能为空），回答完写回去
 * </ul>
 *
 * <p>一个句柄只给一个请求用，不是线程安全的；流式回答里的回调虽然可能换线程，但都是一个接一个执行，不会并发。
 */
public final class Conversation implements AutoCloseable {

    /** 回答完把这一轮写回记忆。 */
    @FunctionalInterface
    interface Saver {
        void save(String userMessage, String assistantReply);
    }

    private static final Conversation NONE =
            new Conversation(null, null, HistoryWindow.Window.EMPTY, false, 0, null, null, null);

    private final String conversationId;
    private final String store;
    private final HistoryWindow.Window window;
    private final boolean expired;
    private final int entityTurns;
    private final String warning;
    private final Saver saver;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();
    private String condensedQuestion;
    private String carriedOrderNo;
    private String carriedSkuId;

    Conversation(
            String conversationId,
            String store,
            HistoryWindow.Window window,
            boolean expired,
            int entityTurns,
            String warning,
            Saver saver,
            Runnable release) {
        this.conversationId = conversationId;
        this.store = store;
        this.window = window;
        this.expired = expired;
        this.entityTurns = entityTurns;
        this.warning = warning;
        this.saver = saver;
        this.release = release;
    }

    /**
     * 没有会话：单轮回答。
     *
     * @return 共享的空句柄
     */
    public static Conversation none() {
        return NONE;
    }

    /**
     * 降级成单轮：带了会话 ID，但这一轮用不上记忆。
     *
     * @param conversationId 会话 ID
     * @param store 记忆库类型
     * @param warning 原因
     * @return 不带历史、不写回的句柄
     */
    static Conversation degraded(String conversationId, String store, String warning) {
        return new Conversation(conversationId, store, HistoryWindow.Window.EMPTY, false, 0, warning, null, null);
    }

    /** 是否带了会话 ID（无论用没用上记忆）；为 false 时调试信息为 null。 */
    public boolean present() {
        return conversationId != null;
    }

    /** 这一轮是否用上了记忆：读了历史，回答完会写回去。 */
    public boolean active() {
        return saver != null;
    }

    /** 裁剪后的历史窗口。 */
    public HistoryWindow.Window window() {
        return window;
    }

    /**
     * 这一轮要带给模型的历史消息：有摘要时先放一条带标签的摘要（{@link MemoryMessages#summaryForPrompt}），再接最近几轮的原话。
     * 放在系统消息之后、这一轮的用户消息之前（{@code ChatClient} 的 {@code messages(...)}）。
     *
     * @return 消息列表，按时间从早到晚；没有历史时为空列表
     */
    public List<Message> history() {
        if (window.summary() == null) {
            return window.messages();
        }
        List<Message> messages = new ArrayList<>(window.messages().size() + 1);
        messages.add(MemoryMessages.summaryForPrompt(window.summary()));
        messages.addAll(window.messages());
        return List.copyOf(messages);
    }

    /**
     * 按需从历史里沿用订单号 / SKU（{@link ConversationEntities#carryOver}），并记进调试信息。
     *
     * @param intent 意图识别的结果
     * @param message 用户这一句
     * @return 补上实体之后的意图
     */
    public IntentResult carryOver(IntentResult intent, String message) {
        if (window.messages().isEmpty()) {
            return intent;
        }
        ConversationEntities.Carried carried =
                ConversationEntities.carryOver(intent, message, window.messages(), entityTurns);
        carriedOrderNo = carried.orderNo();
        carriedSkuId = carried.skuId();
        return carried.intent();
    }

    /**
     * 这一轮拿去识别意图、检索的问题：有改写结果就用它，否则用原话。
     *
     * @param message 用户原话
     * @return 改写后的问题或原话
     */
    public String question(String message) {
        return condensedQuestion != null ? condensedQuestion : message;
    }

    /**
     * 记下追问改写的结果（调试信息里显示）。
     *
     * @param question 改写后的问题，可以为 null
     */
    public void condensed(String question) {
        this.condensedQuestion = question;
    }

    /**
     * 回答完把这一轮写回记忆：用户原话 + 助手最终的回答。没用上记忆时什么都不做。
     *
     * @param userMessage 用户原话
     * @param assistantReply 给用户的回答
     */
    public void save(String userMessage, String assistantReply) {
        if (saver != null) {
            saver.save(userMessage, assistantReply);
        }
    }

    /**
     * 调试信息。
     *
     * @return 没带会话 ID 时为 null
     */
    public MemoryTrace trace() {
        if (!present()) {
            return null;
        }
        return new MemoryTrace(
                conversationId,
                store,
                active(),
                window.keptTurns(),
                window.droppedTurns(),
                window.chars(),
                window.summary() != null,
                expired,
                condensedQuestion,
                carriedOrderNo,
                carriedSkuId,
                warning);
    }

    /** 放开「这个会话正在回答」的标记；重复调用无害。 */
    @Override
    public void close() {
        if (release != null && closed.compareAndSet(false, true)) {
            release.run();
        }
    }
}
