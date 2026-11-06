package io.github.clarence404.assistant.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 两种命令行工具的参数拼装（重点是安全参数）与输出解析，不起进程。 */
class CliDialectTest {

    private static final String SCHEMA = """
            {
              "type" : "object",
              "properties" : { "intent" : { "type" : "string" } }
            }""";

    @TempDir
    Path tmp;

    @Test
    void claudeRunsAsPureTextModel() throws Exception {
        try (CliWorkspace workspace = CliWorkspace.create(tmp)) {
            List<String> args = new ClaudeCliDialect()
                    .arguments(
                            new CliRequest("你是客服", "订单到哪了", null, "haiku", false),
                            workspace,
                            List.of("--max-budget-usd", "0.05"));

            assertThat(args).startsWith("-p", "--output-format", "json");
            assertThat(args).containsSubsequence("--model", "haiku");
            assertThat(args).as("禁用全部内置工具").containsSubsequence("--tools", "");
            assertThat(args)
                    .as("只用空的 MCP 配置")
                    .containsSubsequence("--strict-mcp-config", "--mcp-config", "{\"mcpServers\":{}}");
            assertThat(args).as("不加载任何 settings").containsSubsequence("--setting-sources", "");
            assertThat(args).contains("--no-session-persistence", "--disable-slash-commands");
            assertThat(args).containsSubsequence("--permission-mode", "dontAsk");
            assertThat(args)
                    .doesNotContain(
                            "--dangerously-skip-permissions", "--allow-dangerously-skip-permissions", "--add-dir");
            assertThat(args).as("用户消息走 stdin，不进命令行").doesNotContain("订单到哪了");
            assertThat(args).endsWith("--max-budget-usd", "0.05");

            Path systemFile = Path.of(args.get(args.indexOf("--system-prompt-file") + 1));
            assertThat(Files.readString(systemFile, StandardCharsets.UTF_8)).isEqualTo("你是客服");
            assertThat(systemFile.getParent()).as("输入文件不放在工作目录里").isEqualTo(workspace.root());
            try (var entries = Files.list(workspace.cwd())) {
                assertThat(entries).as("工作目录是空的").isEmpty();
            }
        }
    }

    @Test
    void claudeStreamingAndSchemaFlags() throws Exception {
        try (CliWorkspace workspace = CliWorkspace.create(tmp)) {
            List<String> args = new ClaudeCliDialect()
                    .arguments(new CliRequest("", "hi", SCHEMA, null, true), workspace, List.of());

            assertThat(args)
                    .containsSubsequence("--output-format", "stream-json", "--verbose", "--include-partial-messages");
            assertThat(args).doesNotContain("--model", "--system-prompt-file");
            assertThat(args.get(args.indexOf("--json-schema") + 1))
                    .as("Schema 压成一行")
                    .isEqualTo("{\"type\":\"object\",\"properties\":{\"intent\":{\"type\":\"string\"}}}");
        }
    }

    @Test
    void codexRunsReadOnlyWithoutToolsOrUserConfig() throws Exception {
        try (CliWorkspace workspace = CliWorkspace.create(tmp)) {
            CodexCliDialect dialect = new CodexCliDialect();
            List<String> args = dialect.arguments(
                    new CliRequest("你是客服", "订单到哪了", SCHEMA, "gpt-5-mini", false), workspace, List.of());

            assertThat(args).startsWith("exec", "--json");
            assertThat(args).containsSubsequence("--sandbox", "read-only");
            assertThat(args).contains("--skip-git-repo-check", "--ephemeral", "--ignore-user-config", "--ignore-rules");
            assertThat(args).containsSubsequence("--disable", "shell_tool");
            assertThat(args).containsSubsequence("--disable", "unified_exec");
            assertThat(args).containsSubsequence("-c", "web_search=\"disabled\"");
            assertThat(args)
                    .containsSubsequence("-C", workspace.cwd().toAbsolutePath().toString());
            assertThat(args).containsSubsequence("-m", "gpt-5-mini");
            assertThat(args)
                    .doesNotContain(
                            "--dangerously-bypass-approvals-and-sandbox",
                            "workspace-write",
                            "danger-full-access",
                            "--add-dir");
            assertThat(args).endsWith("-");
            Path schemaFile = Path.of(args.get(args.indexOf("--output-schema") + 1));
            assertThat(Files.readString(schemaFile, StandardCharsets.UTF_8)).isEqualTo(SCHEMA);

            assertThat(dialect.stdin(new CliRequest("你是客服", "订单到哪了", null, null, false)))
                    .as("codex 没有系统提示词参数，拼在 stdin 前面")
                    .startsWith("<system>\n你是客服\n</system>")
                    .endsWith("订单到哪了");
        }
    }

