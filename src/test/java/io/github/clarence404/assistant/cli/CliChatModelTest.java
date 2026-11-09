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
import org.springframework.ai.chat.messages.AssistantMessage;
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
 * 失败分类与结构化输出。第 2 章引入。
 *
 * <p>手法：{@link FakeCliSupport} 把要执行的命令配成 {@code java -cp target/test-classes FakeCli}，CliChatModel 照常拼参数、起进程、
 * 写 stdin、解析 stdout；FakeCli 把收到的东西写到 {@code @TempDir} 下的记录文件，测试再读回来断言。行为（正常 / 失败 / 乱码 / 卡死）
 * 由环境变量 {@code FAKE_CLI_MODE} 控制。测试消息故意带换行和双引号，验证它们原样到达子进程。
 */
class CliChatModelTest {

    /** 正常调用的超时：每次都要起一个 JVM，给宽一些；超时相关的测试另外用 3 秒。 */
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

    /** 指向 FakeCli 的 CliChatModel，默认超时、原生结构化输出、无额外环境变量。 */
    private CliChatModel model(CliFlavor flavor, String mode) {
        return model(flavor, mode, TIMEOUT, true, Map.of());
    }

    private CliChatModel model(
            CliFlavor flavor, String mode, Duration timeout, boolean nativeStructured, Map<String, String> env) {
        return new CliChatModel(
                FakeCliSupport.properties(flavor, workDir(), record(), mode, timeout, nativeStructured, env));
    }

    /** 一条系统消息 + 一条用户消息，和 ChatClient 实际发给模型的形状一样。 */
    private static Prompt prompt() {
        return new Prompt(List.of(new SystemMessage(SYSTEM), new UserMessage(MESSAGE)));
    }

    @Test
    void claudeCallPassesSafetyFlagsSystemPromptFileAndChineseStdin() throws Exception {
        ChatResponse response = model(CliFlavor.CLAUDE, "ok").call(prompt());

        assertThat(response.getResult().getOutput().getText()).isEqualTo("收到：" + MESSAGE);
        assertThat(response.getMetadata().getModel()).isEqualTo("claude-fake-1");
        assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(15); // FakeCli 输出 12 + 缓存命中 3
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

    /** codex：参数以 {@code exec --json} 开头、以 {@code -}（从 stdin 读消息）结尾；它没有逐字增量，流式时整段作为一个元素发出。 */
    @Test
    void codexCallAndStream() throws Exception {
        CliChatModel model = model(CliFlavor.CODEX, "ok");

        String reply = model.call(prompt()).getResult().getOutput().getText();

        String expectedStdin = MESSAGE;
        assertThat(reply).isEqualTo("收到：" + expectedStdin);
        assertThat(FakeCliSupport.recorded(record(), ".stdin")).isEqualTo(expectedStdin);
        assertThat(FakeCliSupport.recordedArgs(record()))
                .startsWith("exec", "--json")
                .containsSubsequence("--sandbox", "read-only")
                .containsSubsequence("-c", "web_search=\"disabled\"")
                .endsWith("-");
        assertThat(FakeCliSupport.recorded(record(), ".system"))
                .as("系统提示词经 model_instructions_file 传给 codex")
                .isEqualTo(SYSTEM);

        StepVerifier.create(model.stream(prompt())
                        .map(r -> r.getResult().getOutput().getText()))
                .expectNext("收到：" + expectedStdin)
                .verifyComplete();
        assertNoLeftoverWorkspaces();
    }

    /**
     * 第 6 章：多轮对话。ChatClient 发来的是「系统消息 + 历史（用户、助手交替）+ 这一轮的用户消息」，两种方言都把系统消息走系统提示词文件，
     * 其余按「用户：/ 助手：」拼成一段写进 stdin（{@code claude -p} / {@code codex exec} 一次只收一段输入，没有消息列表）；摘要记忆那条带标签的
     * 用户消息也在 stdin 里，不会混进系统提示词。
     */
    @Test
    void multiTurnHistoryIsRenderedIntoStdinForBothDialects() throws Exception {
        Prompt multiTurn = new Prompt(List.of(
                new SystemMessage(SYSTEM),
                new UserMessage("<早期对话摘要>\n用户问过订单物流\n</早期对话摘要>"),
                new UserMessage("七天无理由退货要什么条件"),
                new AssistantMessage("签收后 7 天内可以申请。"),
                new UserMessage("那运费谁出")));
        String expected = "用户：<早期对话摘要>\n用户问过订单物流\n</早期对话摘要>\n\n用户：七天无理由退货要什么条件\n\n" + "助手：签收后 7 天内可以申请。\n\n用户：那运费谁出";

        for (CliFlavor flavor : List.of(CliFlavor.CLAUDE, CliFlavor.CODEX)) {
            String reply =
                    model(flavor, "ok").call(multiTurn).getResult().getOutput().getText();

            assertThat(FakeCliSupport.recorded(record(), ".stdin"))
                    .as(flavor + " 的 stdin")
                    .isEqualTo(expected);
            assertThat(FakeCliSupport.recorded(record(), ".system"))
                    .as(flavor + " 的系统提示词")
                    .isEqualTo(SYSTEM);
            assertThat(reply).isEqualTo("收到：" + expected);
        }
        assertNoLeftoverWorkspaces();
    }

    /** 走真正的 IntentService：Schema 作为 {@code --json-schema} 参数传给 claude，用户消息里不再拼格式说明。 */
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

    /** codex 的 Schema 写成文件，用 {@code --output-schema <文件>} 传入。 */
    @Test
    void structuredOutputGoesToCodexAsSchemaFile() throws Exception {
        IntentService intents = intentService(model(CliFlavor.CODEX, "ok"));

        assertThat(intents.classify("订单 1849203385720832512 发货了吗").intent()).isEqualTo(Intent.ORDER_STATUS);
        assertThat(FakeCliSupport.recordedArgs(record())).contains("--output-schema");
        assertThat(FakeCliSupport.recorded(record(), ".schema"))
                .contains("\"intent\"")
                .contains("ORDER_STATUS");
    }

    /** 关掉原生结构化输出：不传 Schema 参数，由 Spring AI 把格式说明（含「JSON Schema」字样）拼进用户消息，经 stdin 发出。 */
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

    /** 模型（FakeCli）始终回一句话而不是 JSON：IntentService 重试一次后兜底为 UNKNOWN，不抛异常。 */
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

    /** 进程正常退出但输出里报告失败（claude 的 is_error、codex 的 turn.failed）：同样算调用失败。 */
    @Test
    void toolReportedErrorIsModelUnavailable() {
        assertThatThrownBy(() -> model(CliFlavor.CLAUDE, "error-result").call(prompt()))
                .isInstanceOf(CliExecutionException.class);
        StepVerifier.create(model(CliFlavor.CODEX, "error-result").stream(prompt()))
                .expectError(CliExecutionException.class)
                .verify(TIMEOUT);
    }

    /** hang 模式下 FakeCli 自己再起一个子进程后卡住：超时后两个进程都要被杀掉（只杀父进程，子进程会变成孤儿继续占资源）。 */
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

    /** 命令找不到（没装 claude）：按模型不可用处理，接口返回 503 而不是 500。 */
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

    /** 用指定模型构造真实的 IntentService，测结构化输出这条链路。 */
    private IntentService intentService(CliChatModel model) {
        return new IntentService(ChatClient.builder(model).build(), new ClassPathResource("prompts/intent-v2.st"));
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
