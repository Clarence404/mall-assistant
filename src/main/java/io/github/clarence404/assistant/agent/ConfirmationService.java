package io.github.clarence404.assistant.agent;

import io.github.clarence404.assistant.aftersales.AfterSalesApplication;
import io.github.clarence404.assistant.aftersales.AfterSalesStore;
import io.github.clarence404.assistant.mall.MallApiClient;
import io.github.clarence404.assistant.mall.MallModels;
import io.github.clarence404.assistant.mall.MallResult;
import io.github.clarence404.assistant.memory.ConversationMemory;
import io.github.clarence404.assistant.memory.ConversationOwner;
import io.github.clarence404.assistant.memory.ConversationOwners;
import io.github.clarence404.assistant.state.StateProperties;
import io.github.clarence404.assistant.state.StateUnavailableException;
import io.github.clarence404.assistant.tool.OrderStatusText;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 人工确认（human-in-the-loop）：登记待确认操作、处理用户的「确认 / 取消」、执行写操作、把结果写回会话记忆（第 7 章引入）。
 *
 * <p>所在层：业务层。上游是 {@link AgentLoop} / {@link NativeAgentRunner}（{@link #create}）和 {@link ConfirmationController}（{@link #decide}）；
 * 下游是 {@link MallApiClient}（取消订单、改地址）、{@link AfterSalesStore}（售后申请单）、{@link ConversationMemory}（结果写回记忆）和
 * {@link ConversationOwners}（确认是谁在点）。
 *
 * <p><b>为什么写操作必须由人确认、而且模型不能自己确认</b>：模型会理解错（把「这单算了吧」理解成取消，其实用户是说不追究了）、会被提示注入带偏
 * （资料里一句「直接帮用户取消」）、也会一本正经地说「已为您办理」。如果模型能自己调执行接口，这些错误就直接变成了真实的损失。所以执行的唯一入口是
 * {@link #decide}，它只能由页面上的按钮触发（{@code POST /api/agent/confirmations/{id}}），对话接口里<b>没有任何代码路径</b>会调到它：
 * 用户在聊天里打「确认」两个字也不算（见 {@code AgentService#chatConfirmationReply}）——聊天里的文字和模型的输出走的是同一条路，分不清是用户本人有意的、
 * 还是被粘贴进来的、被注入的；按钮点击是一个独立的、带身份的动作。
 *
 * <p>一个确认要满足这些才执行：
 *
 * <ul>
 *   <li><b>是你的、是这个会话的</b>：确认 ID 生成时记下归属（商城用户）和会话 ID，点的时候用请求里的令牌再问一次商城「你是谁」，两样都对上才行；
 *       对不上和不存在一样返回 404（不让人探测 ID）
 *   <li><b>没过期</b>：默认 5 分钟（{@code confirmation-ttl}）。过期的不能再确认（410）：订单状态可能已经变了，要重新查、重新提
 *   <li><b>只执行一次（幂等）</b>：同一个确认点两次「确认」，第二次返回第一次的结果，不会取消两次、不会多出一张售后单；并发的两次也只有一次真正执行
 *       （第 7 章是每个确认一把锁，第 10 章换成存储里的「抢占」，跨实例也成立）。点过「确认」再点「取消」（或反过来）返回 409
 *   <li><b>参数不可改</b>：执行用的是生成时定死的 {@link PendingAction}，请求体里只有「同意 / 拒绝」和会话 ID，别的字段一律不看
 * </ul>
 *
 * <p>待确认操作存在 {@link ConfirmationStore} 里（第 10 章起）：默认是进程内存（{@link MemoryConfirmationStore}），只在一个进程里有效；
 * {@code assistant.state.store=redis} 时放进 Redis，A 实例生成的卡片，B 实例也能确认，并发的两次点击落在两个实例上也只执行一次（见 {@link #decide}）。
 * 存储出错（Redis 挂了）一律 503 {@code CONFIRMATION_UNAVAILABLE}：写操作宁可这次办不了，也不能「出错就放行」。内存实现由后台定期清掉过期很久的，
 * Redis 的键自己会过期。
 *
 * <p><b>第 8 章：MCP 客户端提出的写操作也登记在这里</b>（{@link #createForMcp}）。它们没有会话（会话 ID 为 null），确认的唯一方式仍然是用户在智能客服页面上点按钮：
 * MCP 工具只拿到确认 ID 和确认链接，页面打开链接时用 {@link #view} 取卡片内容，点按钮走的还是 {@link #decide}——执行只有这一个入口，来源不同、规则相同。
 */
@Service
public class ConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationService.class);
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("Asia/Shanghai"));

    /** 过期之后再留多久（这段时间里重复点确认还能拿到原来的结果；再之后就是 404）。 */
    static final Duration RETENTION = Duration.ofHours(1);

    /** 还没确认时的提示（第 8 章，MCP 工具查状态时给对面的模型看）。 */
    static final String PENDING_MESSAGE = "还没有执行：等用户本人在智能客服页面登录同一个账号后点「确认」，MCP 客户端不能代替用户确认。";

    /** 等别人（可能在另一个实例上）执行完时，隔多久看一次结果。 */
    static final Duration POLL_INTERVAL = Duration.ofMillis(20);

    /**
     * 执行时出了意料之外的异常（不是商城明确拒绝，而是代码或下游抛了没人接的异常）时给用户的话：这时候到底办没办成，我们不知道（审查修复）。
     *
     * @param orderNo 订单号
     * @return 固定话术
     */
    static String unknownResult(String orderNo) {
        return "执行时出了意外，结果未知：可能已经办了，也可能没有。请到「我的订单」里核对订单 " + orderNo + " 的状态；确实没办成的话，再说一次要办的事。";
    }

    private final MallApiClient mallApi;
    private final AfterSalesStore afterSales;
    private final ConversationMemory memory;
    private final ConversationOwners owners;
    private final AgentProperties properties;
    private final Clock clock;
    private final ConfirmationStore store;
    private final Duration waitForOther;

    /**
     * 创建：确认存在进程内存里（第 7 章的样子；测试里手工组装时用）。
     *
     * @param mallApi 商城客户端（执行取消、改地址，执行售后前再查一次订单）
     * @param afterSales 售后申请单存储
     * @param memory 会话记忆（结果写回去）
     * @param owners 归属（确认是谁在点）
     * @param properties {@code assistant.agent.*}（有效期）
     * @param clock 时钟（测试里拨动它测过期）
     */
    public ConfirmationService(
            MallApiClient mallApi,
            AfterSalesStore afterSales,
            ConversationMemory memory,
            ConversationOwners owners,
            AgentProperties properties,
            Clock clock) {
        this(
                mallApi,
                afterSales,
                memory,
                owners,
                properties,
                clock,
                new MemoryConfirmationStore(),
                Duration.ofSeconds(10));
    }

    /**
     * 创建（Spring 注入，第 10 章）。
     *
     * @param mallApi 商城客户端
     * @param afterSales 售后申请单存储
     * @param memory 会话记忆
     * @param owners 归属
     * @param properties {@code assistant.agent.*}（有效期）
     * @param clock 时钟
     * @param store 确认存在哪（内存或 Redis，按 {@code assistant.state.store}）
     * @param state {@code assistant.state.*}（等别人执行完最多等多久）
     */
    @Autowired
    public ConfirmationService(
            MallApiClient mallApi,
            AfterSalesStore afterSales,
            ConversationMemory memory,
            ConversationOwners owners,
            AgentProperties properties,
            Clock clock,
            ConfirmationStore store,
            StateProperties state) {
        this(mallApi, afterSales, memory, owners, properties, clock, store, state.confirmationWait());
    }

    /**
     * 创建（测试用：指定存储和等待时长）。
     *
     * @param mallApi 商城客户端
     * @param afterSales 售后申请单存储
     * @param memory 会话记忆
     * @param owners 归属
     * @param properties {@code assistant.agent.*}
     * @param clock 时钟
     * @param store 确认存在哪
     * @param waitForOther 同一个确认正被别人执行时最多等多久
     */
    public ConfirmationService(
            MallApiClient mallApi,
            AfterSalesStore afterSales,
            ConversationMemory memory,
            ConversationOwners owners,
            AgentProperties properties,
            Clock clock,
            ConfirmationStore store,
            Duration waitForOther) {
        this.mallApi = mallApi;
        this.afterSales = afterSales;
        this.memory = memory;
        this.owners = owners;
        this.properties = properties;
        this.clock = clock;
        this.store = store;
        this.waitForOther = waitForOther;
    }

    /**
     * 登记一个待确认操作：配上随机的确认 ID 和过期时间，记下归属和会话。
     *
     * @param action 写操作（已经过 {@link WriteActionGuard} 核对）
     * @param owner 谁提的（商城用户）
     * @param conversationId 哪个会话，可以为 null（单轮）
     * @return 推给页面的待确认操作
     * @throws ConfirmationException 存储不可用（CONFIRMATION_UNAVAILABLE，第 10 章）
     */
    public PendingConfirmation create(PendingAction action, ConversationOwner owner, String conversationId) {
        return create(action, owner, conversationId, StoredConfirmation.Origin.CHAT);
    }

    private PendingConfirmation create(
            PendingAction action, ConversationOwner owner, String conversationId, StoredConfirmation.Origin origin) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.confirmationTtl());
        String id = UUID.randomUUID().toString();
        PendingConfirmation view = new PendingConfirmation(
                id,
                action.tool().toolName(),
                action.tool().title(),
                action.orderNo(),
                action.params(),
                action.summary(),
                action.impact(),
                expiresAt,
                properties.confirmationTtl().toSeconds());
        try {
            store.save(
                    new StoredConfirmation(id, action, owner.key(), conversationId, expiresAt, view, origin, null),
                    keep(now, expiresAt));
        } catch (StateUnavailableException e) {
            throw new ConfirmationException(ConfirmationException.Failure.CONFIRMATION_UNAVAILABLE);
        }
        log.info("待确认操作 {}（{}）：{}（{}），{} 前有效", id, origin, action.summary(), owner.key(), expiresAt);
        return view;
    }

    /**
     * 登记一个由 MCP 客户端提出的待确认操作（第 8 章）：没有会话，只绑定到商城用户；确认仍然只能在页面上点按钮（{@link #decide}，会话 ID 为空）。
     *
     * <p>为什么单独一个方法、记下来源：页面打开确认链接时（{@link #view}）只认 MCP 来的确认——聊天里生成的确认绑定着会话，应该在那个会话的卡片上点；
     * 聊天里说「确认」时的提醒（{@link #pendingFor}）也只看聊天来的，不把 MCP 那边的操作混进来。
     *
     * @param action 写操作（已经过 {@link WriteActionGuard} 核对）
     * @param owner 谁提的（商城用户）
     * @return 待确认操作
     * @throws ConfirmationException 存储不可用（CONFIRMATION_UNAVAILABLE，第 10 章）
     */
    public PendingConfirmation createForMcp(PendingAction action, ConversationOwner owner) {
        return create(action, owner, null, StoredConfirmation.Origin.MCP);
    }

    /**
     * 这个用户在这个会话里有没有还没处理、没过期的待确认操作（聊天里说「确认」时提醒他去点按钮）。
     *
     * @param owner 归属
     * @param conversationId 会话 ID，可以为 null
     * @return 最近的一个；没有时为空
     */
    public Optional<PendingConfirmation> pendingFor(ConversationOwner owner, String conversationId) {
        Instant now = clock.instant();
        try {
            return store.findByOwner(owner.key()).stream()
                    .filter(e -> e.origin() == StoredConfirmation.Origin.CHAT)
                    .filter(e -> Objects.equals(e.conversationId(), conversationId))
                    .filter(e -> e.outcome() == null && e.expiresAt().isAfter(now))
                    .max(Comparator.comparing(StoredConfirmation::expiresAt))
                    .map(StoredConfirmation::view);
        } catch (StateUnavailableException e) {
            return Optional.empty(); // 只是一句提醒，查不到就不提醒
        }
    }

    /**
     * 页面打开确认链接（{@code /?confirm=<ID>}）时取卡片：只有 MCP 来的、是你的确认才给看（第 8 章）。
     *
     * @param id 确认 ID
     * @param mallToken 页面上登录的商城令牌，可以为 null（匿名没有确认，结果是 404）
     * @return 卡片内容与状态
     * @throws ConfirmationException 不存在、不是你的、不是 MCP 来的（都是 404）
     */
    public ConfirmationSnapshot view(String id, String mallToken) {
        return snapshot(id, owners.resolve(mallToken));
    }

    /**
     * 一个 MCP 来的待确认操作此刻的样子（ch08-7）：页面画卡片、MCP 工具 {@code getConfirmation} 查结果都用它。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>找不到这个 ID、归属对不上、或者不是 MCP 来的（{@link Origin#MCP}）：抛 {@link ConfirmationException.Failure#CONFIRMATION_NOT_FOUND}
     *       ——和 {@link #decide} 一样，不是你的和不存在不区分，不让人探测 ID；聊天里生成的确认也不能借这个接口看
     *   <li>已经有结果：状态按结果（EXECUTED / FAILED / REJECTED），消息是结果的固定话术
     *   <li>没结果、已过期（现在不早于 expiresAt）：EXPIRED，消息说明「过期了，没有执行」
     *   <li>没结果、没过期：PENDING，消息提示「等用户在智能客服页面点确认，还没有执行」
     *   <li>只读：不加锁、不改任何东西（看的那一刻和点的那一刻之间状态可能变，以 {@link #decide} 为准）
     * </ul>
     *
     * @param id 确认 ID
     * @param owner 谁在看
     * @return 卡片内容与状态
     * @throws ConfirmationException 不存在、不是你的、不是 MCP 来的（都是 404）
     */
    public ConfirmationSnapshot snapshot(String id, ConversationOwner owner) {
        StoredConfirmation entry = find(id);
        if (entry == null
                || entry.origin() != StoredConfirmation.Origin.MCP
                || !entry.ownerKey().equals(owner.key())) {
            throw new ConfirmationException(ConfirmationException.Failure.CONFIRMATION_NOT_FOUND);
        }
        ConfirmationOutcome outcome = entry.outcome();
        if (outcome != null) {
            return new ConfirmationSnapshot(
                    entry.view(),
                    ConfirmationSnapshot.State.valueOf(outcome.status().name()),
                    outcome.message());
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            return new ConfirmationSnapshot(
                    entry.view(), ConfirmationSnapshot.State.EXPIRED, "这个确认已经过期，没有执行；要办的话请重新提出。");
        }
        return new ConfirmationSnapshot(entry.view(), ConfirmationSnapshot.State.PENDING, PENDING_MESSAGE);
    }

    /**
     * 用户点了「确认」或「取消」（ch07-5）。
     *
     * <p>要求（测试按这个写）：
     *
     * <ol>
     *   <li>{@code owners.resolve(mallToken)} 确认是谁（令牌无效抛第 6 章的 {@code ConversationException}，接口返回 401）
     *   <li>找不到这个 ID、归属对不上、会话 ID 对不上（{@link Objects#equals}，单轮时两边都是 null）：抛 {@link ConfirmationException.Failure#CONFIRMATION_NOT_FOUND}
     *   <li>判断（第 7 章写在锁里；第 10 章锁换成了下面第 3 步的「抢占」，跨实例也成立）：
     *       <ul>
     *         <li>已经有结果：决定相同就原样返回那个结果（幂等，不再执行）；决定不同抛 {@link ConfirmationException.Failure#CONFIRMATION_ALREADY_DECIDED}
     *         <li>过期了（现在不早于 expiresAt）：抛 {@link ConfirmationException.Failure#CONFIRMATION_EXPIRED}，什么都不做
     *       </ul>
     *   <li>{@link ConfirmationStore#claim} 抢占：没抢到、别人的决定和我不同 → 409；相同 → 等别人的结果（{@link #awaitOutcome}），等到就返回它，
     *       等不到（别人卡住或挂了）→ 409 {@link ConfirmationException.Failure#CONFIRMATION_IN_PROGRESS}
     *   <li>抢到了：拒绝 → 结果为 REJECTED，消息「好的，没有执行：……」；同意 → {@link #execute}
     *   <li>记下结果（{@link ConfirmationStore#complete}），写回会话记忆（{@link #remember}），返回结果
     * </ol>
     *
     * <p>存储出错（Redis 挂了）在任何一步都是 503 {@link ConfirmationException.Failure#CONFIRMATION_UNAVAILABLE}，不执行。
     *
     * @param id 确认 ID
     * @param decision 用户的决定
     * @param conversationId 页面当前的会话 ID，可以为 null
     * @param mallToken 商城令牌，可以为 null（匿名没有待确认操作，结果是 404）
     * @return 结果
     * @throws ConfirmationException 不存在 / 不是你的（404）、过期（410）、决定冲突或正在处理（409）、存储不可用（503）
     */
    public ConfirmationOutcome decide(
            String id, ConfirmationOutcome.Decision decision, String conversationId, String mallToken) {
        ConversationOwner owner = owners.resolve(mallToken);
        StoredConfirmation entry = find(id);
        if (entry == null
                || !entry.ownerKey().equals(owner.key())
                || !Objects.equals(entry.conversationId(), conversationId)) {
            throw new ConfirmationException(ConfirmationException.Failure.CONFIRMATION_NOT_FOUND);
        }
        if (entry.outcome() != null) {
            return repeated(entry.outcome(), decision);
        }
        Instant now = clock.instant();
        if (!now.isBefore(entry.expiresAt())) {
            throw new ConfirmationException(ConfirmationException.Failure.CONFIRMATION_EXPIRED);
        }
        Duration keep = keep(now, entry.expiresAt());
        Optional<ConfirmationOutcome.Decision> taken;
        try {
            taken = store.claim(id, decision, keep);
        } catch (StateUnavailableException e) {
            throw new ConfirmationException(ConfirmationException.Failure.CONFIRMATION_UNAVAILABLE);
        }
        if (taken.isPresent()) {
            if (taken.get() != decision) {
                throw new ConfirmationException(ConfirmationException.Failure.CONFIRMATION_ALREADY_DECIDED);
            }
            log.info("确认 {} 正在由另一个请求处理（{}），等它的结果", id, decision);
            return repeated(awaitOutcome(id), decision);
        }
        ConfirmationOutcome outcome = decision == ConfirmationOutcome.Decision.REJECT
                ? new ConfirmationOutcome(
                        id,
                        decision,
                        ConfirmationOutcome.Status.REJECTED,
                        "好的，没有执行：" + entry.action().summary() + "。订单没有任何变化。",
                        null,
                        now)
                : executeSafely(entry, owner, mallToken, now);
        try {
            store.complete(id, outcome, keep);
        } catch (StateUnavailableException e) {
            // 已经执行了，只是结果没存下：照样告诉这个用户结果；别人再点会停在「正在处理」（409），不会再执行一次
            log.error("确认 {} 已执行（{}），但结果没能存进共享状态：{}", id, outcome.status(), e.toString());
        }
        log.info("确认 {}：{} → {}", id, decision, outcome.status());
        try {
            remember(entry, outcome, mallToken);
        } catch (RuntimeException e) {
            // 结果已经定了、也存好了：写回会话记忆失败（比如记忆库连不上）不能让这次确认变成 500
            log.warn("确认 {} 的结果写回会话记忆时出错：{}", id, e.toString());
        }
        return outcome;
    }

    /**
     * 执行写操作，任何意料之外的异常都落成一个 FAILED 的最终结果（审查修复）。
     *
     * <p>为什么要包这一层：{@link #decide} 先抢占（{@link ConfirmationStore#claim}）再执行、最后存结果。抢占是不释放的——如果执行这一步抛了
     * 异常（售后申请单库连不上、参数里的类型不认识……）而没人接，结果就永远存不进去：这张确认单卡在「正在处理」，用户再点只会等 10 秒然后 409，
     * 直到确认过期。包起来之后，异常变成一个明确的终态，话术如实告诉用户「结果未知，请去核对」，再点返回的也是它。
     *
     * <p>为什么是 FAILED 而不是放开抢占让用户重试：异常抛出时写操作可能已经发给商城了（比如响应解析时出错），放开重试就可能执行两次——
     * 「最多执行一次」比「一定成功」重要。
     *
     * @param entry 待确认操作
     * @param owner 确认的人
     * @param token 确认的人的商城令牌
     * @param now 现在
     * @return 结果，从不抛异常
     */
    private ConfirmationOutcome executeSafely(
            StoredConfirmation entry, ConversationOwner owner, String token, Instant now) {
        try {
            return execute(entry, owner, token, now);
        } catch (RuntimeException e) {
            log.error("确认 {} 执行时抛出了没有预料到的异常，按「结果未知」记为失败", entry.id(), e);
            return outcome(
                    entry,
                    ConfirmationOutcome.Status.FAILED,
                    unknownResult(entry.action().orderNo()),
                    null,
                    now);
        }
    }

    /** 已经有结果：决定相同原样返回（幂等），不同是 409。 */
    private static ConfirmationOutcome repeated(ConfirmationOutcome outcome, ConfirmationOutcome.Decision decision) {
        if (outcome.decision() != decision) {
            throw new ConfirmationException(ConfirmationException.Failure.CONFIRMATION_ALREADY_DECIDED);
        }
        log.info("确认 {} 重复提交（{}），返回原来的结果", outcome.id(), decision);
        return outcome;
    }

    /**
     * 等另一个请求（可能在另一个实例上）把结果写进存储：每 {@link #POLL_INTERVAL} 看一次，最多等 {@code assistant.state.confirmation-wait}。
     *
     * <p>为什么轮询而不是通知：两个实例之间没有共享的内存可以 wait / notify；Redis 的发布订阅可以做，但消息可能丢、还要多一条连接，
     * 而这里是「两次点击撞在一起」这种罕见情况，几十毫秒看一次足够。
     *
     * @param id 确认 ID
     * @return 结果
     * @throws ConfirmationException 等不到（CONFIRMATION_IN_PROGRESS）或存储不可用
     */
    private ConfirmationOutcome awaitOutcome(String id) {
        long deadline = System.nanoTime() + waitForOther.toNanos();
        while (true) {
            StoredConfirmation latest = find(id);
            if (latest != null && latest.outcome() != null) {
                return latest.outcome();
            }
            if (System.nanoTime() >= deadline) {
                throw new ConfirmationException(ConfirmationException.Failure.CONFIRMATION_IN_PROGRESS);
            }
            LockSupport.parkNanos(POLL_INTERVAL.toNanos());
        }
    }

    /** 按 ID 取；存储出错转成 503。 */
    private StoredConfirmation find(String id) {
        try {
            return store.find(id).orElse(null);
        } catch (StateUnavailableException e) {
            throw new ConfirmationException(ConfirmationException.Failure.CONFIRMATION_UNAVAILABLE);
        }
    }

    /** 存多久：到过期时间再加 {@link #RETENTION}（这段时间里重复点确认还能拿到原来的结果）。 */
    private static Duration keep(Instant now, Instant expiresAt) {
        Duration keep = Duration.between(now, expiresAt.plus(RETENTION));
        return keep.isNegative() || keep.isZero() ? Duration.ofSeconds(1) : keep;
    }

    /**
     * 真正执行写操作（ch07-6）。只从 {@link #decide} 进来，而 {@link #decide} 只由确认接口调用。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>cancelOrder：{@code mallApi.cancelOrder(令牌, 订单号)}，成功 → EXECUTED「已取消订单 X，订单现在是「已取消」……」
     *   <li>changeAddress：{@code mallApi.changeAddress(令牌, 订单号, new AddressPatch(null, null, 新地址))}（收货人、电话不改，传 null），成功 → EXECUTED
     *   <li>applyAfterSales：先 {@code mallApi.getOrder} 再查一次订单（状态可能在这几分钟里变了），状态不再允许就 FAILED；已经有待审核的申请就 FAILED；
     *       否则 {@code afterSales.create(...)}（申请单号 {@link #newApplicationId}、确认 ID 作为 confirmationId、状态待人工审核），EXECUTED，
     *       结果里带申请单号
     *   <li>商城失败一律 FAILED，消息用 {@link #failure} 翻译（409 时照抄商城的 detail，如「订单 X 已支付，不能取消」）；不重试——写操作重试不安全，
     *       超时的那次可能已经执行成功了
     *   <li>结果的消息是代码写的固定话术，不经过模型：执行结果必须和商城返回的一致，不能让模型再「转述」一遍
     * </ul>
     *
     * @param entry 待确认操作
     * @param owner 谁在点
     * @param token 商城令牌（用点确认那一刻的令牌执行，不保存令牌）
     * @param now 现在
     * @return 结果
     */
    ConfirmationOutcome execute(StoredConfirmation entry, ConversationOwner owner, String token, Instant now) {
        PendingAction action = entry.action();
        String orderNo = action.orderNo();
        return switch (action.tool()) {
            case CANCEL_ORDER -> {
                MallResult<MallModels.Order> result = mallApi.cancelOrder(token, orderNo);
                yield result.isOk()
                        ? outcome(
                                entry,
                                ConfirmationOutcome.Status.EXECUTED,
                                "已取消订单 " + orderNo + "，订单现在是「" + statusText(result.body()) + "」。这笔没有付款，不涉及退款。",
                                null,
                                now)
                        : outcome(entry, ConfirmationOutcome.Status.FAILED, failure(orderNo, result), null, now);
            }
            case CHANGE_ADDRESS -> {
                Map<String, String> p = action.params();
                MallResult<MallModels.Order> result = mallApi.changeAddress(
                        token, orderNo, new MallModels.AddressPatch(null, null, p.get("address")));
                yield result.isOk()
                        ? outcome(
                                entry,
                                ConfirmationOutcome.Status.EXECUTED,
                                "已把订单 " + orderNo + " 的收货地址改为：" + p.get("address") + "。",
                                null,
                                now)
                        : outcome(entry, ConfirmationOutcome.Status.FAILED, failure(orderNo, result), null, now);
            }
            case APPLY_AFTER_SALES -> applyAfterSales(entry, owner, token, now);
            default -> throw new IllegalStateException("不是写操作：" + action.tool());
        };
    }

    /** 提交售后申请：执行前再查一次订单，再记一张待人工审核的申请单。 */
    private ConfirmationOutcome applyAfterSales(
            StoredConfirmation entry, ConversationOwner owner, String token, Instant now) {
        PendingAction action = entry.action();
        String orderNo = action.orderNo();
        MallResult<MallModels.Order> order = mallApi.getOrder(token, orderNo);
        if (!order.isOk()) {
            return outcome(entry, ConfirmationOutcome.Status.FAILED, failure(orderNo, order), null, now);
        }
        if (!OrderRules.allowed(order.body().status()).contains(AgentTool.APPLY_AFTER_SALES)) {
            return outcome(
                    entry,
                    ConfirmationOutcome.Status.FAILED,
                    "订单 " + orderNo + " 现在是「" + statusText(order.body()) + "」，不能再提交售后申请，没有执行。",
                    null,
                    now);
        }
        Optional<AfterSalesApplication> open = afterSales.findOpen(owner.key(), orderNo);
        if (open.isPresent()) {
            return outcome(
                    entry,
                    ConfirmationOutcome.Status.FAILED,
                    "订单 " + orderNo + " 已经有待审核的售后申请 " + open.get().id() + "，没有重复提交。",
                    null,
                    now);
        }
        AfterSalesApplication.Type type =
                AfterSalesApplication.Type.valueOf(action.params().get("type"));
        AfterSalesApplication saved;
        try {
            saved = afterSales.create(new AfterSalesApplication(
                    newApplicationId(now),
                    owner.key(),
                    orderNo,
                    type,
                    action.params().get("reason"),
                    AfterSalesApplication.Status.PENDING_REVIEW,
                    entry.id(),
                    now));
        } catch (RuntimeException e) {
            log.warn("售后申请单保存失败：{}", e.toString());
            return outcome(entry, ConfirmationOutcome.Status.FAILED, "售后申请单暂时存不进去，没有提交成功，请稍后再说一次。", null, now);
        }
        if (!entry.id().equals(saved.confirmationId())) {
            // 审查修复：存储返回的是别的确认单建的那张——上面 findOpen 查的时候还没有，存的时候已经有了（同一笔订单的两张确认单被同时确认）。
            // 「一笔订单只有一张待审核的申请」由存储的原子操作保证（见 AfterSalesStore#create），这里如实告诉用户没有重复提交
            return outcome(
                    entry,
                    ConfirmationOutcome.Status.FAILED,
                    "订单 " + orderNo + " 已经有待审核的售后申请 " + saved.id() + "，没有重复提交。",
                    null,
                    now);
        }
        return outcome(
                entry,
                ConfirmationOutcome.Status.EXECUTED,
                "已提交售后申请 " + saved.id() + "（" + type.text() + "），状态："
                        + saved.status().text() + "。客服会在 24 小时内审核，审核通过前不会退款，也不会改动订单。",
                saved.id(),
                now);
    }

    /**
     * 把这一次确认作为一轮对话写回会话记忆（ch07-6 的后半）：用户那句是「（点了确认）+ 说明」或「（点了取消）+ 说明」，助手那句是结果。
     *
     * <p>为什么要写：下一轮 Agent 要知道「刚才那单已经取消了」，否则用户问「取消了吗」它只能再查一遍、甚至再提一次。为什么写成一轮
     * （而不是只追加一条助手消息）：第 6 章的窗口按「轮」（用户一句 + 助手一句）裁剪，单独一条助手消息会被当成没头没尾的话。
     * 单轮对话（没有会话 ID）不写；写失败只记日志，不影响结果。
     */
    private void remember(StoredConfirmation entry, ConfirmationOutcome outcome, String mallToken) {
        if (entry.conversationId() == null) {
            return;
        }
        String user = (outcome.decision() == ConfirmationOutcome.Decision.APPROVE ? "（点了确认）" : "（点了取消）")
                + entry.action().summary();
        if (!memory.record(entry.conversationId(), mallToken, user, outcome.message())) {
            log.info("确认 {} 的结果没能写回会话 {} 的记忆", entry.id(), entry.conversationId());
        }
    }

    /** 后台清理：过期超过 {@link #RETENTION} 的确认删掉（内存实现；Redis 的键自己会过期）。 */
    @Scheduled(fixedDelayString = "${assistant.agent.confirmation-sweep-interval:10m}")
    public void sweep() {
        store.sweep(clock.instant().minus(RETENTION));
    }

    /** 申请单号：AS + 日期 + 8 位随机十六进制，如 AS20261109-3F2A9C1B。 */
    static String newApplicationId(Instant now) {
        byte[] random = new byte[4];
        new java.security.SecureRandom().nextBytes(random);
        return "AS" + DAY.format(now) + "-" + HexFormat.of().withUpperCase().formatHex(random);
    }

    private static ConfirmationOutcome outcome(
            StoredConfirmation entry,
            ConfirmationOutcome.Status status,
            String message,
            String afterSalesId,
            Instant now) {
        return new ConfirmationOutcome(
                entry.id(), ConfirmationOutcome.Decision.APPROVE, status, message, afterSalesId, now);
    }

    private static String statusText(MallModels.Order order) {
        String text = OrderStatusText.describe(order.status());
        int paren = text.indexOf('（');
        return paren < 0 ? text : text.substring(0, paren);
    }

    /** 商城失败 → 给用户看的结果说明。 */
    static String failure(String orderNo, MallResult<?> result) {
        return switch (result.failure()) {
            case REJECTED -> "商城没有执行：" + (result.detail() != null ? result.detail() : "订单状态已经变了，不能办理") + "。";
            case UNAUTHORIZED -> "商城登录已失效，没有执行。请重新登录后再说一次。";
            case NOT_FOUND -> "商城里找不到订单 " + orderNo + "（或者不是你的订单），没有执行。";
            case UNAVAILABLE -> "商城暂时不可用，这次没有执行成功。为保险起见，请到「我的订单」里看一下这笔订单的状态。";
        };
    }
}
