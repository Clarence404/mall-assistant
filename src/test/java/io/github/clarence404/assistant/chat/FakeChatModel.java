package io.github.clarence404.assistant.chat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import reactor.core.publisher.Flux;

/**
 * 不联网的假模型：按预设返回回答 / 片段 / 异常，并记下收到的每个 Prompt，供测试断言。
 *
 * <p>第 2 章起，一次对话要调两次模型：先意图识别（结构化输出），再回答。假模型按请求区分两类：带输出 Schema 的请求算「结构化输出调用」，用
 * {@link #willClassify} / {@link #willFailClassification} 的预设；其余算「回答调用」，用 {@link #willReply} / {@link #willStream} /
 * {@link #willFail} 的预设。
 *
 * <p>和 Ollama、OpenAI 一样，假模型默认声明支持原生结构化输出（选项是 {@link StructuredOutputChatOptions}），Schema 放在
 * {@code outputSchema} 选项里；{@code new FakeChatModel(false)} 模拟不支持的模型，Spring AI 改为把格式说明拼进用户消息。
 */
public class FakeChatModel implements ChatModel {

    /** 默认的意图识别输出：UNKNOWN，不触发拒答，对话照常走回答模型。 */
    public static final String UNKNOWN_INTENT_JSON =
            "{\"intent\":\"UNKNOWN\",\"confidence\":0.0,\"orderNo\":null,\"skuId\":null}";

    private final boolean nativeStructuredOutput;
    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private volatile String reply = "您好，请问有什么可以帮您？";
    private volatile List<String> chunks = List.of("您好，", "请问", "有什么可以帮您？");
    private volatile RuntimeException failure;
    private volatile Flux<String> streamOverride;
    private volatile List<String> classifications = List.of(UNKNOWN_INTENT_JSON);
    private volatile RuntimeException classificationFailure;
    private volatile int classificationCalls;

    public FakeChatModel() {
        this(true);
    }

    /** @param nativeStructuredOutput 是否声明支持原生结构化输出 */
    public FakeChatModel(boolean nativeStructuredOutput) {
        this.nativeStructuredOutput = nativeStructuredOutput;
    }

    @Override
    public ChatOptions getOptions() {
        return nativeStructuredOutput
                ? StructuredOutputChatOptions.builder().build()
                : ChatOptions.builder().build();
    }

    /** 恢复默认：正常回答、意图 UNKNOWN、清空记录。 */
    public void reset() {
        prompts.clear();
        reply = "您好，请问有什么可以帮您？";
        chunks = List.of("您好，", "请问", "有什么可以帮您？");
        failure = null;
        streamOverride = null;
        classifications = List.of(UNKNOWN_INTENT_JSON);
        classificationFailure = null;
        classificationCalls = 0;
    }

    public void willReply(String text) {
        this.reply = text;
    }

    public void willStream(String... parts) {
        this.chunks = List.of(parts);
    }

    /** 回答调用：阻塞调用直接抛出；流式调用先发出预设片段，再以该异常结束。不影响意图识别调用。 */
    public void willFail(RuntimeException error) {
        this.failure = error;
    }

    /** 完全自定义流式输出，例如 Flux.never() 模拟模型一直不吭声。 */
    public void willStream(Flux<String> parts) {
        this.streamOverride = parts;
    }

    /** 意图识别调用依次返回这些原始文本（模拟模型的输出，可以是合法 JSON，也可以是乱七八糟的话），用完后重复最后一个。 */
    public void willClassify(String... rawOutputs) {
        this.classifications = List.of(rawOutputs);
        this.classificationCalls = 0;
    }

    /** 意图识别调用直接抛出该异常（模拟模型连不上、超时）。 */
    public void willFailClassification(RuntimeException error) {
        this.classificationFailure = error;
    }

    public List<Prompt> prompts() {
        return prompts;
    }

    public Prompt lastPrompt() {
        return prompts.getLast();
    }

    /** 意图识别（结构化输出）调用收到的 Prompt。 */
    public List<Prompt> classificationPrompts() {
        return prompts.stream().filter(FakeChatModel::isStructured).toList();
    }

    /** 回答调用收到的 Prompt。 */
    public List<Prompt> answerPrompts() {
        return prompts.stream().filter(p -> !isStructured(p)).toList();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        if (isStructured(prompt)) {
            if (classificationFailure != null) {
                throw classificationFailure;
            }
            List<String> outputs = classifications;
            int index = Math.min(classificationCalls++, outputs.size() - 1);
            return response(outputs.get(index));
        }
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

    /**
     * 结构化输出调用：原生模式下 Schema 在 {@code outputSchema} 选项里；否则 {@code .entity(...)} 会在用户消息末尾追加 BeanOutputConverter
     * 的格式说明，其中含「JSON Schema」字样。
     */
    public static boolean isStructured(Prompt prompt) {
        if (prompt.getOptions() instanceof StructuredOutputChatOptions options && options.getOutputSchema() != null) {
            return true;
        }
        return prompt.getUserMessage().getText().contains("JSON Schema");
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
