package io.github.clarence404.assistant.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

/**
 * 不联网的假模型：按预设返回回答 / 片段 / 异常，并记下收到的每个 Prompt，供测试断言。第 1 章引入，第 2 章加上意图识别相关的预设。
 *
 * <p>为什么可以拿假模型测：业务代码（ChatService、IntentService）只依赖 Spring AI 的 {@link ChatModel} 接口，经 ChatClient 调用，
 * 并不知道背后是 Ollama、OpenAI 还是这个类。把它换成假的，测的就是「我们自己的代码」：提示词有没有带上、输出怎么解析、失败怎么归类、
 * 流式事件怎么排。好处是：结果完全确定（真实模型同一句话每次回答都可能不同）、不联网不花钱、毫秒级；还能随意制造真实环境里很难复现的情况，
 * 比如连接被拒、读超时、流到一半出错、模型一直不吭声、输出不是 JSON。模型本身答得好不好，不归这类测试管（见 OllamaChatIT 与录制回放）。
 *
 * <p>第 2 章起，一次对话要调两次模型：先意图识别（结构化输出），再回答。假模型按请求区分两类：带输出 Schema 的请求算「结构化输出调用」，用
 * {@link #willClassify} / {@link #willFailClassification} 的预设；其余算「回答调用」，用 {@link #willReply} / {@link #willStream} /
 * {@link #willFail} 的预设。
 *
 * <p>和 Ollama、OpenAI 一样，假模型默认声明支持原生结构化输出（选项是 {@link StructuredOutputChatOptions}），Schema 放在
 * {@code outputSchema} 选项里；{@code new FakeChatModel(false)} 模拟不支持的模型，Spring AI 改为把格式说明拼进用户消息。
 *
 * <p>第 3 章加上工具调用：默认同时声明支持原生工具调用（选项是 {@link ToolCallingChatOptions}），这样 ChatClient 才会把注册的工具和
 * ToolContext 放进 Prompt——Spring AI 只在模型的选项是 ToolCallingChatOptions 时才附带工具，否则悄悄丢掉。这里直接借用同时实现了两种接口的
 * {@link OllamaChatOptions}，只是拿它当「选项对象」，并不连 Ollama。{@link #withoutToolCalling()} 模拟命令行模型那样只支持结构化输出、
 * 不支持工具调用的模型。用 {@link #willCallTool} 预设「模型决定调用某个工具」：回答调用的第一轮返回调用意图（工具名 + JSON 参数），
 * Spring AI 执行工具、把结果作为工具结果消息追加进对话后再调一次，这时假模型才返回 {@link #willReply} 的回答。
 *
 * <p>第 6 章多了两种「辅助调用」，按用户消息里的标签区分：带 {@code <对话历史>} 的是追问改写（{@code QuestionCondenser}），返回
 * {@link #willCondense} 的预设；带 {@code <要压缩的对话>} 的是摘要记忆（{@code ConversationSummarizer}），返回 {@link #willSummarize}
 * 的预设。它们不算「回答调用」（{@link #answerPrompts()} 里没有），分别记在 {@link #condensePrompts()} / {@link #summaryPrompts()}。
 *
 * <p>第 7 章多了 Agent 的「下一步」调用：用户消息里有 {@code <办事记录>} 标签（{@code AgentPlanner#STEP_TEMPLATE}），它也是结构化输出，但不算意图识别，
 * 依次返回 {@link #willAct} 预设的原始输出（用完后重复最后一个，正好用来模拟「小模型一直在做同一件事」），记在 {@link #agentPrompts()}。
 */
public class FakeChatModel implements ChatModel {

    /** 默认的意图识别输出：UNKNOWN，不触发拒答，对话照常走回答模型。 */
    public static final String UNKNOWN_INTENT_JSON =
            "{\"intent\":\"UNKNOWN\",\"confidence\":0.0,\"orderNo\":null,\"skuId\":null}";

    private final boolean nativeStructuredOutput;
    private final boolean nativeToolCalling;
    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private final List<AssistantMessage.ToolCall> toolCalls = new CopyOnWriteArrayList<>();
    private final AtomicInteger toolCallIds = new AtomicInteger();
    private volatile String reply = "您好，请问有什么可以帮您？";
    private volatile List<String> chunks = List.of("您好，", "请问", "有什么可以帮您？");
    private volatile RuntimeException failure;
    private volatile Flux<String> streamOverride;
    private volatile List<String> classifications = List.of(UNKNOWN_INTENT_JSON);
    private volatile RuntimeException classificationFailure;
    private volatile int classificationCalls;
    private volatile String condensed = "（没有预设改写结果）";
    private volatile RuntimeException condenseFailure;
    private volatile String summary = "（没有预设摘要）";
    private volatile List<String> actions = List.of(FINISH_JSON);
    private volatile int actionCalls;
    private volatile RuntimeException actionFailure;

