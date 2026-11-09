package io.github.clarence404.assistant.rag;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.chat.ChatReply;
import io.github.clarence404.assistant.chat.ChatService;
import io.github.clarence404.assistant.intent.IntentResult;
import io.github.clarence404.assistant.prompt.TestPrompts;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 第 5 章：RAG 回答的录制回放。用真实模型录下的原样输出（{@code recordings/rag-<模型>.json}）驱动 ChatService 的 RAG 路径，
 * 验证拼出的提示词和录制时一字不差（指纹对得上），引用校验对真实输出的处理（保留、去掉、标记未引用）和参考实现一致。
 *
 * <p>手法：检索结果也在录制里——用一个 {@code plan()} 直接返回录下的引用来源的 RagService，不需要向量模型；模型换成按指纹回放的
 * {@link ReplayModel}。断言的是「和参考实现一致」，不是「答得对」：答得好不好看 docs/ch05.md 的实测记录，以及第 9 章的评估。
 * 重新录制见 {@link RagRecordingIT}。依赖 ch05-5、ch05-6、ch05-7。
 */
class RagReplayTest {

    private static final Logger log = LoggerFactory.getLogger(RagReplayTest.class);

    static Stream<Arguments> recordedAnswers() {
        return RagRecordings.recordings().stream()
                .flatMap(recording -> recording.cases().stream()
                        .filter(c -> c.output() != null)
                        .map(c -> Arguments.of(recording.model(), c.message(), recording, c)));
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @MethodSource("recordedAnswers")
    void replayedAnswerIsCheckedLikeReferenceImplementation(
            String model, String message, RagRecordings.Recording recording, RagRecordings.RecordedCase c) {
        ReplayModel replay = new ReplayModel(recording);
        ChatClient chatClient = ChatClient.builder(replay).build();
        ChatService chat = RagRecordingIT.chatService(chatClient, recordedPlan(chatClient, c));

        ChatReply reply = chat.answer(message, new IntentResult(c.intent(), 0.9, null, null), null);

        assertThat(replay.calls()).as("回答模型只调一次").isEqualTo(1);
        assertThat(reply.reply()).as("引用校验后的回答").isEqualTo(c.reply());
        assertThat(reply.sources()).isEqualTo(c.sources());
        assertThat(reply.rag().cited()).isEqualTo(c.cited());
        assertThat(reply.rag().invalidCitations()).isEqualTo(c.invalid());
        assertThat(reply.rag().uncited()).isEqualTo(c.uncited());
    }

    /** rag-cases.json 加了问题却没重新录制时在这里失败；顺带打印每个模型的引用情况，只作参考。 */
    @Test
    void everyCaseIsRecordedForEveryModel() {
        List<String> messages =
                RagRecordings.cases().stream().map(RagRecordings.Case::message).toList();
        List<RagRecordings.Recording> recordings = RagRecordings.recordings();

        assertThat(recordings).as("至少有一份录制").isNotEmpty();
        for (RagRecordings.Recording recording : recordings) {
            assertThat(recording.cases())
                    .as(recording.model() + " 的录制覆盖 rag-cases.json 的全部问题，否则重新录制")
                    .extracting(RagRecordings.RecordedCase::message)
                    .containsExactlyElementsOf(messages);
            long answered =
                    recording.cases().stream().filter(c -> c.output() != null).count();
            long uncited = recording.cases().stream()
                    .filter(c -> Boolean.TRUE.equals(c.uncited()))
                    .count();
            long invalid = recording.cases().stream()
                    .filter(c -> c.invalid() != null && !c.invalid().isEmpty())
                    .count();
            log.info(
                    "{}（检索用 {}）：{} 条调了模型，{} 条短路；未引用 {} 条，含不存在编号 {} 条（录制于 {}）",
                    recording.model(),
                    recording.embeddingModel(),
                    answered,
                    recording.cases().size() - answered,
                    uncited,
                    invalid,
                    recording.recordedAt());
        }
    }

    /** 一个 plan() 直接返回录下的引用来源的 RagService：拼提示词、调模型、引用校验都走真实代码。 */
    private static RagService recordedPlan(ChatClient chatClient, RagRecordings.RecordedCase c) {
        RagRetriever unused = RagFixtures.retriever(
                RagFixtures.knowledgeBase(
                        new io.github.clarence404.assistant.knowledge.FakeEmbeddingModel(),
                        "fake-ngram",
                        RagFixtures.KNOWLEDGE),
                RagFixtures.properties());
        return new RagService(chatClient, unused, TestPrompts.prompts("v3")) {
            @Override
            public RagPlan plan(String message) {
                RagTrace trace = new RagTrace(
                        message,
                        null,
                        "hybrid",
                        0,
                        0,
                        c.sources().size(),
                        c.sources().stream().mapToInt(s -> s.content().length()).sum(),
                        0,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        null);
                return new RagPlan(c.sources(), trace, "（回放不会用到）");
            }
        };
    }

    /** 按 Prompt 指纹回放录下的原样输出；查不到就说明提示词变了，要重新录制。 */
    static final class ReplayModel implements ChatModel {

        private final String model;
        private final Map<String, String> outputs;
        private final AtomicInteger calls = new AtomicInteger();

        ReplayModel(RagRecordings.Recording recording) {
            this.model = recording.model();
            this.outputs = recording.cases().stream()
                    .filter(c -> c.promptHash() != null)
                    .collect(Collectors.toMap(
                            RagRecordings.RecordedCase::promptHash, RagRecordings.RecordedCase::output, (a, b) -> a));
        }

        int calls() {
            return calls.get();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            String hash = RagRecordings.promptHash(prompt);
            String output = outputs.get(hash);
            if (output == null) {
                throw new IllegalStateException("没有 " + model + " 对这个 Prompt（指纹 " + hash + "）的录制："
                        + "RAG 的系统提示词、资料模板或资料拼法变了，按 docs/ch05.md「录制回放」重新录制");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(output))));
        }

        @Override
        public ChatOptions getOptions() {
            return ChatOptions.builder().build();
        }
    }
}
