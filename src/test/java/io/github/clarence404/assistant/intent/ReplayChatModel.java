package io.github.clarence404.assistant.intent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;

/**
 * 回放录制的模型输出：按 Prompt 指纹查出当时真实模型的原样输出返回，不联网、结果完全确定。
 *
 * <p>查不到说明发给模型的消息文本和录制时不一样了（改了意图识别提示词或用户消息模板，见 {@link IntentRecordings} 的指纹说明），
 * 直接报错，提示重新录制。第 2 章引入。
 *
 * <p>和 {@code FakeChatModel} 的区别：FakeChatModel 的输出是测试里手写的，用来制造各种情况；这里的输出是真实模型说过的原话
 * （包括多余的解释、Markdown 代码块、写错的标签），用来确认代码面对真实输出也能工作，同时保持确定、不联网。
 */
class ReplayChatModel implements ChatModel {

    private final String model;
    private final Map<String, String> outputs;
    private final AtomicInteger calls = new AtomicInteger();

    ReplayChatModel(IntentRecordings.Recording recording) {
        this.model = recording.model();
        this.outputs = recording.cases().stream()
                .flatMap(c -> c.calls().stream())
                .collect(Collectors.toMap(
                        IntentRecordings.Call::promptHash, IntentRecordings.Call::output, (a, b) -> a));
    }

    /** 一共被调用了几次（含重试）。 */
    int calls() {
        return calls.get();
    }

    /**
     * 录制时用的是 Ollama：它支持原生结构化输出，Schema 走 {@code format} 参数、不拼进用户消息。回放要声明同样的能力，
     * 发出的 Prompt（以及指纹）才和录制时一致。
     */
    @Override
    public ChatOptions getOptions() {
        return StructuredOutputChatOptions.builder().build();
    }

    /** 按 Prompt 指纹取出录制的原样输出返回；查不到就抛 {@link IllegalStateException}。 */
    @Override
    public ChatResponse call(Prompt prompt) {
        calls.incrementAndGet();
        String hash = IntentRecordings.promptHash(prompt);
        String output = outputs.get(hash);
        if (output == null) {
            throw new IllegalStateException("没有 " + model + " 对这个 Prompt（指纹 " + hash + "）的录制："
                    + "意图提示词或用户消息模板变了，按 docs/ch02.md「录制回放」重新录制"
                    + "（注意：只改 IntentResult 的字段不会触发这个报错，Schema 不在消息里，也要手动重新录制）");
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(output))));
    }
}
