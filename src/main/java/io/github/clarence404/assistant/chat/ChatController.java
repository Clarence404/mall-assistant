package io.github.clarence404.assistant.chat;

import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.error.ModelFailure;
import io.github.clarence404.assistant.guard.CallerResolver;
import io.github.clarence404.assistant.guard.ChatGuard;
import io.github.clarence404.assistant.guard.ChatRejection;
import io.github.clarence404.assistant.mall.MallToken;
import io.github.clarence404.assistant.memory.ConversationException;
import io.github.clarence404.assistant.ops.ChatMetrics;
import io.github.clarence404.assistant.ops.TurnRecord;
import io.github.clarence404.assistant.ops.UsageLedger;
import io.github.clarence404.assistant.ops.UsageTrace;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 对话的 HTTP 入口：阻塞接口 {@code POST /api/chat} 与流式接口 {@code POST /api/chat/stream}。
 *
 * <p>所在层：接口层（第 1 章引入，第 2 章加上意图识别，第 3 章加上商城令牌与工具事件，第 5 章加上 RAG 的 source 事件，第 6 章加上会话 ID 与 memory 事件，
 * 第 7 章加上 Agent 的 step 与 confirm 事件，第 9 章加上进门检查、Token 记账、指标，done 事件里带 usage，被挡下的资料是 blocked 事件；确认按钮走另一个接口 {@code agent.ConfirmationController}）。上游是 {@code static/index.html} 聊天页或任意
 * HTTP 客户端；下游是 {@link ChatService}（打开会话、意图识别、回答；第 5 章之前流式接口直接调 {@code IntentService}，第 6 章起意图识别要和会话一起做，统一交给 ChatService）。这里只做协议转换：请求体校验、取出请求头里的
 * 商城令牌、把结果写成 JSON 或 SSE 事件，不放业务逻辑。
 *
 * <p>商城令牌：页面登录后，每次提问在 {@code Authorization: Bearer <商城令牌>} 请求头里带上（没登录就不带）。这里只把它取出来交给
 * {@link ChatService}，不记日志、不放进任何发给模型的文字，最终只在工具里用来调商城接口（见 {@code tool.MallTools}）。
 *
 * <p>第 9 章：每一轮先「进门」再干活——{@link CallerResolver} 算出这一轮算谁的（登录用户 / IP），{@link ChatGuard#admit} 过每日预算、每分钟次数、
 * 并发上限三关（挡下就是 429 / 503，流式是一个 error 事件）；进门之后 {@link UsageLedger#open} 一本这一轮的账交给 {@link ChatService}，结束时
 * （成功、失败、页面关掉都算）记账（{@link UsageLedger#commit}）、记指标（{@link ChatMetrics#turn}）、归还并发名额。
 *
 * <p>两个接口的区别：
 *
 * <ul>
 *   <li>阻塞：等模型把整段回答生成完才返回，实现简单，但用户要干等几秒到几十秒
 *   <li>流式：模型每生成一小段（若干 token）就推给前端，用户很快就能看到第一个字；代价是错误处理更复杂（见 {@link #stream}）
 * </ul>
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final ChatGuard guard;
    private final CallerResolver callers;
    private final UsageLedger ledger;
    private final ChatMetrics metrics;

    /**
     * 创建。
     *
     * @param chatService 对话服务
     * @param guard 第 9 章：进门检查（预算、次数、并发）
     * @param callers 第 9 章：这一轮算谁的
     * @param ledger 第 9 章：Token 总账
     * @param metrics 第 9 章：指标
     */
    public ChatController(
            ChatService chatService, ChatGuard guard, CallerResolver callers, UsageLedger ledger, ChatMetrics metrics) {
        this.chatService = chatService;
        this.guard = guard;
        this.callers = callers;
        this.ledger = ledger;
        this.metrics = metrics;
    }

    /**
     * 阻塞接口：{"message": "...", "conversationId": "..."} → {"reply": "...", "intent": {...}, "tools": [...], "sources": [...], "rag": {...},
     * "memory": {...}}；模型调用失败由 ApiExceptionHandler 转成 503。第 5 章起规则类问题走 RAG，sources 是引用的资料，rag 是检索与引用校验的调试信息；
     * 第 6 章起带了会话 ID 就是多轮对话，memory 是记忆的调试信息，同一个会话的上一条还没回答完时返回 409；第 7 章起办事请求交给 Agent，
     * agent 是每一步的记录，提出写操作时 confirmation 是待确认操作（页面画确认卡片）。
     *
     * @param request 请求体，{@code @Valid} 触发 {@link ChatRequest} 上的校验（空消息、超长消息直接 400，不浪费一次模型调用）
     * @param authorization {@code Authorization} 请求头，{@code Bearer <商城令牌>}；没登录时不带
     * @param http 第 9 章：取来源地址（匿名按 IP 限流）
     * @return 完整回答、识别出的意图与工具调用记录；第 9 章起带 usage（这一轮的 Token、今日用量与预算）
     * @throws ModelCallException 模型连不上或超时，由 ApiExceptionHandler 转成 503
     * @throws ChatRejection 第 9 章：进门被挡下，由 ApiExceptionHandler 转成 429 / 503
     */
    @PostMapping("/api/chat")
    public ChatReply chat(
            @Valid @RequestBody ChatRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest http) {
        String mallToken = MallToken.fromAuthorizationHeader(authorization);
        String caller = callers.resolve(mallToken, http.getRemoteAddr());
        ChatGuard.Permit permit;
        try {
            permit = guard.admit(caller);
        } catch (ChatRejection e) {
            metrics.rejected("chat", e.reason().name());
            throw e;
        }
        TurnRecord record = ledger.open(caller, request.conversationId());
        String outcome = "UNKNOWN";
        try (permit) {
            ChatReply reply = chatService.chat(request.message(), request.conversationId(), mallToken, record);
            outcome = ChatMetrics.OK;
            ledger.commit(record);
            return reply.withUsage(budgeted(record));
        } catch (RuntimeException e) {
            outcome = outcomeOf(e);
            throw e;
        } finally {
            ledger.commit(record);
            metrics.turn("chat", record, outcome);
        }
    }

    /**
     * 流式接口：返回 text/event-stream，先推一个 intent 事件（第 6 章起带了会话 ID 时接着一个 memory 事件），再推 0 到多个 tool / source 事件和
     * 若干 token 事件，最后一个 done 事件；出错时推一个 error 事件后结束（会话正忙也是 error 事件，code 为 CONVERSATION_BUSY）。
     *
     * <p>Spring MVC 直接支持返回 Flux：每个元素写成一条 SSE，Flux 结束时关闭连接。意图识别是一次阻塞调用，放进
     * {@code Mono.fromCallable}，它失败时同样走 error 事件。
     *
     * <p>Reactor 速记：{@code Mono} 是「0 或 1 个元素」的异步序列，{@code Flux} 是「0 到 N 个元素」；它们是懒的，Spring MVC
     * 订阅之后才真正开始调用模型。下面这条链的含义：
     *
     * <ol>
     *   <li>{@code Flux.using}：订阅时才打开会话、识别意图（{@link ChatService#begin}），异常会变成序列里的错误信号，而不是直接抛出；
     *       不管序列怎么结束（完成、出错、页面断开取消），最后都关掉会话（第 6 章）
     *   <li>{@code Flux.concat}：拿到意图后，先发 intent 事件，再按顺序接上回答的 memory / tool / source / token 事件
     *   <li>{@code concatWith(done)}：上游正常结束才会接上 done；上游出错时不会执行（第 9 章起 done 里带这一轮的 Token 用量，所以挪到了记账之后）
     *   <li>{@code onErrorResume}：链上任何一步出错，都把错误换成一个 error 事件，然后正常结束
     * </ol>
     *
     * <p>为什么出错不返回 503 而是推 error 事件：流式响应一旦开始写（状态码 200、{@code Content-Type: text/event-stream}
     * 的响应头已经发给浏览器），状态码就改不了了；模型可能推了一半才超时，只能用一条事件告诉前端「出错了、别再等了」。前端据此区分
     * 「正常结束」（done）和「出错结束」（error），连接断了却两者都没收到就是网络中断。
     *
     * @param request 请求体，校验失败时在进入方法前就返回 400 ProblemDetail（此时还没开始推流）
     * @param authorization {@code Authorization} 请求头，{@code Bearer <商城令牌>}；没登录时不带
     * @param http 第 9 章：取来源地址
     * @return SSE 事件流：intent → tool × M → token × N → done（第 9 章起带 usage），或在任一步出错时以 error 结束
     */
    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream(
            @Valid @RequestBody ChatRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest http) {
        String message = request.message();
        String mallToken = MallToken.fromAuthorizationHeader(authorization);
        // 第 9 章：在请求线程上就把人认好（可能要问一次商城），后面的流里不再碰 HttpServletRequest
        String caller = callers.resolve(mallToken, http.getRemoteAddr());
        return Flux.defer(() -> {
                    // 进门：被挡下时直接以 error 事件结束（流一开始就是 200，只能用事件告诉页面），连 intent 都没有
                    ChatGuard.Permit permit = guard.admit(caller);
                    TurnRecord record = ledger.open(caller, request.conversationId());
                    AtomicReference<String> outcome = new AtomicReference<>("CANCELLED");
                    return Flux.using(
                                    () -> chatService.begin(message, request.conversationId(), mallToken, record),
                                    turn -> Flux.concat(
                                            Mono.just(event(StreamEvents.INTENT, turn.intent())),
                                            chatService
                                                    .answerStream(turn)
                                                    // 模型偶尔会推内容为空的片段，不值得占一条事件
                                                    .filter(part -> !(part instanceof AnswerPart.Text text
                                                            && text.text().isEmpty()))
                                                    .map(ChatController::partEvent)),
                                    turn -> turn.conversation().close())
                            // 第 9 章：说完了先记账，再把这一轮的用量放进 done 事件推给页面
                            .concatWith(Mono.fromSupplier(() -> {
                                outcome.set(ChatMetrics.OK);
                                ledger.commit(record);
                                return event(StreamEvents.DONE, StreamEvents.Done.stop(budgeted(record)));
                            }))
                            .doOnError(e -> outcome.set(outcomeOf(e)))
                            .doFinally(signal -> {
                                permit.close();
                                ledger.commit(record);
                                metrics.turn("stream", record, outcome.get());
                            });
                })
                .onErrorResume(e -> {
                    if (e instanceof ChatRejection rejection) {
                        metrics.rejected("stream", rejection.reason().name());
                    }
                    return Mono.just(errorEvent(e));
                });
    }

    /** 这一轮的用量，补上今日用量与预算（记完账之后调）。 */
    private UsageTrace budgeted(TurnRecord record) {
        return record.usage().withBudget(ledger.usedToday(record.caller()), ledger.dailyBudget());
    }

    /** 失败时指标里的 outcome：模型、会话、进门各自的错误码。 */
    static String outcomeOf(Throwable e) {
        if (e instanceof ChatRejection rejection) {
            return rejection.reason().name();
        }
        if (e instanceof ConversationException conversation) {
            return conversation.failure().name();
        }
        return ModelCallException.from(e).failure().name();
    }

    /**
     * 回答片段 → SSE：文字是 token 事件，工具调用记录是 tool 事件，RAG 引用来源是 source 事件（第 5 章），记忆情况是 memory 事件（第 6 章），
     * Agent 的一步是 step 事件、待确认操作是 confirm 事件（第 7 章）。
     *
     * @param part {@link ChatService#answerStream} 产出的片段
     */
    private static ServerSentEvent<Object> partEvent(AnswerPart part) {
        return switch (part) {
            case AnswerPart.Text text -> event(StreamEvents.TOKEN, new StreamEvents.Token(text.text()));
            case AnswerPart.ToolUse tool -> event(StreamEvents.TOOL, tool.invocation());
            case AnswerPart.Source source -> event(StreamEvents.SOURCE, source.source());
            case AnswerPart.Memory memory -> event(StreamEvents.MEMORY, memory.trace());
            case AnswerPart.Step step -> event(StreamEvents.STEP, step.step());
            case AnswerPart.Confirm confirm -> event(StreamEvents.CONFIRM, confirm.confirmation());
            case AnswerPart.Blocked blocked -> event(StreamEvents.BLOCKED, blocked.source());
        };
    }

    /**
     * 把任意异常转成 error 事件：先归类成 {@link ModelFailure}，日志里记完整异常，事件里只给错误码和面向用户的提示。
     *
     * @param e 链上抛出的异常（意图识别失败、模型流式调用失败、超时等）
     * @return {@code event: error} 事件，code 与阻塞接口 ProblemDetail 里的 code 相同
     */
    private static ServerSentEvent<Object> errorEvent(Throwable e) {
        if (e instanceof ChatRejection rejection) {
            // 第 9 章：进门被挡下（太频繁、太忙、今日额度用完），不是模型的问题
            log.info("流式对话被挡下：{}", rejection.reason());
            return event(
                    StreamEvents.ERROR,
                    new StreamEvents.Error(
                            rejection.reason().name(), rejection.reason().message()));
        }
        if (e instanceof ConversationException conversation) {
            // 第 6 章：会话正忙等不是模型的问题，用会话自己的错误码
            log.info("流式对话的会话操作失败：{}", conversation.failure());
            return event(
                    StreamEvents.ERROR,
                    new StreamEvents.Error(
                            conversation.failure().name(),
                            conversation.failure().message()));
        }
        ModelFailure failure = ModelCallException.from(e).failure();
        log.warn("流式调用模型失败：{}", failure, e);
        return event(StreamEvents.ERROR, new StreamEvents.Error(failure.name(), failure.message()));
    }

    /**
     * 组装一条 SSE：{@code event:} 行是事件名，{@code data:} 行是 data 对象序列化成的 JSON。
     *
     * @param name 事件名，取 {@link StreamEvents} 里的常量
     * @param data 事件数据，由 Spring 的 JSON 编码器写成一行 JSON
     */
    private static ServerSentEvent<Object> event(String name, Object data) {
        return ServerSentEvent.builder(data).event(name).build();
    }
}
