package io.github.clarence404.assistant.chat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** 不联网的假模型：按预设返回回答 / 片段 / 异常，并记下收到的每个 Prompt，供测试断言。 */
public class FakeChatModel implements ChatModel {

    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private volatile String reply = "您好，请问有什么可以帮您？";
    private volatile List<String> chunks = List.of("您好，", "请问", "有什么可以帮您？");
    private volatile RuntimeException failure;
    private volatile Flux<String> streamOverride;

    /** 恢复默认：正常回答、清空记录。 */
    public void reset() {
        prompts.clear();
        reply = "您好，请问有什么可以帮您？";
        chunks = List.of("您好，", "请问", "有什么可以帮您？");
        failure = null;
        streamOverride = null;
    }

    public void willReply(String text) {
        this.reply = text;
    }

    public void willStream(String... parts) {
        this.chunks = List.of(parts);
    }

    /** 阻塞调用直接抛出；流式调用先发出预设片段，再以该异常结束。 */
    public void willFail(RuntimeException error) {
        this.failure = error;
    }

    /** 完全自定义流式输出，例如 Flux.never() 模拟模型一直不吭声。 */
    public void willStream(Flux<String> parts) {
        this.streamOverride = parts;
    }

    public List<Prompt> prompts() {
        return prompts;
    }

    public Prompt lastPrompt() {
        return prompts.getLast();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        if (failure != null) {
            throw failure;
        }
        return response(reply);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        prompts.add(prompt);
        Flux<String> parts = streamOverride != null ? streamOverride : Flux.fromIterable(chunks);
        if (failure != null) {
            parts = parts.concatWith(Flux.error(failure));
        }
        return parts.map(FakeChatModel::response);
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
