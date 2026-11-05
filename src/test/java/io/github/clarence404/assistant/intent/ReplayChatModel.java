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
 * <p>查不到说明发给模型的内容和录制时不一样了（改了提示词、用户消息模板或 {@link IntentResult} 的字段），直接报错，提示重新录制。
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

    @Override
    public ChatResponse call(Prompt prompt) {
        calls.incrementAndGet();
        String hash = IntentRecordings.promptHash(prompt);
        String output = outputs.get(hash);
        if (output == null) {
            throw new IllegalStateException("没有 " + model + " 对这个 Prompt（指纹 " + hash + "）的录制："
                    + "提示词、用户消息模板或 IntentResult 的字段变了，按 docs/ch02.md「录制回放」重新录制");
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(output))));
    }
}
