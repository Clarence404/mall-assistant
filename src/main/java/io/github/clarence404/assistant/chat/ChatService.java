package io.github.clarence404.assistant.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/** 和模型对话：一问一答（阻塞）与逐段输出（流式）。调用失败统一转成 ModelCallException。 */
@Service
public class ChatService {

    private final ChatClient chatClient;
    private final ChatProperties properties;

    public ChatService(ChatClient chatClient, ChatProperties properties) {
        this.chatClient = chatClient;
        this.properties = properties;
    }

    /** 阻塞调用：等模型生成完整回答再返回。超时由 HTTP 客户端的 read-timeout 控制（见 application.yml）。 */
    public String reply(String message) {
        // TODO ch01-3：用 chatClient 发出用户消息 message，阻塞等待并返回完整回答文本（模型返回 null 时返回空串）。
        //  调用模型抛出的任何 RuntimeException 都要转成 ModelCallException（用 ModelCallException.from）再抛出，
        //  ApiExceptionHandler 才能把它映射成 503。
        //  验收：ChatServiceTest.replyReturnsWholeAnswer / replyMaps*
        throw new UnsupportedOperationException("TODO ch01-3：实现 ChatService#reply");
    }

    /** 流式调用：模型每生成一段就推一段。首段和相邻两段之间超过 streamTimeout 没动静，按超时结束。 */
    public Flux<String> stream(String message) {
        // TODO ch01-4：用 chatClient 发出用户消息 message，以流的方式返回增量文本 Flux<String>。
        //  1. 首段和相邻两段之间超过 properties.streamTimeout() 没有新内容，就以超时结束
        //  2. 流里出现的任何错误都转成 ModelCallException（不要在这里吞掉，交给 Controller 决定怎么推给前端）
        //  验收：ChatServiceTest.stream*
        return Flux.error(new UnsupportedOperationException("TODO ch01-4：实现 ChatService#stream"));
    }
}
