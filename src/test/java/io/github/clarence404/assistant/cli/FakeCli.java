package io.github.clarence404.assistant.cli;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 假的命令行工具：测试里用 {@code java -cp <test-classes> io.github.clarence404.assistant.cli.FakeCli <参数>} 代替真实的 claude /
 * codex（CI 上两者都没有）。只依赖 JDK，不依赖测试类路径上的其他库。
 *
 * <p>参数以 {@code exec} 开头时模拟 codex，否则模拟 claude；按参数决定输出格式（json / stream-json / codex 的 JSONL）。行为由环境变量控制：
 *
 * <ul>
 *   <li>{@code FAKE_CLI_MODE}：{@code ok}（默认）、{@code fail}（stderr 写错误、退出码 3）、{@code garbage}（输出不是 JSON）、{@code hang}
 *       （再起一个子进程，然后两个都一直不退出）、{@code error-result}（工具报告失败）
 *   <li>{@code FAKE_CLI_REPLY}：回答文本，默认 {@code 收到：<stdin 内容>}
 *   <li>{@code FAKE_CLI_STRUCTURED}：结构化输出（带 Schema 参数时）返回的 JSON
 *   <li>{@code FAKE_CLI_RECORD}：记录文件前缀，写下 {@code .args}（每行一个 URL 编码的参数）、{@code .stdin}、{@code .system}、
 *       {@code .schema}、{@code .cwd}（工作目录及其中的文件数）、{@code .pids}
 * </ul>
 */
public final class FakeCli {

    static final String DEFAULT_STRUCTURED =
            "{\"intent\":\"ORDER_STATUS\",\"confidence\":0.9,\"orderNo\":\"1849203385720832512\",\"skuId\":null}";

    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    private static final PrintStream ERR =
            new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);

    private FakeCli() {}

    public static void main(String[] args) throws Exception {
        String mode = env("FAKE_CLI_MODE", "ok");
        if ("hang-child".equals(mode)) {
            Thread.sleep(Long.MAX_VALUE);
            return;
        }
        List<String> argList = List.of(args);
        String stdin = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        record(argList, stdin);
        switch (mode) {
            case "fail" -> {
                ERR.println("boom: not logged in");
                System.exit(3);
            }
            case "garbage" -> OUT.println("this is not json at all");
            case "hang" -> hang();
            default -> {
                boolean codex = !argList.isEmpty() && "exec".equals(argList.getFirst());
                String reply = env("FAKE_CLI_REPLY", "收到：" + stdin);
                boolean errorResult = "error-result".equals(mode);
                if (codex) {
                    codex(argList, reply, errorResult);
                } else {
                    claude(argList, reply, errorResult);
                }
            }
        }
    }

    private static void claude(List<String> args, String reply, boolean errorResult) {
        String structured = args.contains("--json-schema") ? env("FAKE_CLI_STRUCTURED", DEFAULT_STRUCTURED) : null;
        String result = "{\"type\":\"result\",\"subtype\":\"" + (errorResult ? "error_during_execution" : "success")
                + "\",\"is_error\":" + errorResult + ",\"result\":" + quote(structured != null ? "" : reply)
                + (structured != null ? ",\"structured_output\":" + structured : "")
                + ",\"total_cost_usd\":0.0012,\"usage\":{\"input_tokens\":12,\"cache_read_input_tokens\":3,"
                + "\"output_tokens\":34},\"modelUsage\":{\"claude-fake-1\":{}}}";
        if (!"stream-json".equals(valueOf(args, "--output-format"))) {
            OUT.println(result);
            return;
        }
        OUT.println("{\"type\":\"system\",\"subtype\":\"init\",\"tools\":[],\"mcp_servers\":[]}");
        OUT.println("not json: a stray log line");
        OUT.println(delta("thinking_delta", "thinking", "先想一想"));
        for (String chunk : chunks(reply)) {
            OUT.println(delta("text_delta", "text", chunk));
        }
        OUT.println("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":" + quote(reply)
                + "}]}}");
        OUT.println(result);
    }

    private static void codex(List<String> args, String reply, boolean errorResult) throws IOException {
        OUT.println("{\"type\":\"thread.started\",\"thread_id\":\"fake\"}");
        OUT.println("{\"type\":\"turn.started\"}");
        OUT.println("{\"type\":\"error\",\"message\":\"Reconnecting... 1/5\"}");
        if (errorResult) {
            OUT.println("{\"type\":\"turn.failed\",\"error\":{\"message\":\"401 Unauthorized\"}}");
            System.exit(1);
        }
        OUT.println(
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\",\"type\":\"reasoning\",\"text\":\"想一想\"}}");
        String text = args.contains("--output-schema") ? env("FAKE_CLI_STRUCTURED", DEFAULT_STRUCTURED) : reply;
        OUT.println("{\"type\":\"item.completed\",\"item\":{\"id\":\"item_1\",\"type\":\"agent_message\",\"text\":"
                + quote(text) + "}}");
        OUT.println("{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":20,\"output_tokens\":7}}");
    }

    private static void hang() throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder child = new ProcessBuilder(
                        java, "-cp", System.getProperty("java.class.path"), FakeCli.class.getName())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        child.environment().put("FAKE_CLI_MODE", "hang-child");
        Process process = child.start();
        String record = System.getenv("FAKE_CLI_RECORD");
        if (record != null) {
            Files.writeString(Path.of(record + ".pids"), ProcessHandle.current().pid() + "\n" + process.pid() + "\n");
        }
        OUT.println("{\"type\":\"system\",\"subtype\":\"init\"}");
        Thread.sleep(Long.MAX_VALUE);
    }

    private static void record(List<String> args, String stdin) throws IOException {
        String record = System.getenv("FAKE_CLI_RECORD");
        if (record == null) {
            return;
        }
        StringBuilder encoded = new StringBuilder();
        for (String arg : args) {
            encoded.append(URLEncoder.encode(arg, StandardCharsets.UTF_8)).append('\n');
        }
        Files.writeString(Path.of(record + ".args"), encoded, StandardCharsets.UTF_8);
        Files.writeString(Path.of(record + ".stdin"), stdin, StandardCharsets.UTF_8);
        copy(valueOf(args, "--system-prompt-file"), record + ".system");
        for (String arg : args) {
            if (arg.startsWith("model_instructions_file=\"")) {
                // codex 的 -c model_instructions_file="<路径>"（TOML 字符串）
                String path = arg.substring("model_instructions_file=\"".length(), arg.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
                copy(path, record + ".system");
            }
        }
        copy(valueOf(args, "--output-schema"), record + ".schema");
        Path cwd = Path.of("").toAbsolutePath();
        try (Stream<Path> entries = Files.list(cwd)) {
            Files.writeString(Path.of(record + ".cwd"), cwd + "\n" + entries.count() + "\n", StandardCharsets.UTF_8);
        }
    }

    private static void copy(String source, String target) throws IOException {
        if (source != null) {
            Files.writeString(
                    Path.of(target), Files.readString(Path.of(source), StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }
    }

    private static String valueOf(List<String> args, String flag) {
        int index = args.indexOf(flag);
        return index >= 0 && index + 1 < args.size() ? args.get(index + 1) : null;
    }

    private static List<String> chunks(String text) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += 3) {
            chunks.add(text.substring(i, Math.min(text.length(), i + 3)));
        }
        return chunks;
    }

    private static String delta(String deltaType, String field, String text) {
        return "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\""
                + deltaType + "\",\"" + field + "\":" + quote(text) + "}}}";
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null ? defaultValue : value;
    }

    /** 最小的 JSON 字符串转义。 */
    static String quote(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
