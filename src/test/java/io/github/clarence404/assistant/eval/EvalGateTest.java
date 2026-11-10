package io.github.clarence404.assistant.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.guard.InjectionFilter;
import io.github.clarence404.assistant.mall.StubMallApi;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 评测门禁（第 9 章，CI 每次都跑）：不连模型，用录下来的真实模型输出（qwen2.5:1.5b + bge-m3，{@code eval/recording-qwen2.5-1.5b.json}）
 * 回放整份评测集，再和基线（{@code eval/baseline-qwen2.5-1.5b.json}）比；有退化，构建就失败。
 *
 * <p>它防的是「代码改坏了」：解析模型输出、路由、检索、注入过滤、引用校验、Agent 的提议关……任何一处改动让原本对的用例变错，这里就红。
 * 它<b>不</b>防「换了模型 / 改了提示词之后变差」：提示词一变，指纹就对不上，回放找不到录制——这时门禁也会红，并提示重录；重录要连真实模型
 * （{@link EvalIT} 加 {@code EVAL_RECORD=true}），跑完看报告、和基线比，确认没有退化再更新基线，这一步是人做决定。
 *
 * <p>报告写到 {@code target/eval/report-replay.md}，CI 把它贴进运行摘要。
 */
class EvalGateTest {

    static final String MODEL = "qwen2.5:1.5b";

    @Test
    void replayedEvalDoesNotRegressAgainstBaseline(@TempDir Path knowledge) throws Exception {
        EvalRecording.Recording recording = EvalRecording.read(MODEL);
        EvalReport baseline = EvalRecording.readClasspath(EvalRecording.fileName("baseline", MODEL), EvalReport.class);
        assertThat(recording).as("录制文件 eval/recording-qwen2.5-1.5b.json").isNotNull();
        assertThat(baseline).as("基线文件 eval/baseline-qwen2.5-1.5b.json").isNotNull();
        EvalSet set = EvalFixtures.evalSet();
        assertThat(recording.evalSetVersion())
                .as("评测集改过了（版本 %s，录制时 %s）：用 EvalIT 重新录制并更新基线", set.version(), recording.evalSetVersion())
                .isEqualTo(set.version());

        EvalRecording.ReplayChatModel chat = new EvalRecording.ReplayChatModel(recording.calls());
        EvalRecording.ReplayEmbeddingModel vectors = new EvalRecording.ReplayEmbeddingModel(recording.embeddings());
        EvalReport report;
        try (StubMallApi mall = StubMallApi.start()) {
            EvalTarget target = EvalFixtures.target(
                    chat, vectors, recording.embeddingModel(), mall, knowledge, InjectionFilter.standard());
            report = new EvalRunner(target).run(set, "replay:" + MODEL);
        }
        EvalComparison comparison = EvalComparison.compare(baseline, report, EvalComparison.Thresholds.defaults());

        Path out = Path.of("target/eval");
        Files.createDirectories(out);
        Files.writeString(
                out.resolve("report-replay.md"),
                report.toMarkdown() + "\n" + comparison.describe() + "\n",
                StandardCharsets.UTF_8);

        assertThat(chat.misses)
                .as("录制里找不到这些调用：提示词、检索结果或工具结果的格式变了，用 EvalIT 加 EVAL_RECORD=true 重新录制")
                .isEmpty();
        assertThat(comparison.passed()).as(comparison.describe()).isTrue();
        // 回放是确定的：代码没改坏时，每一条的结果都和基线一样
        assertThat(comparison.newlyFailing()).as(comparison.describe()).isEmpty();
    }
}
