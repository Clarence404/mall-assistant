package io.github.clarence404.assistant.chat;

import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.error.ModelFailure;
import io.github.clarence404.assistant.intent.IntentService;
import io.github.clarence404.assistant.mall.MallToken;
import jakarta.validation.Valid;
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
 * <p>所在层：接口层（第 1 章引入，第 2 章加上意图识别，第 3 章加上商城令牌与工具事件）。上游是 {@code static/index.html} 聊天页或任意
 * HTTP 客户端；下游是 {@link ChatService}（回答）与 {@link IntentService}（意图识别）。这里只做协议转换：请求体校验、取出请求头里的
 * 商城令牌、把结果写成 JSON 或 SSE 事件，不放业务逻辑。
 *
 * <p>商城令牌：页面登录后，每次提问在 {@code Authorization: Bearer <商城令牌>} 请求头里带上（没登录就不带）。这里只把它取出来交给
 * {@link ChatService}，不记日志、不放进任何发给模型的文字，最终只在工具里用来调商城接口（见 {@code tool.MallTools}）。
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
    private final IntentService intentService;

    public ChatController(ChatService chatService, IntentService intentService) {
        this.chatService = chatService;
        this.intentService = intentService;
    }

    /**
     * 阻塞接口：{"message": "..."} → {"reply": "...", "intent": {...}, "tools": [...]}；模型调用失败由 ApiExceptionHandler 转成 503。
     *
     * @param request 请求体，{@code @Valid} 触发 {@link ChatRequest} 上的校验（空消息、超长消息直接 400，不浪费一次模型调用）
     * @param authorization {@code Authorization} 请求头，{@code Bearer <商城令牌>}；没登录时不带
     * @return 完整回答、识别出的意图与工具调用记录
     * @throws ModelCallException 模型连不上或超时，由 ApiExceptionHandler 转成 503
     */
    @PostMapping("/api/chat")
    public ChatReply chat(
            @Valid @RequestBody ChatRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return chatService.chat(request.message(), MallToken.fromAuthorizationHeader(authorization));
    }

    /**
     * 流式接口：返回 text/event-stream，先推一个 intent 事件，再推 0 到多个 tool 事件和若干 token 事件，最后一个 done 事件；
     * 出错时推一个 error 事件后结束。
     *
     * <p>Spring MVC 直接支持返回 Flux：每个元素写成一条 SSE，Flux 结束时关闭连接。意图识别是一次阻塞调用，放进
     * {@code Mono.fromCallable}，它失败时同样走 error 事件。
     *
     * <p>Reactor 速记：{@code Mono} 是「0 或 1 个元素」的异步序列，{@code Flux} 是「0 到 N 个元素」；它们是懒的，Spring MVC
     * 订阅之后才真正开始调用模型。下面这条链的含义：
     *
     * <ol>
     *   <li>{@code Mono.fromCallable}：订阅时才执行意图识别，异常会变成序列里的错误信号，而不是直接抛出
     *   <li>{@code flatMapMany} + {@code Flux.concat}：拿到意图后，先发 intent 事件，再按顺序接上回答的 tool / token 事件
     *   <li>{@code concatWith(done)}：上游正常结束才会接上 done；上游出错时不会执行
     *   <li>{@code onErrorResume}：链上任何一步出错，都把错误换成一个 error 事件，然后正常结束
     * </ol>
     *
     * <p>为什么出错不返回 503 而是推 error 事件：流式响应一旦开始写（状态码 200、{@code Content-Type: text/event-stream}
     * 的响应头已经发给浏览器），状态码就改不了了；模型可能推了一半才超时，只能用一条事件告诉前端「出错了、别再等了」。前端据此区分
     * 「正常结束」（done）和「出错结束」（error），连接断了却两者都没收到就是网络中断。
     *
     * @param request 请求体，校验失败时在进入方法前就返回 400 ProblemDetail（此时还没开始推流）
     * @param authorization {@code Authorization} 请求头，{@code Bearer <商城令牌>}；没登录时不带
     * @return SSE 事件流：intent → tool × M → token × N → done，或在任一步出错时以 error 结束
     */
    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream(
            @Valid @RequestBody ChatRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String message = request.message();
        String mallToken = MallToken.fromAuthorizationHeader(authorization);
        return Mono.fromCallable(() -> intentService.classify(message))
                .flatMapMany(intent -> Flux.concat(
                        Mono.just(event(StreamEvents.INTENT, intent)),
                        chatService
                                .answerStream(message, intent, mallToken)
                                // 模型偶尔会推内容为空的片段，不值得占一条事件
                                .filter(part -> !(part instanceof AnswerPart.Text text
                                        && text.text().isEmpty()))
                                .map(ChatController::partEvent)))
                .concatWith(Mono.fromSupplier(() -> event(StreamEvents.DONE, StreamEvents.Done.stop())))
                .onErrorResume(e -> Mono.just(errorEvent(e)));
    }

    /**
     * 回答片段 → SSE：文字是 token 事件，工具调用记录是 tool 事件。
     *
     * @param part {@link ChatService#answerStream} 产出的片段
     */
    private static ServerSentEvent<Object> partEvent(AnswerPart part) {
        return switch (part) {
            case AnswerPart.Text text -> event(StreamEvents.TOKEN, new StreamEvents.Token(text.text()));
            case AnswerPart.ToolUse tool -> event(StreamEvents.TOOL, tool.invocation());
        };
    }

    /**
     * 把任意异常转成 error 事件：先归类成 {@link ModelFailure}，日志里记完整异常，事件里只给错误码和面向用户的提示。
     *
     * @param e 链上抛出的异常（意图识别失败、模型流式调用失败、超时等）
     * @return {@code event: error} 事件，code 与阻塞接口 ProblemDetail 里的 code 相同
     */
    private static ServerSentEvent<Object> errorEvent(Throwable e) {
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
