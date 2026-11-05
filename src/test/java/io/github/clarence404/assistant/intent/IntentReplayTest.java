package io.github.clarence404.assistant.intent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;

/**
 * 第 2 章：录制回放。用真实模型录下的原样输出（{@code src/test/resources/recordings/}）驱动 {@link IntentService}，验证代码能处理真实模型的
 * 各种输出：合法 JSON、包了 Markdown 代码块的、标签写错的、需要重试的……
 *
 * <p>断言的是「解析结果与录制时参考实现的结果一致、模型调用次数一致」，不断言模型分类得对不对：那是模型的事，交给评估集（第 9 章），这里只打印准确率供参考。
 * 重新录制见 {@link IntentRecordingIT}。
 */
class IntentReplayTest {

    private static final Logger log = LoggerFactory.getLogger(IntentReplayTest.class);

    static Stream<Arguments> recordedCases() {
        return IntentRecordings.recordings().stream()
                .flatMap(recording -> recording.cases().stream()
                        .map(c -> Arguments.of(recording.model(), c.message(), recording, c)));
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @MethodSource("recordedCases")
    void replayedOutputParsesLikeReferenceImplementation(
            String model, String message, IntentRecordings.Recording recording, IntentRecordings.RecordedCase c) {
        ReplayChatModel replay = new ReplayChatModel(recording);
        IntentService service =
                new IntentService(ChatClient.builder(replay).build(), new ClassPathResource("prompts/intent-v1.st"));

        IntentResult result = service.classify(message);

        assertThat(result).as("解析结果").isEqualTo(c.result());
        assertThat(replay.calls()).as("模型调用次数（含重试）").isEqualTo(c.calls().size());
    }

    @Test
    void everyCaseIsRecordedForEveryModel() {
        List<IntentRecordings.Case> cases = IntentRecordings.cases();
        List<IntentRecordings.Recording> recordings = IntentRecordings.recordings();

        assertThat(recordings).as("至少有一份录制").isNotEmpty();
        for (IntentRecordings.Recording recording : recordings) {
            assertThat(recording.cases())
                    .as(recording.model() + " 的录制覆盖 intent-cases.json 的全部消息，否则重新录制")
                    .extracting(IntentRecordings.RecordedCase::message)
                    .containsExactlyElementsOf(
                            cases.stream().map(IntentRecordings.Case::message).toList());
            long correct = recording.cases().stream()
                    .filter(c -> c.result().intent() == c.expected())
                    .count();
            long retried =
                    recording.cases().stream().filter(c -> c.calls().size() > 1).count();
            log.info(
                    "{}：分类正确 {}/{}，需要重试 {} 条（录制于 {}）",
                    recording.model(),
                    correct,
                    recording.cases().size(),
                    retried,
                    recording.recordedAt());
        }
    }
}
