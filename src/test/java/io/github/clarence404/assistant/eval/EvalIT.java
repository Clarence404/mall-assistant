package io.github.clarence404.assistant.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.cli.CliChatModel;
import io.github.clarence404.assistant.cli.CliFlavor;
import io.github.clarence404.assistant.cli.CliProperties;
import io.github.clarence404.assistant.guard.InjectionFilter;
import io.github.clarence404.assistant.mall.StubMallApi;
import io.github.clarence404.assistant.ops.TokenEstimator;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 真实模型的评测（第 9 章）：对一个 Ollama 上的模型跑整份评测集，出报告；可选录制（给 CI 的评测门禁回放）、更新基线、请 LLM 裁判打分。
 *
 * <p>只有设置了环境变量 {@code EVAL_OLLAMA_URL} 才运行（同前几章的 {@code RECORD_OLLAMA_URL}），平时的 {@code ./mvnw verify} 和 CI 都跳过：
 *
 * <pre>
 * EVAL_OLLAMA_URL=http://localhost:11434 EVAL_OLLAMA_MODEL=qwen2.5:1.5b \
 *   ./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=EvalIT
 * </pre>
 *
 * <table>
 *   <caption>环境变量</caption>
 *   <tr><td>{@code EVAL_OLLAMA_URL}</td><td>Ollama 地址（只从环境变量读，不写进任何文件）</td></tr>
 *   <tr><td>{@code EVAL_OLLAMA_MODEL}</td><td>被测的对话模型，默认 {@code qwen2.5:1.5b}</td></tr>
 *   <tr><td>{@code EVAL_EMBEDDING_MODEL}</td><td>向量模型，默认 {@code bge-m3}</td></tr>
 *   <tr><td>{@code EVAL_RECORD=true}</td><td>把这次的模型输出和向量写进 {@code src/test/resources/eval/recording-<模型>.json}（评测门禁回放它）</td></tr>
 *   <tr><td>{@code EVAL_UPDATE_BASELINE=true}</td><td>把这次的报告写成基线 {@code baseline-<模型>.json}；不设时有基线就和基线比，退化了测试失败</td></tr>
 *   <tr><td>{@code EVAL_JUDGE}</td><td>LLM 裁判：{@code claude-cli}（本机已登录的 Claude Code，haiku）或 {@code ollama:<模型>}；不设就不请</td></tr>
 * </table>
 *
 * <p>报告写到 {@code target/eval/report-<模型>.json / .md}。另外统计一下 {@link TokenEstimator} 估算的输入 Token 和模型报告的差多少（讲义里的数）。
 */
@EnabledIfEnvironmentVariable(named = "EVAL_OLLAMA_URL", matches = "https?://.+")
class EvalIT {

    private static final Logger log = LoggerFactory.getLogger(EvalIT.class);

    private static final String URL = System.getenv("EVAL_OLLAMA_URL");
    private static final String MODEL = env("EVAL_OLLAMA_MODEL", "qwen2.5:1.5b");
    private static final String EMBEDDING = env("EVAL_EMBEDDING_MODEL", "bge-m3");

    @Test
    void evaluate(@TempDir Path knowledge) throws Exception {
        OllamaApi api = api();
        ChatModel ollama = OllamaChatModel.builder()
                .ollamaApi(api)
                .options(OllamaChatOptions.builder()
                        .model(MODEL)
                        .temperature(0.3) // 同 application-ollama.yml；意图识别、RAG、Agent 在代码里各自设 0
                        .keepAlive("10m")
                        .build())
                .build();
        OllamaEmbeddingModel embedding = OllamaEmbeddingModel.builder()
                .ollamaApi(api)
                .options(OllamaEmbeddingOptions.builder().model(EMBEDDING).build())
                .build();
        EvalRecording.RecordingChatModel chat = new EvalRecording.RecordingChatModel(new Calibrating(ollama));
        EvalRecording.RecordingEmbeddingModel vectors = new EvalRecording.RecordingEmbeddingModel(embedding);

        EvalSet set = EvalFixtures.evalSet();
        EvalReport report;
        try (StubMallApi mall = StubMallApi.start()) {
            EvalTarget target =
                    EvalFixtures.target(chat, vectors, EMBEDDING, mall, knowledge, InjectionFilter.standard());
            report = new EvalRunner(target, judge()).run(set, MODEL);
        }

        Path out = Path.of("target/eval");
        Files.createDirectories(out);
        Files.writeString(
                out.resolve(EvalRecording.fileName("report", MODEL)),
                EvalRecording.JSON.writeValueAsString(report),
                StandardCharsets.UTF_8);
        String markdown = report.toMarkdown() + "\n" + Calibrating.summary() + "\n";
        Files.writeString(
                out.resolve(EvalRecording.fileName("report", MODEL).replace(".json", ".md")),
                markdown,
                StandardCharsets.UTF_8);
        log.info("\n{}", markdown);

        if (Boolean.parseBoolean(System.getenv("EVAL_RECORD"))) {
            Path file = EvalRecording.writeSource(
                    EvalRecording.fileName("recording", MODEL),
                    new EvalRecording.Recording(
                            MODEL,
                            EMBEDDING,
                            LocalDate.now().toString(),
                            set.version(),
                            List.copyOf(chat.calls),
                            new java.util.TreeMap<>(vectors.vectors)));
            log.info("录制已写入 {}（{} 次模型调用、{} 个向量）", file, chat.calls.size(), vectors.vectors.size());
        }
        if (Boolean.parseBoolean(System.getenv("EVAL_UPDATE_BASELINE"))) {
            Path file = EvalRecording.writeSource(EvalRecording.fileName("baseline", MODEL), report);
            log.info("基线已更新：{}", file);
            return;
        }
        EvalReport baseline = EvalRecording.readClasspath(EvalRecording.fileName("baseline", MODEL), EvalReport.class);
        if (baseline != null) {
            EvalComparison comparison = EvalComparison.compare(baseline, report, EvalComparison.Thresholds.defaults());
            log.info(comparison.describe());
            assertThat(comparison.passed()).as(comparison.describe()).isTrue();
        }
    }

