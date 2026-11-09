package io.github.clarence404.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.chat.ChatClientConfig;
import io.github.clarence404.assistant.knowledge.FakeEmbeddingModel;
import io.github.clarence404.assistant.mall.StubMallApi;
import io.github.clarence404.assistant.prompt.TestPrompts;
import io.github.clarence404.assistant.rag.RagFixtures;
import io.github.clarence404.assistant.rag.RagPlan;
import io.github.clarence404.assistant.rag.RagService;
import io.github.clarence404.assistant.rag.RagSource;
import io.github.clarence404.assistant.rag.RagTrace;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.ai.ollama.api.OllamaChatOptions;

/**
 * 第 6 章：多轮对话的录制回放。用真实模型录下的原样输出（{@code recordings/conversation-<模型>.json}）驱动带记忆的 ChatService，把每段对话
 * 原样再走一遍，验证每一句的意图、沿用的订单号、带了几轮历史、追问改写的结果、检索用的问题、调用的工具和最终回答都和参考实现录制时一致。
 *
 * <p>手法：模型换成按 Prompt 指纹回放的 {@link ReplayModel}——意图识别、追问改写、回答三种调用都在录制里，任何一次的 Prompt
 * （含历史的拼法、裁剪结果、改写提示词）和录制时差一个字就报「没有录制」；检索换成按「检索用的问题」取录下资料的 RagService，检索用的问题不对
 * （改写结果变了）同样报错。商城用 {@link StubMallApi}，和录制时一样。依赖 ch06-1～ch06-6。重新录制见 {@link ConversationRecordingIT}。
 */
class ConversationReplayTest {

    private static final Logger log = LoggerFactory.getLogger(ConversationReplayTest.class);
    private static final StubMallApi MALL = StubMallApi.start();

    @AfterAll
    static void stopMall() {
        MALL.close();
    }

