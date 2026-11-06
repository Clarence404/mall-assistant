package io.github.clarence404.assistant.tool;

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
import org.springframework.ai.ollama.api.OllamaChatOptions;

/**
 * 回放录制的模型输出：按 Prompt 指纹查出当时真实模型的回答文字和调用意图，原样还给 Spring AI。第 3 章引入。
 *
 * <p>和第 2 章的 {@code ReplayChatModel} 一样不联网、结果确定；不同的是输出里可能带调用意图（工具名 + 模型生成的原样参数），
 * Spring AI 会照常执行工具、把结果追加进对话再问一次，第二次的 Prompt 指纹也在录制里。查不到说明提示词、工具说明或工具结果变了，直接报错。
 */
class ToolReplayChatModel implements ChatModel {

    private final String model;
    private final Map<String, ToolRecordings.Call> outputs;
    private final AtomicInteger calls = new AtomicInteger();

    ToolReplayChatModel(ToolRecordings.Recording recording) {
        this.model = recording.model();
        this.outputs = recording.cases().stream()
                .flatMap(c -> c.calls().stream())
                .collect(Collectors.toMap(ToolRecordings.Call::promptHash, c -> c, (a, b) -> a));
    }

    int calls() {
        return calls.get();
    }

    /** 录制时用的是 Ollama：选项要能带工具（{@code ToolCallingChatOptions}），工具定义才会进 Prompt、指纹才对得上。 */
    @Override
    public ChatOptions getOptions() {
        return OllamaChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        calls.incrementAndGet();
        String hash = ToolRecordings.promptHash(prompt);
        ToolRecordings.Call call = outputs.get(hash);
        if (call == null) {
            throw new IllegalStateException("没有 " + model + " 对这个 Prompt（指纹 " + hash + "）的录制："
                    + "系统提示词、工具说明或工具结果的格式变了，按 docs/ch03.md「录制回放」重新录制");
        }
        List<AssistantMessage.ToolCall> toolCalls = call.toolCalls().stream()
                .map(c -> new AssistantMessage.ToolCall(c.id(), "function", c.name(), c.arguments()))
                .toList();
        AssistantMessage message = AssistantMessage.builder()
                .content(call.text())
                .toolCalls(toolCalls)
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }
}
