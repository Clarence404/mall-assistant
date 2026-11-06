package io.github.clarence404.assistant.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * OpenAI Codex CLI（{@code codex exec}）的参数与输出格式，按 0.166.0 的 {@code codex exec --help} 与 {@code --json} 事件验证。
 *
 * <p>Codex 是编码代理，没有「关掉全部工具」的单个开关，这里能收紧的都收紧了：
 *
 * <ul>
 *   <li>{@code --sandbox read-only}：模型生成的命令只能在只读沙箱里跑（不能写文件、不能联网）
 *   <li>{@code --disable shell_tool / unified_exec / …}：关掉执行命令、子代理、插件、应用、浏览器、图片等功能；{@code -c web_search="disabled"}
 *       关掉联网搜索
 *   <li>{@code --ignore-user-config --ignore-rules}：不加载 {@code ~/.codex/config.toml}（其中的 MCP 服务、模型提供方）与 execpolicy
 *       规则；登录凭据仍从 CODEX_HOME 读取
 *   <li>{@code --ephemeral}：会话不写盘；{@code --skip-git-repo-check} + {@code -C <空临时目录>}：不在任何仓库里运行
 * </ul>
 *
 * <p>即便如此，只读沙箱仍允许读取本机文件，所以 codex-cli profile 只适合本机学习，不要把服务暴露给别人用。
 *
 * <p>系统提示词写成文件，用 {@code -c model_instructions_file="<文件>"} 替换掉 Codex 自带的编码代理指令（输入 token 从约 1.7 万降到约 7 千，
 * 剩下的是 Codex 固定附带的环境与工具说明），用户消息走 stdin。结构化输出用 {@code --output-schema <文件>}，最终的 agent 消息就是 JSON
 * 文本。{@code --json} 只在一条 agent 消息完整生成后才输出它，所以流式接口是整段推送。
 *
 * <p>架构层：命令行模型。不属于某一章的练习，是第 2 章之后加的可选扩展（{@code claude-cli} / {@code codex-cli} profile 才启用）。
 * 上游：{@link CliChatModel}（经 {@link CliFlavor#CODEX} 选中）。
 */
public class CodexCliDialect implements CliDialect {

    /** 关掉的功能（{@code codex features list} 里的名字）：只留下「回答文字」这一件事。 */
    static final List<String> DISABLED_FEATURES = List.of(
            "shell_tool",
            "unified_exec",
            "multi_agent",
            "apps",
            "plugins",
            "image_generation",
            "view_image",
            "browser_use",
            "computer_use",
            "hooks");

    @Override
    public String name() {
        return "codex";
    }

    /**
     * 拼 codex 的参数，安全相关的含义见类注释。阻塞与流式用同一套参数，区别只在怎么读输出。
     */
    @Override
    public List<String> arguments(CliRequest request, CliWorkspace workspace, List<String> extraArgs)
            throws IOException {
        List<String> args = new ArrayList<>();
        // exec：非交互执行一次；--json：stdout 输出 JSONL 事件（每行一个 JSON），方便程序解析
        args.addAll(List.of("exec", "--json"));
        args.addAll(List.of("--sandbox", "read-only"));
        args.add("--skip-git-repo-check");
        args.add("--ephemeral");
        args.add("--ignore-user-config");
        args.add("--ignore-rules");
        // 不输出 ANSI 颜色控制字符，免得混进要解析的文本
        args.addAll(List.of("--color", "never"));
        args.addAll(List.of("-C", workspace.cwd().toAbsolutePath().toString()));
        for (String feature : DISABLED_FEATURES) {
            args.addAll(List.of("--disable", feature));
        }
        args.addAll(List.of("-c", "web_search=\"disabled\""));
        if (request.hasSystemPrompt()) {
            Path instructions = workspace.write("system-prompt.txt", request.systemPrompt());
            args.addAll(List.of("-c", "model_instructions_file=" + tomlString(instructions.toString())));
        }
        if (request.model() != null) {
            args.addAll(List.of("-m", request.model()));
        }
        if (request.hasOutputSchema()) {
            // 结构化输出：Schema 写成文件交给 codex，最终的 agent 消息就是符合 Schema 的 JSON 文本
            args.addAll(List.of(
                    "--output-schema",
                    workspace
                            .write("output-schema.json", request.outputSchema())
                            .toString()));
        }
        args.addAll(extraArgs);
        args.add("-"); // 指令从 stdin 读
        return args;
    }

    /** stdin 只写对话内容；系统提示词已经通过 model_instructions_file 传了。 */
    @Override
    public String stdin(CliRequest request) {
        return request.conversation();
    }

    /** {@code -c} 的值按 TOML 解析：路径写成带转义的基本字符串（Windows 路径里的反斜杠要转义）。 */
    static String tomlString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * 解析完整的 JSONL 输出：取最后一条 agent 消息作为回答（codex 可能先输出几条中间说明），token 用量取自 turn.completed。
     * codex 的事件里没有模型名和费用，这两项为 null。
     *
     * @throws CliExecutionException 没有完成事件也没有消息、没有 agent 消息、或 turn.failed
     */
    @Override
    public CliReply parseReply(String stdout) {
        CodexEvents events = new CodexEvents();
        for (String line : stdout.split("\\R")) {
            events.onLine(line);
        }
        events.requireCompleted();
        if (events.messages.isEmpty()) {
            throw new CliExecutionException("codex 的输出里没有 agent 消息：" + ClaudeCliDialect.abbreviate(stdout));
        }
        return new CliReply(events.messages.getLast(), null, events.inputTokens, events.outputTokens, null);
    }

    /**
     * 流式解析：每行交给 {@link CodexEvents}，这一行新增了几条 agent 消息就发几段文本。因为 codex 只在消息完整生成后才输出，
     * 每段都是一整条消息，不是逐字增量。
     */
    @Override
    public StreamParser streamParser() {
        CodexEvents events = new CodexEvents();
        return new StreamParser() {

            @Override
            public List<String> onLine(String line) {
                int before = events.messages.size();
                events.onLine(line);
                List<String> fresh = events.messages.subList(before, events.messages.size());
                if (fresh.isEmpty()) {
                    return List.of();
                }
                // 多条 agent 消息之间空一行
                List<String> out = new ArrayList<>();
                for (int i = 0; i < fresh.size(); i++) {
                    out.add(before + i == 0 ? fresh.get(i) : "\n\n" + fresh.get(i));
                }
                return out;
            }

            @Override
            public List<String> onEnd() {
                events.requireCompleted();
                return List.of();
            }
        };
    }

    /**
     * 容错地收集 {@code --json} 事件：{@code item.completed}（item.type 为 agent_message）里的 text 是回答，{@code turn.completed} 带
     * usage，{@code turn.failed} 表示失败；type 为 error 的事件是重连之类的提示，不算失败。也兼容旧版的 {@code {"msg":{"type":"agent_message"}}}。
     */
    static final class CodexEvents {

        private final List<String> messages = new ArrayList<>();
        private boolean completed;
        private Integer inputTokens;
        private Integer outputTokens;

        void onLine(String line) {
            JsonNode node = ClaudeCliDialect.parse(line);
            if (node == null) {
                return;
            }
            String type = node.path("type").asString();
            switch (type) {
                case "item.completed" -> {
                    JsonNode item = node.path("item");
                    String itemType = item.path("type").asString();
                    if ("agent_message".equals(itemType) || "assistant_message".equals(itemType)) {
                        messages.add(item.path("text").asString(""));
                    }
                }
                case "turn.completed" -> {
                    completed = true;
                    JsonNode usage = node.path("usage");
                    if (usage.has("input_tokens")) {
                        inputTokens = usage.path("input_tokens").asInt();
                    }
                    if (usage.has("output_tokens")) {
                        outputTokens = usage.path("output_tokens").asInt();
                    }
                }
                case "turn.failed" ->
                    throw new CliExecutionException("codex 报告失败："
                            + ClaudeCliDialect.abbreviate(
                                    node.path("error").path("message").asString("")));
                default -> {
                    JsonNode msg = node.path("msg");
                    if ("agent_message".equals(msg.path("type").asString())) {
                        messages.add(msg.path("message").asString(""));
                        completed = true;
                    }
                }
            }
        }

        /** 结束时检查：既没见到完成事件、也没收到任何消息，说明输出被截断或进程异常退出。 */
        void requireCompleted() {
            if (!completed && messages.isEmpty()) {
                throw new CliExecutionException("codex 的输出没有 turn.completed 事件就结束了");
            }
        }
    }
}
