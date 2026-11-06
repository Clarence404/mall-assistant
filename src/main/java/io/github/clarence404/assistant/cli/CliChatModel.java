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
 *
 * <p>架构层：命令行模型。不属于某一章的练习，是第 2 章之后加的可选扩展（{@code claude-cli} / {@code codex-cli} profile 才启用）。
 *
 * <p>ChatModel 是什么：Spring AI 对「一个能对话的模型」的抽象，核心就两个方法——{@link #call(Prompt)} 阻塞地拿到完整回答，
 * {@link #stream(Prompt)} 以 {@code Flux} 逐段拿到回答。OllamaChatModel、OpenAiChatModel 都是它的实现，内部发 HTTP 请求；
 * 这个类也实现它，只是内部改成起一个本机进程。业务代码用的 {@code ChatClient} 只认 ChatModel 接口，所以换成这里不用改任何业务代码。
 *
 * <p>上游：{@code ChatClient}（{@code ChatClientConfig} 构建，{@code ChatService} / {@code IntentService} 使用），由 {@link CliModelConfig}
 * 注册为 Bean。下游：{@link CliCommandResolver}（找到可执行程序）、{@link CliWorkspace}（临时目录）、{@link CliDialect}（参数与解析）、
 * {@link CliProcessRunner}（起进程、超时）。
 *
 * <p>失败：抛 {@link CliExecutionException} / {@link CliTimeoutException}，由上层统一转成 {@code ModelCallException}。这里没有自动重试：
 * {@code spring.ai.retry} 是 OpenAI / Ollama 等厂商实现自己用的，自定义的 ChatModel 不会被它包一层。
 */
public class CliChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(CliChatModel.class);

    private final CliProperties properties;
    private final CliDialect dialect;
    private final CliProcessRunner runner;
    private volatile List<String> resolvedCommand;

    /**
     * 生产用构造器：按 {@code assistant.cli.flavor} 选方言，用配置的环境变量和超时创建进程执行器。
     *
     * @param properties {@code assistant.cli.*} 配置
     * @throws NullPointerException 没有配置 flavor
     */
    public CliChatModel(CliProperties properties) {
        this(
                properties,
                Objects.requireNonNull(properties.flavor(), "assistant.cli.flavor")
                        .dialect(),
                new CliProcessRunner(properties.env(), properties.timeout()));
    }

    /** 测试用构造器：可以换成别的方言或执行器。 */
    CliChatModel(CliProperties properties, CliDialect dialect, CliProcessRunner runner) {
        this.properties = properties;
        this.dialect = dialect;
        this.runner = runner;
    }

    /**
     * 这个模型的默认选项：Spring AI 会把它和每次请求带的选项（如意图识别设的温度）合并后放进 {@link Prompt}。
     *
     * <p>选项的<b>类型</b>决定结构化输出走哪条路：返回 {@link StructuredOutputChatOptions} 表示「我能原生处理 JSON Schema」，
     * {@code entity(..., spec -> spec.useProviderStructuredOutput())} 时 Spring AI 就把 Schema 放进 {@code outputSchema} 选项交给这里，
     * {@link #toRequest} 再取出来交给工具（claude 的 {@code --json-schema}、codex 的 {@code --output-schema}），工具保证输出符合 Schema；
     * 配置 {@code native-structured-output=false} 时返回普通 {@link ChatOptions}，Spring AI 改为在用户消息末尾追加一段「请按这个
     * JSON Schema 输出」的格式说明，能不能做到全靠模型自觉，解析失败时由意图识别的重试与兜底处理。
     *
     * @return 带默认模型名的选项
     */
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

    /**
     * 阻塞调用：起一个进程，等它退出后解析完整的 stdout。
     *
     * <p>步骤：拆出系统提示词 / 对话 / Schema → 新建临时目录 → 拼命令行 → 运行进程（用户消息写进 stdin）→ 方言解析 stdout →
     * 包成 {@link ChatResponse}。临时目录由 try-with-resources 在返回或出错后删除。
     *
     * @param prompt Spring AI 组装好的提示词（消息列表 + 选项）
     * @return 一个 Generation 的回答，元数据里带模型名与 token 用量（工具报告了的话）
     * @throws CliExecutionException 找不到命令、退出码非 0、输出解析不了
     * @throws CliTimeoutException 超过 {@code assistant.cli.timeout}
     */
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

    /**
     * 流式调用：进程 stdout 每输出一行事件就解析一次，有新文本就往下游发一个 {@link ChatResponse}。
     *
     * <p>Reactor 小科普：{@link Flux} 是「0 到 N 个元素的异步序列」，有人订阅（这里是 ChatService → SSE 接口）才真正开始执行。
     *
     * <ul>
     *   <li>{@code Flux.using(创建资源, 用资源生成 Flux, 清理资源)}：每次订阅新建一个临时目录，序列结束、出错或被取消时都会删除它
     *   <li>{@code Flux.defer}：拼命令、起进程推迟到订阅时才做，不订阅就不会启动进程
     *   <li>{@code concatMapIterable(parser::onLine)}：每行输出变成 0～N 段文本，保持顺序
     *   <li>{@code concatWith(Flux.defer(...onEnd()))}：stdout 正常读完后再调 {@code onEnd()}，没见到结束事件就在这里报错
     * </ul>
     *
     * <p>超时有两层：{@link CliProcessRunner} 的看门狗（{@code assistant.cli.timeout}，整次调用），以及 ChatService 上的
     * {@code Flux.timeout}（{@code assistant.chat.stream-timeout}，两段之间的空档）；后者触发时会取消订阅，进而杀掉进程树。
     * 这里的 doOnError 只记日志，异常的归类在 ChatService 的 {@code onErrorMap} 里做。
     *
     * @param prompt Spring AI 组装好的提示词
     * @return 逐段的回答，每个元素只含新增的文本
     */
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

    /**
     * 拼出完整命令行：解析好的程序（可能是 {@code node.exe + 脚本}）+ 配置的 commandArgs + 方言给出的参数。
     *
     * @throws CliExecutionException 往临时目录写系统提示词 / Schema 文件失败
     */
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

    /**
     * 把 Spring AI 的 {@link Prompt} 拆成命令行需要的几部分。
     *
     * <p>Prompt 里是一串带角色的消息：SYSTEM（系统提示词，定人设和规则）、USER（用户说的话）、ASSISTANT（模型之前的回答）。
     * 系统消息合并后单独走系统提示词参数，其余消息拼成文本走 stdin；Schema 只在选项是 {@link StructuredOutputChatOptions} 时才有；
     * temperature 等采样参数命令行工具没有对应参数，直接丢弃（所以意图识别设的温度 0 在这里不起作用）。
     *
     * @param prompt Spring AI 组装好的提示词
     * @param stream 是否流式（决定方言用哪种输出格式）
     * @return 本次调用的内容
     */
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

    /**
     * 只有一条用户消息时原样发送；多轮对话（第 6 章起）按「用户：/ 助手：」拼成一段文本。
     *
     * <p>{@code claude -p} / {@code codex exec} 一次只接收一段输入，没有「消息列表」的概念，所以历史消息只能拼成文本交给它。
     */
    static String render(List<Message> conversation) {
        if (conversation.size() == 1 && conversation.getFirst().getMessageType() == MessageType.USER) {
            return Objects.requireNonNullElse(conversation.getFirst().getText(), "");
        }
        return conversation.stream()
                .map(m -> (m.getMessageType() == MessageType.ASSISTANT ? "助手：" : "用户：")
                        + Objects.requireNonNullElse(m.getText(), ""))
                .collect(Collectors.joining("\n\n"));
    }

    /** 把解析结果包成 Spring AI 的 {@link ChatResponse}：一个 Generation（回答文本），元数据里放模型名和 token 用量（有才放）。 */
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
