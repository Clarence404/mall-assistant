package io.github.clarence404.assistant.cli;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Claude Code（{@code claude -p}）的参数与输出格式，按 2.1.112 验证。
 *
 * <p>只把它当一个纯文本模型用，不让它碰本机的任何东西：
 *
 * <ul>
 *   <li>{@code --tools ""}：禁用全部内置工具（Bash、读写文件、联网……），init 事件里 tools 为空数组
 *   <li>{@code --strict-mcp-config --mcp-config {"mcpServers":{}}}：只用这里给的（空的）MCP 配置，忽略用户与项目里配置的 MCP 服务
 *   <li>{@code --setting-sources ""}：不加载 user / project / local 三级 settings（其中的 hooks、权限规则、插件都不生效）
 *   <li>{@code --disable-slash-commands}：不加载 skills / 斜杠命令
 *   <li>{@code --permission-mode dontAsk}：万一还有工具，没有预先允许的一律拒绝，不会卡在等人确认
 *   <li>{@code --no-session-persistence}：会话不写盘，不能被 resume
 *   <li>工作目录是每次新建的空临时目录；CLAUDE.md 与自动记忆用环境变量关掉（见 application-claude-cli.yml）
 * </ul>
 *
 * <p>系统提示词用 {@code --system-prompt-file} 传文件（替换掉 Claude Code 自带的编码助手提示词），用户消息走 stdin。
 *
 * <p>架构层：命令行模型。不属于某一章的练习，是第 2 章之后加的可选扩展（{@code claude-cli} / {@code codex-cli} profile 才启用）。
 * 上游：{@link CliChatModel}（经 {@link CliFlavor#CLAUDE} 选中）。
 *
 * <p>为什么要关这么多东西：Claude Code 是能读写文件、执行命令的编码代理，默认还会加载你本机的 settings、hooks、插件、MCP 服务和
 * CLAUDE.md。客服场景只需要「输入一段文字、输出一段文字」，多开一样就多一份风险（用户的一句话可能诱导它去执行命令），
 * 也会让你的个人配置混进客服的行为里。这些参数写死在代码里，{@code extra-args} 只能追加，不能去掉它们。
 */
public class ClaudeCliDialect implements CliDialect {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public String name() {
        return "claude";
    }

    /**
     * 拼 claude 的参数。阻塞调用用 {@code --output-format json}（结束时一次输出一个 result 对象）；流式调用用 {@code stream-json}
     * （每行一个事件），{@code --verbose} 是 {@code -p} 模式下使用 stream-json 的要求，{@code --include-partial-messages}
     * 让它在生成过程中就输出逐段的增量，而不是每条消息生成完才输出。
     */
    @Override
    public List<String> arguments(CliRequest request, CliWorkspace workspace, List<String> extraArgs)
            throws IOException {
        List<String> args = new ArrayList<>();
        // -p（print）：非交互模式，读完输入、输出结果就退出
        args.add("-p");
        args.add("--output-format");
        if (request.stream()) {
            args.addAll(List.of("stream-json", "--verbose", "--include-partial-messages"));
        } else {
            args.add("json");
        }
        if (request.model() != null) {
            args.addAll(List.of("--model", request.model()));
        }
        // 以下安全参数含义见类注释
        args.addAll(List.of("--tools", ""));
        args.addAll(List.of("--strict-mcp-config", "--mcp-config", "{\"mcpServers\":{}}"));
        args.addAll(List.of("--setting-sources", ""));
        args.add("--disable-slash-commands");
        args.addAll(List.of("--permission-mode", "dontAsk"));
        args.add("--no-session-persistence");
        if (request.hasSystemPrompt()) {
            args.addAll(List.of(
                    "--system-prompt-file",
                    workspace.write("system-prompt.txt", request.systemPrompt()).toString()));
        }
        if (request.hasOutputSchema()) {
            // 结构化输出：Claude Code 内部加一个 StructuredOutput 工具并校验，结果放在 result 事件的 structured_output 字段
            args.addAll(List.of("--json-schema", compact(request.outputSchema())));
        }
        args.addAll(extraArgs);
        return args;
    }

    /** stdin 只写对话内容；系统提示词已经通过文件参数传了。 */
    @Override
    public String stdin(CliRequest request) {
        return request.conversation();
    }

    /**
     * {@code --output-format json}：stdout 是一个 type 为 result 的 JSON 对象。容错：取最后一个能解析的 result 行。
     *
     * @param stdout 进程的完整输出
     * @return 解析结果
     * @throws CliExecutionException 输出里没有 result 对象，或 result 报告失败
     */
    @Override
    public CliReply parseReply(String stdout) {
        JsonNode result = null;
        for (String line : stdout.split("\\R")) {
            JsonNode node = parse(line);
            if (node != null && "result".equals(node.path("type").asString())) {
                result = node;
            }
        }
        if (result == null) {
            throw new CliExecutionException("claude 的输出里没有 result 对象：" + abbreviate(stdout));
        }
        return toReply(result);
    }

    /**
     * 把 result 对象转成 {@link CliReply}（阻塞与流式共用）。
     *
     * <ul>
     *   <li>{@code is_error=true} 时抛异常；例外是 {@code error_max_structured_output_retries}（工具内部多次没产出合格的结构化结果），
     *       照常返回文本，交给意图识别的解析失败重试与兜底
     *   <li>回答文本：有 {@code structured_output}（用了 {@code --json-schema}）就把它序列化成 JSON 字符串，Spring AI 的
     *       {@code entity()} 再把这段 JSON 转成 Java 对象；否则取 {@code result} 字段的纯文本
     *   <li>输入 token = {@code input_tokens} + 缓存读取 + 缓存写入：开启提示词缓存后，重复的前缀（如系统提示词）单独计数，加起来才是完整输入量
     *   <li>模型名取 {@code modelUsage} 的第一个键
     * </ul>
     *
     * @throws CliExecutionException result 报告失败
     */
    static CliReply toReply(JsonNode result) {
        String subtype = result.path("subtype").asString();
        if (result.path("is_error").asBoolean(false) && !"error_max_structured_output_retries".equals(subtype)) {
            throw new CliExecutionException("claude 报告失败：" + subtype + " "
                    + abbreviate(result.path("result").asString()));
        }
        // 结构化输出重试用完时照常返回文本：解析失败由意图识别的重试与兜底处理
        JsonNode structured = result.path("structured_output");
        String text = structured.isMissingNode() || structured.isNull()
                ? result.path("result").asString("")
                : JSON.writeValueAsString(structured);
        JsonNode usage = result.path("usage");
        Integer input = usage.has("input_tokens")
                ? usage.path("input_tokens").asInt()
                        + usage.path("cache_read_input_tokens").asInt(0)
                        + usage.path("cache_creation_input_tokens").asInt(0)
                : null;
        Integer output =
                usage.has("output_tokens") ? usage.path("output_tokens").asInt() : null;
        Double cost =
                result.has("total_cost_usd") ? result.path("total_cost_usd").asDouble() : null;
        String model =
                result.path("modelUsage").propertyNames().stream().findFirst().orElse(null);
        return new CliReply(text, model, input, output, cost);
    }

    /** 每次流式调用新建一个 {@link ClaudeStreamParser}。 */
    @Override
    public StreamParser streamParser() {
        return new ClaudeStreamParser();
    }

    /**
     * {@code --output-format stream-json --verbose --include-partial-messages}：每行一个事件。增量文本在
     * {@code stream_event / content_block_delta / text_delta} 里（thinking_delta 不输出），最后一个 result 事件表示结束。
     *
     * <p>一行增量事件大致是 {@code {"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"您好"}}}}，
     * 其他事件（system init、完整的 assistant 消息等）一律忽略。result 事件里也有完整回答：前面已经逐段发过就不再发，避免重复。
     */
    static final class ClaudeStreamParser implements StreamParser {

        private boolean emittedText;
        private JsonNode result;

        @Override
        public List<String> onLine(String line) {
            JsonNode node = parse(line);
            if (node == null) {
                return List.of();
            }
            String type = node.path("type").asString();
            if ("stream_event".equals(type)) {
                JsonNode event = node.path("event");
                JsonNode delta = event.path("delta");
                if ("content_block_delta".equals(event.path("type").asString())
                        && "text_delta".equals(delta.path("type").asString())) {
                    String text = delta.path("text").asString("");
                    if (!text.isEmpty()) {
                        emittedText = true;
                        return List.of(text);
                    }
                }
                return List.of();
            }
            if ("result".equals(type)) {
                result = node;
                CliReply reply = toReply(node);
                // 没有逐段输出时（比如关掉了 partial messages），把完整结果一次发出
                if (!emittedText && !reply.text().isEmpty()) {
                    emittedText = true;
                    return List.of(reply.text());
                }
            }
            return List.of();
        }

        /** 进程正常退出但没见到 result 事件，说明输出被截断或工具异常退出，按失败处理。 */
        @Override
        public List<String> onEnd() {
            if (result == null) {
                throw new CliExecutionException("claude 的流式输出没有 result 事件就结束了");
            }
            return List.of();
        }
    }

    /** 把一行解析成 JSON；不以左花括号开头或解析失败（日志、警告之类的杂行）返回 null 直接跳过，不因为一行乱码就整体失败。 */
    static JsonNode parse(String line) {
        String trimmed = line.strip();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            return JSON.readTree(trimmed);
        } catch (JacksonException e) {
            return null;
        }
    }

    /** Schema 压成一行：命令行参数里不要有换行。 */
    static String compact(String json) {
        try {
            return JSON.writeValueAsString(JSON.readTree(json));
        } catch (JacksonException e) {
            return json.replaceAll("\\s*\\R\\s*", " ");
        }
    }

    /** 压成一行并截到 300 字，用于异常消息与日志，避免把整段输出塞进日志。 */
    static String abbreviate(String text) {
        String oneLine = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }
}
