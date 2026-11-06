package io.github.clarence404.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.chat.ChatReply;
import io.github.clarence404.assistant.chat.ChatService;
import io.github.clarence404.assistant.intent.IntentResult;
import io.github.clarence404.assistant.mall.StubMallApi;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 第 3 章：录制回放「模型的工具调用决策」。真实模型（qwen2.5 0.5b / 1.5b）拿到工具清单后的原样输出录在
 * {@code src/test/resources/recordings/tools-*.json}，这里不联网地回放，验证代码能处理真实模型给出的调用意图：工具名、参数格式
 * （模型可能把 SKU 写成数字、参数抄错）、一轮里调多个工具、该调不调。
 *
 * <p>断言的是「执行的工具、参数、模型调用次数、最终回答与录制时参考实现的结果一致」，不断言模型调得对不对：那是模型的事，这里只打印命中率
 * 供参考（交给第 9 章的评估集）。重新录制见 {@link ToolDecisionRecordingIT}。
 *
 * <p>手法：{@link ToolReplayChatModel} 按 Prompt 指纹返回录制的输出，Spring AI 照常执行工具（调假商城 {@link StubMallApi}，结果和录制时一样），
 * 再把工具结果交回给回放模型，于是第二轮的指纹也对得上。
 */
class ToolDecisionReplayTest {

    private static final Logger log = LoggerFactory.getLogger(ToolDecisionReplayTest.class);

    private static StubMallApi mall;

    @BeforeAll
    static void startMall() {
        mall = StubMallApi.start();
    }

    @AfterAll
    static void stopMall() {
        mall.close();
    }

    static Stream<Arguments> recordedCases() {
        return ToolRecordings.recordings().stream()
                .flatMap(recording -> recording.cases().stream()
                        .map(c -> Arguments.of(recording.model(), c.message(), recording, c)));
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @MethodSource("recordedCases")
    void replayedDecisionRunsLikeReferenceImplementation(
            String model, String message, ToolRecordings.Recording recording, ToolRecordings.RecordedCase c) {
        ToolReplayChatModel replay = new ToolReplayChatModel(recording);
        ChatService service = ToolRecordings.chatService(replay, mall.baseUrl());

        ChatReply reply = service.answer(message, IntentResult.unknown(), StubMallApi.TOKEN);

        assertThat(reply.tools())
                .as("执行的工具与参数")
                .map(t -> new ToolRecordings.Invocation(t.name(), t.arguments()))
                .containsExactlyElementsOf(c.invocations());
        assertThat(replay.calls()).as("模型调用次数（含工具调用后的第二轮）").isEqualTo(c.calls().size());
        assertThat(reply.reply()).as("最终回答").isEqualTo(c.reply());
    }

    /** tools-cases.json 加了消息却没重新录制时在这里失败；顺带打印每个模型「调对工具、参数也对」的条数，只作参考。 */
    @Test
    void everyCaseIsRecordedForEveryModel() {
        List<ToolRecordings.Case> cases = ToolRecordings.cases();
        List<ToolRecordings.Recording> recordings = ToolRecordings.recordings();

        assertThat(recordings).as("至少有一份录制").isNotEmpty();
        for (ToolRecordings.Recording recording : recordings) {
            assertThat(recording.cases())
                    .as(recording.model() + " 的录制覆盖 tools-cases.json 的全部消息，否则重新录制")
                    .extracting(ToolRecordings.RecordedCase::message)
                    .containsExactlyElementsOf(
                            cases.stream().map(ToolRecordings.Case::message).toList());
            long correct = recording.cases().stream()
                    .filter(ToolDecisionReplayTest::decidedCorrectly)
                    .count();
            log.info(
                    "{}：工具决策正确 {}/{}（录制于 {}）",
                    recording.model(),
                    correct,
                    recording.cases().size(),
                    recording.recordedAt());
        }
    }

    /** 不该调时一个都没调；该调时第一次调用的工具名和参数与标注一致。 */
    static boolean decidedCorrectly(ToolRecordings.RecordedCase c) {
        if (c.expectedTool() == null) {
            return c.invocations().isEmpty();
        }
        return !c.invocations().isEmpty()
                && c.expectedTool().equals(c.invocations().getFirst().name())
                && ToolRecordings.sameArguments(c.invocations().getFirst().arguments(), c.expectedArguments());
    }
}
