package io.github.clarence404.assistant.memory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 对话记忆的入口：打开一轮对话（读历史、裁剪）、回答完写回、查看与清空会话、定期清理过期会话（第 6 章引入）。
 *
 * <p>所在层：业务层。上游是 {@code ChatService}（对话）和 {@link ConversationController}（查看、清空）；下游是 Spring AI 的
 * {@link ChatMemory}（{@code MessageWindowChatMemory}：存储窗口）和它背后的 {@link ChatMemoryRepository}（内存或 JDBC，见 {@link MemoryConfig}），
 * 以及 {@link ConversationOwners}（归属与记忆键）、{@link HistoryWindow}（提示词窗口）、{@link ConversationSummarizer}（摘要记忆）。
 *
 * <p><b>先记住一件事：模型本身没有状态</b>。Ollama、OpenAI 的接口都是「一次请求一次回答」，上一次说了什么它不记得。所谓多轮对话，是我们自己把历史
 * 存下来，每次请求都把最近几轮一起发过去。所以「记忆」完全是应用层的事：存在哪、存什么、带多少、谁能看，都由这里决定。
 *
 * <p><b>会话 ID</b>：页面生成一个随机 UUID（存在浏览器 localStorage，点「新对话」换一个），每次提问放在请求体的 {@code conversationId} 字段里。
 * 服务端只校验格式，不负责分配：省掉一次「创建会话」的请求，刷新页面后还是同一个 ID，历史就接得上。ID 的随机部分有 122 位，猜不出来；
 * 就算泄露了，也读不到别人的会话（见 {@link ConversationOwners}）。
 *
 * <p><b>为什么没用 Spring AI 的 {@code MessageChatMemoryAdvisor}</b>（挂在 ChatClient 上自动读写记忆的顾问）：
 *
 * <ul>
 *   <li>它在调模型<b>之前</b>就把这一轮的用户消息写进记忆，模型调用失败、流式被取消时，记忆里会留下一句没有回答的话
 *   <li>它写进去的是「发给模型的那条」用户消息：第 3 章直接查询时拼了查询结果、第 5 章拼了参考资料，整段都会进记忆（见 {@link MemoryMessages} 为什么不存）
 *   <li>它只按条数裁剪（就是 {@code MessageWindowChatMemory}），没有字数预算；也做不了追问改写、指代沿用这些「检索之前」的事
 *   <li>意图识别和回答共用一个 ChatClient，挂上它意图识别的请求也会带上历史、写进记忆
 * </ul>
 *
 * 所以这里直接用 {@link ChatMemory} 的 {@code get} / {@code add} / {@code clear}，什么时候读、写什么、什么时候写都自己控制。
 *
 * <p><b>同一个会话的并发</b>：页面在回答完之前不让再发，但用户可能开两个标签页、或者连点两下。两个请求同时读到同样的历史、各自回答、先后写回，
 * 后写的会覆盖先写的（{@code MessageWindowChatMemory#add} 是「读出全部 → 追加 → 整体写回」，不是原子的），而且第二个回答没看到第一轮。
 * 这里用最简单的办法：一个会话同一时间只允许一个请求，后到的直接返回 409「上一条还在回答中」（{@link #busy}，记忆键的集合）。
 * 只在单个进程里有效；部署多个实例时要换成分布式锁（Redis {@code SET NX}）或者带版本号的乐观锁。
 *
 * <p><b>过期（TTL）</b>：最后一条消息之后超过 {@code ttl} 没再说话，下次打开时当作新会话、清掉旧历史；后台每隔 {@code sweep-interval} 也扫一遍，
 * 把过期的删掉（内存模式不清理会一直占着内存）。判断依据是消息的时间戳（{@link MemoryMessages#TIMESTAMP}）。
 */
@Service
public class ConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemory.class);

    private final ChatMemory chatMemory;
    private final ChatMemoryRepository repository;
    private final ConversationOwners owners;
    private final ConversationSummarizer summarizer;
    private final MemoryProperties properties;
    private final Clock clock;
    private final String store;

    /** 正在回答的会话（记忆键）：同一个会话同时只允许一个请求。 */
    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    /**
     * 创建。
     *
     * @param chatMemory Spring AI 的对话记忆（存储窗口），见 {@link MemoryConfig}
     * @param repository 它背后的存储（清理过期会话、摘要压缩时直接用）
     * @param owners 归属与记忆键
     * @param summarizer 摘要记忆
     * @param properties {@code assistant.memory.*}
     * @param clock 时钟（测试里换成可以拨动的，测过期）
     */
    public ConversationMemory(
            ChatMemory chatMemory,
            ChatMemoryRepository repository,
            ConversationOwners owners,
            ConversationSummarizer summarizer,
            MemoryProperties properties,
            Clock clock) {
        this.chatMemory = chatMemory;
        this.repository = repository;
        this.owners = owners;
        this.summarizer = summarizer;
        this.properties = properties;
        this.clock = clock;
        this.store = properties.store().name().toLowerCase(Locale.ROOT);
    }

    /**
     * 打开一轮对话：确认归属 → 占住这个会话 → 读历史（过期就清掉）→ 按提示词窗口裁剪。
     *
     * <p>降级而不是报错的情况（这一轮照常回答，只是不带历史、不写回，原因在调试信息里）：登录失效、商城不可用（确认不了归属）、记忆库读不了。
     *
     * @param conversationId 会话 ID（已校验格式），没带时为 null
     * @param mallToken 商城令牌，可以为 null
     * @return 会话句柄，用完要 {@link Conversation#close()}
     * @throws ConversationException 同一个会话的上一条还没回答完（CONVERSATION_BUSY）
     */
    public Conversation open(String conversationId, String mallToken) {
        if (conversationId == null || !properties.enabled()) {
            return Conversation.none();
        }
        ConversationOwner owner;
        try {
            owner = owners.resolve(mallToken);
        } catch (ConversationException e) {
            return Conversation.degraded(conversationId, store, e.failure().message() + "这一轮不带历史，也不记录。");
        }
        String key = owners.memoryKey(owner, conversationId);
        if (!busy.add(key)) {
            throw new ConversationException(ConversationException.Failure.CONVERSATION_BUSY, null);
        }
        try {
            List<Message> stored = chatMemory.get(key);
            boolean expired = isExpired(stored);
            if (expired) {
                log.info("会话 {} 空闲超过 {}，按新会话处理", conversationId, properties.ttl());
                chatMemory.clear(key);
                stored = List.of();
            }
            HistoryWindow.Window window =
                    HistoryWindow.trim(stored, properties.windowTurns(), properties.budgetChars());
            return new Conversation(
                    conversationId,
                    store,
                    window,
                    expired,
                    properties.entityTurns(),
                    null,
                    (user, assistant) -> save(key, user, assistant),
                    () -> busy.remove(key));
        } catch (RuntimeException e) {
            busy.remove(key);
            log.warn("读取会话记忆失败，这一轮按单轮回答：{}", e.toString());
            return Conversation.degraded(
                    conversationId,
                    store,
                    ConversationException.Failure.MEMORY_UNAVAILABLE.message() + "这一轮不带历史，也不记录。");
        }
    }

    /**
     * 把这一轮写回记忆：用户原话 + 助手的回答（去掉引用编号），同一个时间戳；开了摘要记忆时顺带压缩。
     *
     * <p>写失败只记日志：回答已经给用户了，不能因为记不下来就把整轮算失败。
     *
     * @param key 记忆键
     * @param userMessage 用户原话
     * @param assistantReply 给用户的回答
     */
    void save(String key, String userMessage, String assistantReply) {
        Instant now = clock.instant();
        try {
            chatMemory.add(
                    key, List.of(MemoryMessages.user(userMessage, now), MemoryMessages.assistant(assistantReply, now)));
            List<Message> compacted = summarizer.compact(chatMemory.get(key), now);
            if (compacted != null) {
                repository.saveAll(key, compacted);
            }
        } catch (RuntimeException e) {
            log.warn("写回会话记忆失败（这一轮的回答不受影响）：{}", e.toString());
        }
    }

    /**
     * 在一轮对话之外补记一轮（第 7 章：用户点了确认卡片上的按钮，执行结果要写回会话，下一轮 Agent 才知道「那单已经取消了」）。
     *
     * <p>和正常的一轮走同一套：确认归属 → 占住会话 → 写入 → 放开。会话正忙、归属确认不了、记忆库挂了都不抛，返回 false（结果已经给用户了，记不下来不该让
     * 确认失败）。
     *
     * @param conversationId 会话 ID，为 null 时什么都不做
     * @param mallToken 商城令牌，可以为 null
     * @param userMessage 这一轮「用户」那句（如「（点了确认）取消订单 …」）
     * @param assistantReply 这一轮助手那句（执行结果）
     * @return 写进去了为 true
     */
    public boolean record(String conversationId, String mallToken, String userMessage, String assistantReply) {
        if (conversationId == null || !properties.enabled()) {
            return false;
        }
        try (Conversation conversation = open(conversationId, mallToken)) {
            if (!conversation.active()) {
                return false;
            }
            conversation.save(userMessage, assistantReply);
            return true;
        } catch (ConversationException e) {
            log.info("补记会话 {} 失败：{}", conversationId, e.failure());
            return false;
        }
    }

    /**
     * 查看会话的全部消息（{@code GET /api/conversations/{id}/messages}，页面刷新后恢复、调试用）。
     *
     * @param conversationId 会话 ID
     * @param mallToken 商城令牌，可以为 null
     * @return 消息，按时间从早到晚
     * @throws ConversationException 会话不存在、已过期或不是你的（404）；登录失效（401）；商城或记忆库不可用（503）
     */
    public List<ConversationMessage> messages(String conversationId, String mallToken) {
        String key = owners.memoryKey(owners.resolve(mallToken), conversationId);
        List<Message> stored = read(key);
        if (stored.isEmpty() || isExpired(stored)) {
            throw new ConversationException(ConversationException.Failure.CONVERSATION_NOT_FOUND, null);
        }
        return stored.stream().map(ConversationMemory::view).toList();
    }

    /**
     * 清空会话（{@code DELETE /api/conversations/{id}}）。
     *
     * @param conversationId 会话 ID
     * @param mallToken 商城令牌，可以为 null
     * @throws ConversationException 会话不存在或不是你的（404）；正在回答（409）；登录失效（401）；商城或记忆库不可用（503）
     */
    public void clear(String conversationId, String mallToken) {
        String key = owners.memoryKey(owners.resolve(mallToken), conversationId);
        if (!busy.add(key)) {
            throw new ConversationException(ConversationException.Failure.CONVERSATION_BUSY, null);
        }
        try {
            if (read(key).isEmpty()) {
                throw new ConversationException(ConversationException.Failure.CONVERSATION_NOT_FOUND, null);
            }
            chatMemory.clear(key);
        } catch (ConversationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ConversationException(ConversationException.Failure.MEMORY_UNAVAILABLE, e);
        } finally {
            busy.remove(key);
        }
    }

    /**
     * 后台清理过期会话，并清掉过期的归属缓存。
     *
     * <p>做法是通用的：列出全部会话、逐个读出来看最后一条消息的时间。会话多了以后，JDBC 模式应该改成一条
     * {@code DELETE ... WHERE "timestamp" < ?} 的 SQL（见 docs/ch06.md 的思考题）。
     *
     * @return 删掉了几个会话
     */
    @Scheduled(
            initialDelayString = "${assistant.memory.sweep-interval:10m}",
            fixedDelayString = "${assistant.memory.sweep-interval:10m}")
    public int sweep() {
        owners.evictExpired();
        if (!properties.enabled()) {
            return 0;
        }
        int removed = 0;
        try {
            for (String key : repository.findConversationIds()) {
                if (!busy.contains(key) && isExpired(repository.findByConversationId(key))) {
                    repository.deleteByConversationId(key);
                    removed++;
                }
            }
        } catch (RuntimeException e) {
            log.info("清理过期会话失败（记忆库不可用？），下次再试：{}", e.toString());
        }
        if (removed > 0) {
            log.info("清理了 {} 个过期会话", removed);
        }
        return removed;
    }

    /**
     * 会话是否过期：最后一条消息的时间 + ttl 早于现在。没有时间戳的消息（别的程序写的）不算过期。
     *
     * @param stored 会话里的消息
     * @return 过期时为 true
     */
    boolean isExpired(List<Message> stored) {
        Instant last = MemoryMessages.lastActivity(stored);
        return last != null && last.plus(properties.ttl()).isBefore(clock.instant());
    }

    /** 读会话；记忆库不可用时转成 503。 */
    private List<Message> read(String key) {
        try {
            return chatMemory.get(key);
        } catch (RuntimeException e) {
            throw new ConversationException(ConversationException.Failure.MEMORY_UNAVAILABLE, e);
        }
    }

    /** 记忆里的消息 → 接口返回的形式。 */
    private static ConversationMessage view(Message message) {
        String role = switch (message.getMessageType()) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "summary";
            case TOOL -> "tool";
        };
        return new ConversationMessage(role, MemoryMessages.text(message), MemoryMessages.timestamp(message));
    }
}
