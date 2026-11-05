package io.github.clarence404.assistant.chat;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /** 阻塞接口：{"message": "..."} → {"reply": "..."}；模型调用失败由 ApiExceptionHandler 转成 503。 */
    @PostMapping("/api/chat")
    public ChatReply chat(@Valid @RequestBody ChatRequest request) {
        return new ChatReply(chatService.reply(request.message()));
    }

    /**
     * 流式接口：返回 text/event-stream，依次推送若干 token 事件，最后一个 done 事件；出错时推一个 error 事件后结束。
     *
     * <p>Spring MVC 直接支持返回 Flux：每个元素写成一条 SSE，Flux 结束时关闭连接。
     */
    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream(@Valid @RequestBody ChatRequest request) {
        // TODO ch01-6：把 chatService.stream(...) 的文本流转成 SSE 事件流（事件名与 data 结构见 StreamEvents）：
        //  1. 每段非空文本 → event 为 token，data 为 StreamEvents.Token
        //  2. 正常结束后再追加一个 event 为 done 的事件，data 为 StreamEvents.Done.stop()
        //  3. 任何错误 → 只推一个 event 为 error 的事件（data 为 StreamEvents.Error，code 取 ModelFailure 的 name()，
        //     message 取 ModelFailure#message()），然后正常结束；出错时不能再发 done，也不能把异常信息推给前端
        //  构造事件：ServerSentEvent.builder(data).event(name).build()
        //  验收：ChatStreamApiTest
        return Flux.error(new UnsupportedOperationException("TODO ch01-6：实现 ChatController#stream"));
    }
}