    static Stream<Arguments> recordedConversations() {
        return ConversationRecordings.recordings().stream()
                .flatMap(
                        recording -> recording.cases().stream().map(c -> Arguments.of(recording.model(), c.name(), c)));
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @MethodSource("recordedConversations")
    void conversationReplaysLikeReferenceImplementation(
            String model, String name, ConversationRecordings.RecordedCase recorded) {
        ReplayModel replay = new ReplayModel(model, recorded.calls());
        ChatClient chatClient = new ChatClientConfig().chatClient(ChatClient.builder(replay));
        MemoryFixtures.Chat chat = MemoryFixtures.chat(
                chatClient, recordedRetrieval(chatClient, recorded), MALL, MemoryFixtures.properties());
        ConversationRecordings.Case c = new ConversationRecordings.Case(
                recorded.name(),
                recorded.login(),
                recorded.turns().stream()
                        .map(ConversationRecordings.Turn::message)
                        .toList(),
                null);

        ConversationRecordings.RecordedCase replayed = ConversationRecordingIT.run(chat, c, null);

        assertThat(replay.calls()).as("模型调用次数和录制时一样").isEqualTo(recorded.calls().size());
        for (int i = 0; i < recorded.turns().size(); i++) {
            ConversationRecordings.Turn expected = recorded.turns().get(i);
            ConversationRecordings.Turn actual = replayed.turns().get(i);
            String label = "第 " + (i + 1) + " 句「" + expected.message() + "」";
            assertThat(actual.intent()).as(label + "的意图").isEqualTo(expected.intent());
            assertThat(actual.historyTurns()).as(label + "带的历史轮数").isEqualTo(expected.historyTurns());
            assertThat(actual.condensedQuestion()).as(label + "的追问改写").isEqualTo(expected.condensedQuestion());
            assertThat(actual.carriedOrderNo()).as(label + "沿用的订单号").isEqualTo(expected.carriedOrderNo());
            assertThat(actual.retrievalQuery()).as(label + "检索用的问题").isEqualTo(expected.retrievalQuery());
            assertThat(actual.tools()).as(label + "调用的工具").isEqualTo(expected.tools());
            assertThat(actual.reply()).as(label + "的回答").isEqualTo(expected.reply());
        }
    }

    /** conversation-cases.json 加了对话却没重新录制时在这里失败；顺带打印每个模型的追问改写与沿用情况，只作参考。 */
    @Test
    void everyCaseIsRecordedForEveryModel() {
        List<String> names = ConversationRecordings.cases().stream()
                .map(ConversationRecordings.Case::name)
                .toList();
        List<ConversationRecordings.Recording> recordings = ConversationRecordings.recordings();

        assertThat(recordings).as("至少有一份录制").isNotEmpty();
        for (ConversationRecordings.Recording recording : recordings) {
            assertThat(recording.cases())
                    .as(recording.model() + " 的录制覆盖 conversation-cases.json 的全部对话，否则重新录制")
                    .extracting(ConversationRecordings.RecordedCase::name)
                    .containsExactlyElementsOf(names);
            for (ConversationRecordings.RecordedCase c : recording.cases()) {
                ConversationRecordings.Turn last = c.turns().getLast();
                log.info(
                        "{}「{}」最后一句：改写「{}」，沿用订单号 {}，回答：{}",
                        recording.model(),
                        c.name(),
                        last.condensedQuestion(),
                        last.carriedOrderNo(),
                        last.reply());
            }
        }
    }

    /** 一个 plan() 按「检索用的问题」返回录下资料的 RagService：拼提示词、调模型、引用校验都走真实代码。 */
    private static RagService recordedRetrieval(ChatClient chatClient, ConversationRecordings.RecordedCase recorded) {
        Map<String, List<RagSource>> byQuery = new HashMap<>();
        recorded.turns().stream()
                .filter(t -> t.retrievalQuery() != null)
                .forEach(t -> byQuery.put(t.retrievalQuery(), t.sources()));
        RagFixtures.Kb unused =
                RagFixtures.knowledgeBase(new FakeEmbeddingModel(), "fake-ngram", RagFixtures.KNOWLEDGE);
        return new RagService(
                chatClient, RagFixtures.retriever(unused, RagFixtures.properties()), TestPrompts.prompts("v3")) {
            @Override
            public RagPlan plan(String query) {
                List<RagSource> sources = byQuery.get(query);
                if (sources == null) {
                    throw new IllegalStateException("录制里没有用「" + query + "」检索过：追问改写的结果变了，按 docs/ch06.md「录制回放」重新录制");
                }
                RagTrace trace = new RagTrace(
                        query,
                        null,
                        "hybrid",
                        0,
                        0,
                        sources.size(),
                        0,
                        0,
                        sources.isEmpty() ? RagTrace.NO_MATCH : null,
                        null,
                        List.of(),
                        List.of(),
                        null);
                return new RagPlan(sources, trace, "（回放：没有相关资料）");
            }
        };
    }

    /** 按 Prompt 指纹回放录下的原样输出；查不到就说明发给模型的消息变了，要重新录制。 */
    static final class ReplayModel implements ChatModel {

        private final String model;
        private final Map<String, String> outputs;
        private int calls;

        ReplayModel(String model, List<ConversationRecordings.Call> recorded) {
            this.model = model;
            this.outputs = recorded.stream()
                    .collect(Collectors.toMap(
                            ConversationRecordings.Call::promptHash, ConversationRecordings.Call::output, (a, b) -> a));
        }

        int calls() {
            return calls;
        }

        /** 和录制时的 Ollama 一样声明支持原生结构化输出与工具调用：意图识别的 Schema 走选项、不拼进消息，指纹才对得上。 */
        @Override
        public ChatOptions getOptions() {
            return OllamaChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls++;
            String hash = ConversationRecordings.promptHash(prompt);
            String output = outputs.get(hash);
            if (output == null) {
                throw new IllegalStateException("没有 " + model + " 对这个 Prompt（指纹 " + hash + "）的录制："
                        + "历史的拼法、窗口裁剪、追问改写提示词或回答提示词变了，按 docs/ch06.md「录制回放」重新录制");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(output))));
        }
    }
}