    @Test
    void claudeReplyPrefersStructuredOutputAndReadsUsage() {
        CliReply reply = new ClaudeCliDialect().parseReply("""
                        {"type":"result","subtype":"success","is_error":false,"result":"","total_cost_usd":0.006,\
                        "usage":{"input_tokens":100,"cache_read_input_tokens":20,"output_tokens":30},\
                        "modelUsage":{"claude-haiku-4-5":{}},"structured_output":{"intent":"CHITCHAT","confidence":0.9}}""");

        assertThat(reply.text()).isEqualTo("{\"intent\":\"CHITCHAT\",\"confidence\":0.9}");
        assertThat(reply.inputTokens()).isEqualTo(120);
        assertThat(reply.outputTokens()).isEqualTo(30);
        assertThat(reply.costUsd()).isEqualTo(0.006);
        assertThat(reply.model()).isEqualTo("claude-haiku-4-5");
    }

    @Test
    void claudeErrorsAndGarbageAreExecutionFailures() {
        ClaudeCliDialect dialect = new ClaudeCliDialect();
        assertThatThrownBy(() -> dialect.parseReply("Error: not logged in")).isInstanceOf(CliExecutionException.class);
        assertThatThrownBy(
                        () -> dialect.parseReply(
                                "{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,\"result\":\"x\"}"))
                .isInstanceOf(CliExecutionException.class)
                .hasMessageContaining("error_during_execution");
        // 结构化输出重试用完：把文本交给意图识别自己的重试与兜底
        assertThat(dialect.parseReply("{\"type\":\"result\",\"subtype\":\"error_max_structured_output_retries\","
                                + "\"is_error\":true,\"result\":\"我觉得是查订单\"}")
                        .text())
                .isEqualTo("我觉得是查订单");
    }

    @Test
    void claudeStreamKeepsOnlyTextDeltas() {
        CliDialect.StreamParser parser = new ClaudeCliDialect().streamParser();

        assertThat(parser.onLine("{\"type\":\"system\",\"subtype\":\"init\"}")).isEmpty();
        assertThat(parser.onLine("garbage")).isEmpty();
        assertThat(
                        parser.onLine(
                                "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"嗯\"}}}"))
                .isEmpty();
        assertThat(
                        parser.onLine(
                                "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"您好\"}}}"))
                .containsExactly("您好");
        assertThat(parser.onLine("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"您好\"}"))
                .as("已经逐段发过，result 不再重复发")
                .isEmpty();
        assertThat(parser.onEnd()).isEmpty();
    }

    @Test
    void claudeStreamWithoutResultFails() {
        CliDialect.StreamParser parser = new ClaudeCliDialect().streamParser();
        parser.onLine("{\"type\":\"system\",\"subtype\":\"init\"}");

        assertThatThrownBy(parser::onEnd).isInstanceOf(CliExecutionException.class);
    }

    @Test
    void codexParsesAgentMessagesAndToleratesReconnectNotices() {
        CliReply reply = new CodexCliDialect().parseReply("""
                        {"type":"thread.started","thread_id":"t1"}
                        {"type":"turn.started"}
                        {"type":"error","message":"Reconnecting... 1/5"}
                        {"type":"item.completed","item":{"id":"item_0","type":"reasoning","text":"想一想"}}
                        {"type":"item.completed","item":{"id":"item_1","type":"agent_message","text":"您好"}}
                        {"type":"turn.completed","usage":{"input_tokens":20,"cached_input_tokens":0,"output_tokens":7}}
                        """);

        assertThat(reply.text()).isEqualTo("您好");
        assertThat(reply.inputTokens()).isEqualTo(20);
        assertThat(reply.outputTokens()).isEqualTo(7);
    }

    @Test
    void codexTurnFailedIsExecutionFailure() {
        assertThatThrownBy(() -> new CodexCliDialect().parseReply("""
                                {"type":"turn.started"}
                                {"type":"turn.failed","error":{"message":"401 Unauthorized"}}
                                """))
                .isInstanceOf(CliExecutionException.class)
                .hasMessageContaining("401");
    }
}
