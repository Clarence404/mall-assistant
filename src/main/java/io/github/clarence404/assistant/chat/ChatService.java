package io.github.clarence404.assistant.chat;

import io.github.clarence404.assistant.error.ModelCallException;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/** 和模型对话：一问一答（阻塞）与逐段输出（流式）。调用失败统一转成 {@link ModelCallException}。 */
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
        try {
            String content = chatClient.prompt().user(message).call().content();
            return Objects.requireNonNullElse(content, "");
        } catch (RuntimeException e) {
            throw ModelCallException.from(e);
        }
    }

    /** 流式调用：模型每生成一段就推一段。首段和相邻两段之间超过 streamTimeout 没动静，按超时结束。 */
    public Flux<String> stream(String message) {
        return chatClient.prompt().user(message).stream()
                .content()
                .timeout(properties.streamTimeout())
                .onErrorMap(ModelCallException::from);
    }
}