    /** 第 7 章：Agent 默认的下一步：直接回答。 */
    public static final String FINISH_JSON =
            "{\"action\":\"finish\",\"input\":\"请问要办什么？\",\"orderNo\":\"\",\"thought\":\"没有可办的\"}";

    /** 默认声明支持原生结构化输出与原生工具调用，与 Ollama 的行为一致。 */
    public FakeChatModel() {
        this(true, true);
    }

    /** @param nativeStructuredOutput 是否声明支持原生结构化输出（为 false 时也不支持工具调用） */
    public FakeChatModel(boolean nativeStructuredOutput) {
        this(nativeStructuredOutput, nativeStructuredOutput);
    }

    private FakeChatModel(boolean nativeStructuredOutput, boolean nativeToolCalling) {
        this.nativeStructuredOutput = nativeStructuredOutput;
        this.nativeToolCalling = nativeToolCalling;
    }

    /** 只支持原生结构化输出、不支持工具调用的模型，和 {@code CliChatModel} 一样。 */
    public static FakeChatModel withoutToolCalling() {
        return new FakeChatModel(true, false);
    }

    /**
     * ChatClient 构造请求时，以模型的默认选项为底。返回 {@link StructuredOutputChatOptions} 等于告诉 Spring AI「我能接收 JSON
     * Schema」，{@code entity(..., useProviderStructuredOutput())} 才会把 Schema 放进选项，而不是拼进用户消息；返回
     * {@link ToolCallingChatOptions} 等于告诉它「我能接收工具」。{@link OllamaChatOptions} 两个都是。
     */
    @Override
    public ChatOptions getOptions() {
        if (nativeToolCalling) {
            return OllamaChatOptions.builder().build();
        }
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
        toolCalls.clear();
        condensed = "（没有预设改写结果）";
        condenseFailure = null;
        summary = "（没有预设摘要）";
        actions = List.of(FINISH_JSON);
        actionCalls = 0;
        actionFailure = null;
    }

    /** 第 7 章：Agent 的每一步都直接抛出该异常（模拟模型在办事中途挂了）。 */
    public void willFailAct(RuntimeException error) {
        this.actionFailure = error;
    }

    /**
     * 第 7 章：Agent 每一步依次返回这些原始输出（一般是 {@code AgentDecision} 的 JSON，也可以是乱码），用完后重复最后一个。
     *
     * @param rawOutputs 每一步模型的原样输出
     */
    public void willAct(String... rawOutputs) {
        this.actions = List.of(rawOutputs);
        this.actionCalls = 0;
    }

    /** 第 7 章：Agent 每一步收到的 Prompt。 */
    public List<Prompt> agentPrompts() {
        return prompts.stream().filter(FakeChatModel::isAgentStep).toList();
    }

    /** Agent 的一步：用户消息里有 {@code <办事记录>} 标签。 */
    public static boolean isAgentStep(Prompt prompt) {
        return prompt.getUserMessage().getText().contains("<办事记录>");
    }

    /** 第 6 章：追问改写调用返回这段文本（模型「改写后的问题」的原样输出）。 */
    public void willCondense(String text) {
        this.condensed = text;
    }

    /** 第 6 章：追问改写调用直接抛出该异常。 */
    public void willFailCondense(RuntimeException error) {
        this.condenseFailure = error;
    }

    /** 第 6 章：摘要记忆的压缩调用返回这段文本。 */
    public void willSummarize(String text) {
        this.summary = text;
    }

    /** 第 6 章：追问改写调用收到的 Prompt。 */
    public List<Prompt> condensePrompts() {
        return prompts.stream().filter(FakeChatModel::isCondense).toList();
    }

    /** 第 6 章：摘要记忆调用收到的 Prompt。 */
    public List<Prompt> summaryPrompts() {
        return prompts.stream().filter(FakeChatModel::isSummary).toList();
    }

    /** 追问改写的调用：用户消息里有 {@code <对话历史>} 标签。 */
    public static boolean isCondense(Prompt prompt) {
        return !isStructured(prompt) && prompt.getUserMessage().getText().contains("<对话历史>");
    }

    /** 摘要记忆的调用：用户消息里有 {@code <要压缩的对话>} 标签。 */
    public static boolean isSummary(Prompt prompt) {
        return !isStructured(prompt) && prompt.getUserMessage().getText().contains("<要压缩的对话>");
    }

    /** 回答调用（阻塞）返回这段文本。 */
    public void willReply(String text) {
        this.reply = text;
    }

    /** 回答调用（流式）依次发出这些片段，模拟模型逐段生成。 */
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

    /**
     * 回答调用的第一轮：模型不说话，而是要求调用这个工具（可以调多次，表示同一轮里并行调用多个工具）。
     *
     * @param name 工具名，如 queryOrderStatus
     * @param argumentsJson 模型生成的参数，JSON 字符串，如 {@code {"orderNo":"..."}}
     */
    public void willCallTool(String name, String argumentsJson) {
        toolCalls.add(new AssistantMessage.ToolCall(
                "call_" + toolCallIds.incrementAndGet(), "function", name, argumentsJson));
    }

