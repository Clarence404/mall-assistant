package io.github.clarence404.assistant.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.error.ModelFailure;
import io.github.clarence404.assistant.intent.Intent;
import io.github.clarence404.assistant.intent.IntentResult;
import io.github.clarence404.assistant.intent.IntentService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 真起进程：用 {@link FakeCli} 代替 claude / codex，验证参数真的传到了子进程（含 Windows 下的引号与空参数）、stdin 的中文、输出解析、超时杀进程树、
 * 失败分类与结构化输出。
 */
class CliChatModelTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String SYSTEM = "你是「示例商城」的客服，只用简体中文回答。";
    private static final String MESSAGE = "我的订单 1849203385720832512 到哪了？\n顺便问下 \"L 码\" 有货吗";

    @TempDir
    Path tmp;

    private Path workDir() {
        return tmp.resolve("work");
    }

    private Path record() {
        return tmp.resolve("record");
    }

    private CliChatModel model(CliFlavor flavor, String mode) {
        return model(flavor, mode, TIMEOUT, true, Map.of());
    }

    private CliChatModel model(
            CliFlavor flavor, String mode, Duration timeout, boolean nativeStructured, Map<String, String> env) {
        return new CliChatModel(
                FakeCliSupport.properties(flavor, workDir(), record(), mode, timeout, nativeStructured, env));
    }

    private static Prompt prompt() {
        return new Prompt(List.of(new SystemMessage(SYSTEM), new UserMessage(MESSAGE)));
    }

    @Test
    void claudeCallPassesSafetyFlagsSystemPromptFileAndChineseStdin() throws Exception {
        ChatResponse response = model(CliFlavor.CLAUDE, "ok").call(prompt());

        assertThat(response.getResult().getOutput().getText()).isEqualTo("收到：" + MESSAGE);
        assertThat(response.getMetadata().getModel()).isEqualTo("claude-fake-1");
        assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(15);
        assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(34);

        List<String> args = FakeCliSupport.recordedArgs(record());
        assertThat(args)
                .as("参数原样到达子进程：空字符串、带引号的 JSON 都没被拆坏")
                .containsSubsequence("--tools", "", "--strict-mcp-config", "--mcp-config", "{\"mcpServers\":{}}")
                .containsSubsequence("--setting-sources", "", "--disable-slash-commands")
                .contains("-p", "--no-session-persistence")
                .containsSubsequence("--model", "haiku");
        assertThat(FakeCliSupport.recorded(record(), ".stdin")).isEqualTo(MESSAGE);
        assertThat(FakeCliSupport.recorded(record(), ".system")).isEqualTo(SYSTEM);
        List<String> cwd = Files.readAllLines(Path.of(record() + ".cwd"));
        assertThat(Path.of(cwd.get(0)).startsWith(workDir().toRealPath())
                        || Path.of(cwd.get(0)).startsWith(workDir()))
                .as("工作目录是 workDir 下新建的临时目录")
                .isTrue();
        assertThat(cwd.get(1)).as("工作目录是空的").isEqualTo("0");
        assertNoLeftoverWorkspaces();
    }

    @Test
    void claudeStreamEmitsTextDeltasInOrderAndCompletes() {
        List<String> parts = model(CliFlavor.CLAUDE, "ok").stream(prompt())
                .map(r -> r.getResult().getOutput().getText())
                .collectList()
                .block(TIMEOUT);

        assertThat(parts).hasSizeGreaterThan(3);
        assertThat(String.join("", parts)).as("只拼接 text_delta，不含思考内容").isEqualTo("收到：" + MESSAGE);
        assertNoLeftoverWorkspaces();
    }

    @Test
    void codexCallAndStream() throws Exception {
        CliChatModel model = model(CliFlavor.CODEX, "ok");

        String reply = model.call(prompt()).getResult().getOutput().getText();

        String expectedStdin = "<system>\n" + SYSTEM + "\n</system>\n\n" + MESSAGE;
        assertThat(reply).isEqualTo("收到：" + expectedStdin);
        assertThat(FakeCliSupport.recorded(record(), ".stdin")).isEqualTo(expectedStdin);
        assertThat(FakeCliSupport.recordedArgs(record()))
                .startsWith("exec", "--json")
                .containsSubsequence("--sandbox", "read-only")
                .containsSubsequence("-c", "web_search=\"disabled\"")
                .endsWith("-");

        StepVerifier.create(model.stream(prompt())
                        .map(r -> r.getResult().getOutput().getText()))
                .expectNext("收到：" + expectedStdin)
                .verifyComplete();
        assertNoLeftoverWorkspaces();
    }

    @Test
    void structuredOutputGoesToClaudeAsJsonSchema() throws Exception {
        IntentService intents = intentService(model(CliFlavor.CLAUDE, "ok"));

        IntentResult result = intents.classify("订单 1849203385720832512 发货了吗");

        assertThat(result).isEqualTo(new IntentResult(Intent.ORDER_STATUS, 0.9, "1849203385720832512", null));
        List<String> args = FakeCliSupport.recordedArgs(record());
        JsonNode schema = JsonMapper.builder().build().readTree(args.get(args.indexOf("--json-schema") + 1));
        assertThat(schema.path("properties").has("intent")).isTrue();
        assertThat(schema.path("properties").has("orderNo")).isTrue();
        assertThat(FakeCliSupport.recorded(record(), ".stdin"))
                .as("原生结构化输出：格式说明不拼进用户消息")
                .doesNotContain("JSON Schema")
                .contains("订单 1849203385720832512 发货了吗");
        assertThat(FakeCliSupport.recorded(record(), ".system")).contains("意图识别");
    }

    @Test
    void structuredOutputGoesToCodexAsSchemaFile() throws Exception {
        IntentService intents = intentService(model(CliFlavor.CODEX, "ok"));

        assertThat(intents.classify("订单 1849203385720832512 发货了吗").intent()).isEqualTo(Intent.ORDER_STATUS);
        assertThat(FakeCliSupport.recordedArgs(record())).contains("--output-schema");
        assertThat(FakeCliSupport.recorded(record(), ".schema"))
                .contains("\"intent\"")
                .contains("ORDER_STATUS");
    }

    @Test
    void withoutNativeStructuredOutputFormatInstructionsGoIntoStdin() throws Exception {
        CliChatModel model = model(
                CliFlavor.CLAUDE,
                "ok",
                TIMEOUT,
                false,
                Map.of(
                        "FAKE_CLI_REPLY",
                        "{\"intent\":\"AFTER_SALES\",\"confidence\":0.8,\"orderNo\":null,\"skuId\":null}"));

        assertThat(intentService(model).classify("杯子碎了怎么退款").intent()).isEqualTo(Intent.AFTER_SALES);
        assertThat(FakeCliSupport.recordedArgs(record())).doesNotContain("--json-schema");
        assertThat(FakeCliSupport.recorded(record(), ".stdin"))
                .contains("杯子碎了怎么退款")
                .contains("JSON Schema");
    }

    @Test
    void unparsableIntentIsRetriedThenUnknown() {
        CliChatModel model = model(CliFlavor.CLAUDE, "ok", TIMEOUT, false, Map.of("FAKE_CLI_REPLY", "我觉得是查订单"));

        assertThat(intentService(model).classify("订单到哪了")).isEqualTo(IntentResult.unknown());
    }

    @Test
    void nonZeroExitIsModelUnavailableWithStderrInMessage() {
        assertThatThrownBy(() -> model(CliFlavor.CLAUDE, "fail").call(prompt()))
                .isInstanceOfSatisfying(CliExecutionException.class, e -> {
                    assertThat(e.getMessage()).contains("退出码 3").contains("boom: not logged in");
                    assertThat(ModelCallException.from(e).failure()).isEqualTo(ModelFailure.MODEL_UNAVAILABLE);
                });
        assertNoLeftoverWorkspaces();
    }

    @Test
    void garbageOutputIsModelUnavailable() {
        assertThatThrownBy(() -> model(CliFlavor.CLAUDE, "garbage").call(prompt()))
                .isInstanceOfSatisfying(
                        CliExecutionException.class,
                        e -> assertThat(ModelCallException.from(e).failure())
                                .isEqualTo(ModelFailure.MODEL_UNAVAILABLE));
        StepVerifier.create(model(CliFlavor.CLAUDE, "garbage").stream(prompt()))
                .expectError(CliExecutionException.class)
                .verify(TIMEOUT);
    }

    @Test
    void toolReportedErrorIsModelUnavailable() {
        assertThatThrownBy(() -> model(CliFlavor.CLAUDE, "error-result").call(prompt()))
                .isInstanceOf(CliExecutionException.class);
        StepVerifier.create(model(CliFlavor.CODEX, "error-result").stream(prompt()))
                .expectError(CliExecutionException.class)
                .verify(TIMEOUT);
    }

    @Test
    void timeoutKillsWholeProcessTree() throws Exception {
        CliChatModel model = model(CliFlavor.CLAUDE, "hang", Duration.ofSeconds(3), true, Map.of());

        assertThatThrownBy(() -> model.call(prompt()))
                .isInstanceOfSatisfying(
                        CliTimeoutException.class,
                        e -> assertThat(ModelCallException.from(e).failure()).isEqualTo(ModelFailure.MODEL_TIMEOUT));

        assertProcessesGone();
        assertNoLeftoverWorkspaces();
    }

    @Test
    void streamTimeoutKillsWholeProcessTree() throws Exception {
        CliChatModel model = model(CliFlavor.CLAUDE, "hang", Duration.ofSeconds(3), true, Map.of());

        StepVerifier.create(model.stream(prompt()))
                .expectErrorSatisfies(
                        e -> assertThat(ModelCallException.from(e).failure()).isEqualTo(ModelFailure.MODEL_TIMEOUT))
                .verify(TIMEOUT);

        assertProcessesGone();
    }

    @Test
    void cancellingStreamKillsProcess() throws Exception {
        CliChatModel model = model(CliFlavor.CLAUDE, "hang", TIMEOUT, true, Map.of());

        // 下游（ChatService 的 Flux#timeout、客户端断开）先放弃：取消订阅也要杀掉进程
        StepVerifier.create(model.stream(prompt()).timeout(Duration.ofSeconds(3)))
                .expectError(java.util.concurrent.TimeoutException.class)
                .verify(TIMEOUT);

        assertProcessesGone();
    }

    @Test
    void missingCommandIsModelUnavailable() {
        CliProperties properties = new CliProperties(
                CliFlavor.CLAUDE, "no-such-cli-xyz", List.of(), null, TIMEOUT, List.of(), workDir(), Map.of(), true);

        assertThatThrownBy(() -> new CliChatModel(properties).call(prompt()))
                .isInstanceOfSatisfying(
                        CliExecutionException.class,
                        e -> assertThat(ModelCallException.from(e).failure())
                                .isEqualTo(ModelFailure.MODEL_UNAVAILABLE));
    }

    private IntentService intentService(CliChatModel model) {
        return new IntentService(ChatClient.builder(model).build(), new ClassPathResource("prompts/intent-v1.st"));
    }

    /** FakeCli 在 hang 模式下记下了自己和子进程的 pid：都应该已经被杀掉。 */
    private void assertProcessesGone() throws Exception {
        Path pids = Path.of(record() + ".pids");
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!Files.exists(pids) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        List<Long> ids = Files.readAllLines(pids).stream()
                .filter(s -> !s.isBlank())
                .map(Long::parseLong)
                .toList();
        assertThat(ids).hasSize(2);
        for (long pid : ids) {
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                    .as("进程 %d 已退出", pid)
                    .isFalse();
        }
    }

    /** 每次调用的临时目录用完即删。 */
    private void assertNoLeftoverWorkspaces() {
        if (!Files.exists(workDir())) {
            return;
        }
        try (Stream<Path> entries = Files.list(workDir())) {
            assertThat(entries).isEmpty();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
