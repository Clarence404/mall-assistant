package io.github.clarence404.assistant.chat;

import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.error.ModelFailure;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

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
        return chatService.stream(request.message())
                .filter(text -> !text.isEmpty())
                .map(text -> event(StreamEvents.TOKEN, new StreamEvents.Token(text)))
                .concatWith(Mono.fromSupplier(() -> event(StreamEvents.DONE, StreamEvents.Done.stop())))
                .onErrorResume(e -> Mono.just(errorEvent(e)));
    }

    private static ServerSentEvent<Object> errorEvent(Throwable e) {
        ModelFailure failure = ModelCallException.from(e).failure();
        log.warn("流式调用模型失败：{}", failure, e);
        return event(StreamEvents.ERROR, new StreamEvents.Error(failure.name(), failure.message()));
    }

    private static ServerSentEvent<Object> event(String name, Object data) {
        return ServerSentEvent.builder(data).event(name).build();
    }
}