    /** 意图识别调用直接抛出该异常（模拟模型连不上、超时）。 */
    public void willFailClassification(RuntimeException error) {
        this.classificationFailure = error;
    }

    /** 收到的全部 Prompt（意图识别与回答都算），按调用顺序。 */
    public List<Prompt> prompts() {
        return prompts;
    }

    /** 最后一次收到的 Prompt，常用来断言系统消息与用户消息。 */
    public Prompt lastPrompt() {
        return prompts.getLast();
    }

    /** 意图识别（结构化输出）调用收到的 Prompt。 */
    public List<Prompt> classificationPrompts() {
        return prompts.stream().filter(p -> isStructured(p) && !isAgentStep(p)).toList();
    }

    /** 回答调用收到的 Prompt（第 6 章起不含追问改写和摘要）。 */
    public List<Prompt> answerPrompts() {
        return prompts.stream()
                .filter(p -> !isStructured(p) && !isCondense(p) && !isSummary(p) && !isAgentStep(p))
                .toList();
    }

    /** 回答调用的 Prompt 里注册了哪些工具（工具名）；没注册时为空。 */
    public static List<String> toolNames(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolCallbacks() != null) {
            return options.getToolCallbacks().stream()
                    .map(ToolCallback::getToolDefinition)
                    .map(d -> d.name())
                    .toList();
        }
        return List.of();
    }

    /** Prompt 里的工具结果消息（role=tool）的内容，即 Spring AI 执行工具后交回给模型的 JSON。 */
    public static List<String> toolResponses(Prompt prompt) {
        List<String> responses = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            if (message instanceof ToolResponseMessage tool) {
                tool.getResponses().forEach(r -> responses.add(r.responseData()));
            }
        }
        return responses;
    }

    /** Prompt 里全部消息的文本（系统、用户、助手、工具结果），用来断言「某段内容有没有发给模型」。 */
    public static String allText(Prompt prompt) {
        StringBuilder text = new StringBuilder();
        for (Message message : prompt.getInstructions()) {
            text.append(message.getText()).append('\n');
            if (message instanceof AssistantMessage assistant) {
                assistant.getToolCalls().forEach(c -> text.append(c.arguments()).append('\n'));
            }
        }
        toolResponses(prompt).forEach(r -> text.append(r).append('\n'));
        if (prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolCallbacks() != null) {
            // 工具定义（名字、说明、参数 Schema）也会发给模型
            options.getToolCallbacks()
                    .forEach(c -> text.append(c.getToolDefinition()).append('\n'));
        }
        return text.toString();
    }

    /** 阻塞调用：先记下 Prompt，再按「结构化输出调用 / 回答调用」分别取预设。 */
    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        if (isAgentStep(prompt)) {
            if (actionFailure != null) {
                throw actionFailure;
            }
            List<String> outputs = actions;
            return response(outputs.get(Math.min(actionCalls++, outputs.size() - 1)));
        }
        if (isStructured(prompt)) {
            if (classificationFailure != null) {
                throw classificationFailure;
            }
            List<String> outputs = classifications;
            // 第 n 次调用取第 n 个预设，用完重复最后一个：用来模拟「第一次输出乱码、重试后才正常」
            int index = Math.min(classificationCalls++, outputs.size() - 1);
            return response(outputs.get(index));
        }
        if (isCondense(prompt)) {
            if (condenseFailure != null) {
                throw condenseFailure;
            }
            return response(condensed);
        }
        if (isSummary(prompt)) {
            return response(summary);
        }
        if (failure != null) {
            throw failure;
        }
        if (wantsToolCall(prompt)) {
            return toolCallResponse();
        }
        return response(reply);
    }

    /** 预设了工具调用、注册了工具、而且对话里还没有工具结果：这一轮返回调用意图。 */
    private boolean wantsToolCall(Prompt prompt) {
        return !toolCalls.isEmpty()
                && !toolNames(prompt).isEmpty()
                && toolResponses(prompt).isEmpty();
    }

    /** 模型的「调用意图」：AssistantMessage 不带文字，带若干 ToolCall（id、类型、工具名、JSON 参数）。 */
    private ChatResponse toolCallResponse() {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.copyOf(toolCalls))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    /**
     * 流式调用：每个片段包成一个 {@link ChatResponse}，和真实模型逐段推送的形状一样。设置了 {@link #willFail} 时，片段发完再以异常结束，
     * 模拟「流到一半模型挂了」。
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        prompts.add(prompt);
        if (failure == null && wantsToolCall(prompt)) {
            return Flux.just(toolCallResponse());
        }
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

    /** 把一段文本包成模型响应：一个 Generation，内容是 AssistantMessage（模型说的话）。 */
    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
