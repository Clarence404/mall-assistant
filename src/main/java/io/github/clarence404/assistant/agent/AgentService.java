package io.github.clarence404.assistant.agent;

import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.intent.Intent;
import io.github.clarence404.assistant.intent.IntentResult;
import io.github.clarence404.assistant.memory.ConversationException;
import io.github.clarence404.assistant.memory.ConversationOwner;
import io.github.clarence404.assistant.memory.ConversationOwners;
import io.github.clarence404.assistant.tool.ToolProperties;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

/**
 * 售后 Agent 的入口：哪些话交给 Agent、怎么跑（方案 A / B）、没登录怎么提示、聊天里说「确认」怎么办（第 7 章引入）。
 *
 * <p>所在层：业务层。上游是 {@code ChatService}（意图识别之后先问 {@link #handles}）；下游是 {@link AgentLoop}（方案 B，默认）或
 * {@link NativeAgentRunner}（方案 A），以及 {@link ConversationOwners}（谁在办）和 {@link ConfirmationService}（有没有待确认操作）。
 *
 * <p><b>路由</b>：「我要退这一单」是要<b>办事</b>，「七天无理由要什么条件」只是<b>问规则</b>。第 5 章把两者分成了 AFTER_SALES 和 POLICY，这一章：
 *
 * <ul>
 *   <li>登录了、意图是 AFTER_SALES → Agent
 *   <li>登录了、意图是查订单 / 闲聊 / 未识别，但话里明说要办事（{@link #ACTION_REQUEST}：「帮我取消」「地址填错了」「我要退款」）→ 也交给 Agent。
 *       意图识别的提示词（第 5 章的 intent-v2）没有「取消订单」「改地址」这两类，小模型常把它们分成查订单；与其改提示词（前几章的录制都要重录），不如用一条
 *       确定的规则兜底
 *   <li>POLICY 仍然走第 5 章 RAG，查订单、问商品仍然走第 3 章
 *   <li>没登录：Agent 什么都办不了（查不了订单），售后类的问题照旧走 RAG 回答规则；话里明说要办事时，回答末尾加一句「请先登录」（{@link #loginHint}）
 * </ul>
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** 明说要办事的说法：「帮我取消」「我要退款」「申请退货」「地址填错了」「买错了」。只认办事的说法，「退货要什么条件」这种问规则的不算。 */
    static final Pattern ACTION_REQUEST =
            Pattern.compile("(我要|我想|帮我|给我|麻烦|申请|请|要)\\S{0,6}(取消|退款|退货|换货|改)|买错|不想要了|地址\\S{0,3}(填错|写错|错了)");

    /** 聊天里的「确认」：只有这几个字（加标点）的短句才算，长句里提到「确认」不算。 */
    static final Pattern AFFIRMATIVE = Pattern.compile("(确认|确定|好的?|可以|是的?|同意|执行|没问题|行|嗯|ok|OK|yes)[!！。.~～]*");

    /** 没登录又明说要办事时，回答末尾加的提示。 */
    public static final String LOGIN_HINT = "办理取消订单、修改地址、退款申请需要先登录：请在页面右上角登录商城账号后再说一次。";

    /** 聊天里说「确认」时的固定回答。 */
    public static final String CHAT_CONFIRMATION_REPLY =
            "要执行这个操作，请点确认卡片上的「确认」按钮；在聊天里回复「确认」不算数——这是为了确保操作是你本人有意点下的。不需要的话点「取消」。";

    /** 交给 Agent 的意图（要配合 {@link #ACTION_REQUEST}，AFTER_SALES 除外）。 */
    private static final Set<Intent> ACTION_INTENTS = Set.of(Intent.ORDER_STATUS, Intent.UNKNOWN, Intent.CHITCHAT);

    private final AgentLoop loop;
    private final NativeAgentRunner nativeRunner;
    private final ConversationOwners owners;
    private final ConfirmationService confirmations;
    private final AgentProperties properties;
    private final ToolProperties toolProperties;
    private final Clock clock;

    /**
     * 创建。
     *
     * @param loop 方案 B
     * @param nativeRunner 方案 A
     * @param owners 归属
     * @param confirmations 待确认操作
     * @param properties {@code assistant.agent.*}
     * @param toolProperties {@code assistant.tools.*}：当前模型支不支持原生工具调用（命令行模型不支持，方案 A 退回 B）
     * @param clock 算办事总时长的时钟（第 6 章 {@code MemoryConfig} 提供的那个；测试里换成可以拨动的）
     */
    public AgentService(
            AgentLoop loop,
            NativeAgentRunner nativeRunner,
            ConversationOwners owners,
            ConfirmationService confirmations,
            AgentProperties properties,
            ToolProperties toolProperties,
            Clock clock) {
        this.loop = loop;
        this.nativeRunner = nativeRunner;
        this.owners = owners;
        this.confirmations = confirmations;
        this.properties = properties;
        this.toolProperties = toolProperties;
        this.clock = clock;
        if (properties.mode() == AgentProperties.Mode.NATIVE && !toolProperties.nativeCalling()) {
            log.warn(
                    "assistant.agent.mode=native 需要模型支持原生工具调用，当前 profile 不支持（assistant.tools.native-calling=false），改用 loop");
        }
    }

    /**
     * 这句话交不交给 Agent。
     *
     * @param intent 识别出的意图
     * @param message 用户原话
     * @param mallToken 商城令牌；没登录时 Agent 不接
     * @return 交给 Agent 时为 true
     */
    public boolean handles(IntentResult intent, String message, String mallToken) {
        if (!properties.enabled() || mallToken == null) {
            return false;
        }
        return intent.intent() == Intent.AFTER_SALES
                || (ACTION_INTENTS.contains(intent.intent())
                        && ACTION_REQUEST.matcher(message).find());
    }

    /**
     * 没登录时要不要提示登录：意图是售后、话里明说要办事。
     *
     * @param intent 识别出的意图
     * @param message 用户原话
     * @param mallToken 商城令牌
     * @return 要提示时是 {@link #LOGIN_HINT}，否则 null
     */
    public String loginHint(IntentResult intent, String message, String mallToken) {
        boolean hint = properties.enabled()
                && mallToken == null
                && intent.intent() == Intent.AFTER_SALES
                && ACTION_REQUEST.matcher(message).find();
        return hint ? LOGIN_HINT : null;
    }

    /**
     * 用户在聊天里说「确认」：如果他在这个会话里有还没处理的待确认操作，回一句固定的话提醒他点按钮；什么都不执行。
     *
     * <p>为什么聊天里的「确认」不算：聊天文字和模型的输入走的是同一条路——它可能是用户随手回的「好的」（其实在回答别的事）、可能是从别处粘贴来的、
     * 也可能是被注入进来的。把「执行」绑在一个独立的、带身份校验的按钮点击上，才能确定是用户本人有意的。也不能交给模型去判断「这句话算不算确认」：
     * 那等于让模型自己确认。
     *
     * @param message 用户原话
     * @param mallToken 商城令牌
     * @param conversationId 会话 ID，可以为 null
     * @return 有待确认操作时是 {@link #CHAT_CONFIRMATION_REPLY}，否则 null（照常回答）
     */
    public String chatConfirmationReply(String message, String mallToken, String conversationId) {
        if (!properties.enabled()
                || mallToken == null
                || !AFFIRMATIVE.matcher(message.strip()).matches()) {
            return null;
        }
        try {
            ConversationOwner owner = owners.resolve(mallToken);
            return confirmations.pendingFor(owner, conversationId).isPresent() ? CHAT_CONFIRMATION_REPLY : null;
        } catch (ConversationException e) {
            return null;
        }
    }

    /**
     * 办一件事。
     *
     * @param message 用户原话
     * @param history 对话历史，单轮时为空列表
     * @param mallToken 商城令牌（{@link #handles} 已保证不为 null）
     * @param conversationId 会话 ID，可以为 null
     * @param listener 每一步、每个待确认操作的通知（流式推事件），阻塞接口传 {@code e -> {}}
     * @param cancelled 页面是否关了
     * @return 结果
     * @throws ModelCallException 某一步调模型失败
     */
    public AgentResult run(
            String message,
            List<Message> history,
            String mallToken,
            String conversationId,
            Consumer<AgentEvent> listener,
            BooleanSupplier cancelled) {
        ConversationOwner owner;
        try {
            owner = owners.resolve(mallToken);
        } catch (ConversationException e) {
            log.info("Agent：确认不了是谁在办（{}），不办", e.failure());
            return new AgentResult(
                    AgentReplies.loginExpired(),
                    new AgentTrace(mode(), List.of(), AgentTrace.StopReason.LOGIN_EXPIRED, 0),
                    null);
        }
        AgentRun run = new AgentRun(new AgentTask(message, history, mallToken, owner, conversationId), listener, clock);
        return "native".equals(mode()) ? nativeRunner.run(run) : loop.run(run, cancelled);
    }

    /** 实际用的方式：配了 native 但模型不支持原生工具调用时退回 loop。 */
    String mode() {
        return properties.mode() == AgentProperties.Mode.NATIVE && toolProperties.nativeCalling() ? "native" : "loop";
    }
}
