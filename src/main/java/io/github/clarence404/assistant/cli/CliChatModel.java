package io.github.clarence404.assistant.cli;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import reactor.core.publisher.Flux;

/**
 * 把本机已安装、已登录的 AI 编程命令行工具（Claude Code / Codex CLI）当作对话模型：每次调用起一个进程，内容走 stdin，结果从 stdout 解析。
 *
 * <p>只当纯文本模型用：工具、MCP、本地设置与会话持久化都关掉，工作目录是每次新建的空临时目录（具体参数见各 {@link CliDialect}）。
 *
 * <p>结构化输出：默认声明支持原生结构化输出（选项是 {@link StructuredOutputChatOptions}），{@code entity(..., useProviderStructuredOutput())}
 * 时 Spring AI 把 JSON Schema 放进 {@code outputSchema} 选项，这里转成 claude 的 {@code --json-schema} / codex 的
 * {@code --output-schema}；{@code assistant.cli.native-structured-output=false} 时退回到把格式说明拼进用户消息。
 *
 * <p>温度等采样参数命令行工具不支持，忽略；模型名取请求选项里的 model，没有就用 {@code assistant.cli.model}。
 */
public class CliChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(CliChatModel.class);

    private final CliProperties properties;
    private final CliDialect dialect;
    private final CliProcessRunner runner;
    private volatile List<String> resolvedCommand;

    public CliChatModel(CliProperties properties) {
        this(
                properties,
                Objects.requireNonNull(properties.flavor(), "assistant.cli.flavor")
                        .dialect(),
                new CliProcessRunner(properties.env(), properties.timeout()));
    }

    CliChatModel(CliProperties properties, CliDialect dialect, CliProcessRunner runner) {
        this.properties = properties;
        this.dialect = dialect;
        this.runner = runner;
    }

    @Override
    public ChatOptions getOptions() {
        return properties.nativeStructuredOutput()
                ? StructuredOutputChatOptions.builder()
                        .model(properties.model())
                        .build()
                : ChatOptions.builder().model(properties.model()).build();
    }

    /** 工具输出里带模型名就用它；没带时用配置的模型；都没有说明用的是工具自己的默认模型（codex 的 JSON 事件不含模型名）。 */
    private String modelForLog(CliReply reply, CliRequest request) {
        if (reply.model() != null) {
            return reply.model();
        }
        if (request.model() != null && !request.model().isBlank()) {
            return request.model();
        }
        return dialect.name() + " 默认模型";
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        CliRequest request = toRequest(prompt, false);
        long start = System.nanoTime();
        try (CliWorkspace workspace = CliWorkspace.create(properties.workDir())) {
            List<String> command = command(request, workspace);
            String stdout = runner.run(command, workspace.cwd(), dialect.stdin(request));
            CliReply reply = dialect.parseReply(stdout);
            log.info(
                    "{} 调用完成：{} ms，模型 {}，tokens 输入 {} / 输出 {}，费用 {}",
                    dialect.name(),
                    (System.nanoTime() - start) / 1_000_000,
                    modelForLog(reply, request),
                    reply.inputTokens(),
                    reply.outputTokens(),
                    // codex 用 ChatGPT 账号登录时按订阅计费，不报告单次费用；claude 会在结果里给出 total_cost_usd
                    reply.costUsd() != null ? "$" + reply.costUsd() : "工具未报告（订阅计费）");
            return response(reply);
        } catch (CliExecutionException e) {
            log.warn("{} 调用失败：{}", dialect.name(), e.getMessage());
            throw e;
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        CliRequest request = toRequest(prompt, true);
        return Flux.using(
                        () -> CliWorkspace.create(properties.workDir()),
                        workspace -> Flux.defer(() -> {
                            CliDialect.StreamParser parser = dialect.streamParser();
                            List<String> command = command(request, workspace);
                            return runner.lines(command, workspace.cwd(), dialect.stdin(request))
                                    .concatMapIterable(parser::onLine)
                                    .concatWith(Flux.defer(() -> Flux.fromIterable(parser.onEnd())));
                        }),
                        CliWorkspace::close)
                .filter(text -> !text.isEmpty())
                .map(text -> new ChatResponse(List.of(new Generation(new AssistantMessage(text)))))
                .doOnError(CliExecutionException.class, e -> log.warn("{} 流式调用失败：{}", dialect.name(), e.getMessage()));
    }

    private List<String> command(CliRequest request, CliWorkspace workspace) {
        List<String> command = new ArrayList<>(resolvedCommand());
        command.addAll(properties.commandArgs());
        try {
            command.addAll(dialect.arguments(request, workspace, properties.extraArgs()));
        } catch (IOException e) {
            throw new CliExecutionException("准备 " + dialect.name() + " 的输入文件失败", e);
        }
        return command;
    }

    /** 第一次调用时才解析命令：没装 CLI 的机器（如 CI）照样能启动应用、跑测试。 */
    private List<String> resolvedCommand() {
        List<String> current = resolvedCommand;
        if (current == null) {
            current = CliCommandResolver.resolve(properties.commandOrDefault());
            log.info("{} 命令：{}", dialect.name(), current);
            resolvedCommand = current;
        }
        return current;
    }

    CliRequest toRequest(Prompt prompt, boolean stream) {
        String system = prompt.getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.SYSTEM)
                .map(Message::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n\n"));
        List<Message> conversation = prompt.getInstructions().stream()
                .filter(m -> m.getMessageType() != MessageType.SYSTEM)
                .toList();
        String schema =
                prompt.getOptions() instanceof StructuredOutputChatOptions options ? options.getOutputSchema() : null;
        String model = prompt.getOptions() != null && prompt.getOptions().getModel() != null
                ? prompt.getOptions().getModel()
                : properties.model();
        return new CliRequest(system, render(conversation), schema, model, stream);
    }

    /** 只有一条用户消息时原样发送；多轮对话（第 6 章起）按「用户：/ 助手：」拼成一段文本。 */
    static String render(List<Message> conversation) {
        if (conversation.size() == 1 && conversation.getFirst().getMessageType() == MessageType.USER) {
            return Objects.requireNonNullElse(conversation.getFirst().getText(), "");
        }
        return conversation.stream()
                .map(m -> (m.getMessageType() == MessageType.ASSISTANT ? "助手：" : "用户：")
                        + Objects.requireNonNullElse(m.getText(), ""))
                .collect(Collectors.joining("\n\n"));
    }

    private static ChatResponse response(CliReply reply) {
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
        if (reply.model() != null) {
            metadata.model(reply.model());
        }
        if (reply.inputTokens() != null || reply.outputTokens() != null) {
            metadata.usage(new DefaultUsage(reply.inputTokens(), reply.outputTokens()));
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(reply.text()))), metadata.build());
    }
}
