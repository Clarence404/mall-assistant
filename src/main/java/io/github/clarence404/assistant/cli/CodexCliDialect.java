package io.github.clarence404.assistant.cli;

import java.io.IOException;
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
 * <p>{@code codex exec} 没有系统提示词参数：系统提示词拼在 stdin 内容的前面。结构化输出用 {@code --output-schema <文件>}，最终的 agent
 * 消息就是 JSON 文本。{@code --json} 只在一条 agent 消息完整生成后才输出它，所以流式接口是整段推送。
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

    @Override
    public List<String> arguments(CliRequest request, CliWorkspace workspace, List<String> extraArgs)
            throws IOException {
        List<String> args = new ArrayList<>();
        args.addAll(List.of("exec", "--json"));
        args.addAll(List.of("--sandbox", "read-only"));
        args.add("--skip-git-repo-check");
        args.add("--ephemeral");
        args.add("--ignore-user-config");
        args.add("--ignore-rules");
        args.addAll(List.of("--color", "never"));
        args.addAll(List.of("-C", workspace.cwd().toAbsolutePath().toString()));
        for (String feature : DISABLED_FEATURES) {
            args.addAll(List.of("--disable", feature));
        }
        args.addAll(List.of("-c", "web_search=\"disabled\""));
        if (request.model() != null) {
            args.addAll(List.of("-m", request.model()));
        }
        if (request.hasOutputSchema()) {
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

    @Override
    public String stdin(CliRequest request) {
        if (!request.hasSystemPrompt()) {
            return request.conversation();
        }
        return """
                <system>
                %s
                </system>

                %s""".formatted(request.systemPrompt().strip(), request.conversation());
    }

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

        void requireCompleted() {
            if (!completed && messages.isEmpty()) {
                throw new CliExecutionException("codex 的输出没有 turn.completed 事件就结束了");
            }
        }
    }
}