    /** 按 {@code EVAL_JUDGE} 请裁判；不设就不请。 */
    private static EvalJudge judge() {
        String judge = System.getenv("EVAL_JUDGE");
        if (judge == null || judge.isBlank()) {
            return null;
        }
        ChatModel model;
        if (judge.equals("claude-cli")) {
            model = new CliChatModel(new CliProperties(
                    CliFlavor.CLAUDE,
                    env("CLAUDE_CLI_COMMAND", "claude"),
                    List.of(),
                    env("CLAUDE_CLI_MODEL", "haiku"),
                    Duration.ofSeconds(120),
                    List.of(),
                    null,
                    Map.of(
                            "CLAUDE_CODE_DISABLE_CLAUDE_MDS", "1",
                            "CLAUDE_CODE_DISABLE_AUTO_MEMORY", "1",
                            "CLAUDE_CODE_DISABLE_THINKING", "1"),
                    true));
        } else if (judge.startsWith("ollama:")) {
            model = OllamaChatModel.builder()
                    .ollamaApi(api())
                    .options(OllamaChatOptions.builder()
                            .model(judge.substring("ollama:".length()))
                            .build())
                    .build();
        } else {
            throw new IllegalArgumentException("EVAL_JUDGE 只能是 claude-cli 或 ollama:<模型>");
        }
        return new ChatClientJudge(ChatClient.builder(model).build());
    }

    /**
     * 连 Ollama 的客户端：手工 new 的 {@code OllamaApi} 不经过 Spring Boot 的 {@code spring.http.clients.*}，默认的 HTTP 客户端读超时只有 10 秒，
     * 第一次调用要把模型加载进内存（CPU 上 bge-m3 要十几秒），直接超时。这里换成 JDK HttpClient、读超时 3 分钟。
     */
    private static OllamaApi api() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofMinutes(3));
        return OllamaApi.builder()
                .baseUrl(URL)
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .build();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 包一层：每次调用比较「按字数估算的输入 Token」和「模型报告的」，评测结束时给出总的偏差。 */
    static final class Calibrating implements ChatModel {

        private static final AtomicLong ESTIMATED = new AtomicLong();
        private static final AtomicLong ACTUAL = new AtomicLong();
        private static final AtomicLong CALLS = new AtomicLong();

        private final ChatModel delegate;

        Calibrating(ChatModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ChatResponse response = delegate.call(prompt);
            Usage usage = response.getMetadata().getUsage();
            if (usage != null && usage.getPromptTokens() != null && usage.getPromptTokens() > 0) {
                ESTIMATED.addAndGet(TokenEstimator.estimate(prompt.getInstructions()));
                ACTUAL.addAndGet(usage.getPromptTokens());
                CALLS.incrementAndGet();
            }
            return response;
        }

        @Override
        public ChatOptions getOptions() {
            return delegate.getOptions();
        }

        static String summary() {
            if (ACTUAL.get() == 0) {
                return "Token 估算：没有可比较的调用";
            }
            return String.format(
                    "Token 估算校准：%d 次调用，输入 Token 估算合计 %d、模型报告 %d，估算 / 实际 = %.2f（不含工具定义与结构化输出的 Schema，它们也算输入）",
                    CALLS.get(), ESTIMATED.get(), ACTUAL.get(), ESTIMATED.get() / (double) ACTUAL.get());
        }
    }
}
