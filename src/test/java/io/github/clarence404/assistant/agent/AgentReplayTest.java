package io.github.clarence404.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.chat.ChatReply;
import io.github.clarence404.assistant.knowledge.FakeEmbeddingModel;
import io.github.clarence404.assistant.mall.StubMallApi;
import io.github.clarence404.assistant.prompt.TestPrompts;
import io.github.clarence404.assistant.rag.RagFixtures;
import io.github.clarence404.assistant.rag.RagPlan;
import io.github.clarence404.assistant.rag.RagService;
import io.github.clarence404.assistant.rag.RagSource;
import io.github.clarence404.assistant.rag.RagTrace;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
 * 第 7 章：Agent 决策的录制回放。用真实模型录下的原样输出（{@code recordings/agent-<模型>.json}）驱动带 Agent 的 ChatService，把每件事原样再办一遍，
 * 验证意图、每一步（动作、类型、参数、结果）、为什么停、提出的写操作、最终回答都和参考实现录制时一致——也就是说，参考实现能正确处理小模型真实的输出
 * （包括它办错、兜圈子的时候）。
 *
 * <p>手法：模型换成按 Prompt 指纹回放的 {@link ReplayModel}（意图识别和 Agent 的每一步都在录制里，任何一步的 Prompt 和录制时差一个字就报「没有录制」）；
 * 查规则换成按问题取录下资料的 RagService；商城用 {@link StubMallApi}，每件事前恢复初始状态。依赖 ch07-1～ch07-4。重新录制见 {@link AgentRecordingIT}。
 */
class AgentReplayTest {

    private static final Logger log = LoggerFactory.getLogger(AgentReplayTest.class);
    private static final StubMallApi MALL = StubMallApi.start();

    @AfterAll
    static void stopMall() {
        MALL.close();
    }

    @BeforeEach
    void resetMall() {
        MALL.reset();
    }

    static Stream<Arguments> recordedCases() {
        return AgentRecordings.recordings().stream()
                .flatMap(r -> r.cases().stream().map(c -> Arguments.of(r.model(), c.name(), c)));
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @MethodSource("recordedCases")
    void agentReplaysLikeReferenceImplementation(String model, String name, AgentRecordings.RecordedCase recorded) {
        ReplayModel replay = new ReplayModel(model, recorded.calls());
        AgentFixtures.Agent agent = AgentFixtures.agent(
                replay, recordedRetrieval(replay, recorded), MALL, AgentProperties.defaults(AgentProperties.Mode.LOOP));
        if (recorded.stopReason() == AgentTrace.StopReason.TIMEOUT) {
            // 录制时是真的慢（一步一两分钟）；回放时一瞬间就走完了。最后一次调用之后把时钟拨过总时长，「超时」才回放得出来
            replay.afterLastCall(() -> agent.memory().clock().advance(Duration.ofMinutes(3)));
        }

        ChatReply reply = agent.chat().chat(recorded.message(), AgentRecordings.token());
        AgentRecordings.RecordedCase replayed = AgentRecordings.recorded(
                new AgentRecordings.Case(name, recorded.message(), null, null), reply, 0, Map.of(), List.of());

        assertThat(replay.calls()).as("模型调用次数和录制时一样").isEqualTo(recorded.calls().size());
        assertThat(replayed.intent()).as("意图").isEqualTo(recorded.intent());
        assertThat(replayed.steps()).as("每一步").isEqualTo(recorded.steps());
        assertThat(replayed.stopReason()).as("为什么停").isEqualTo(recorded.stopReason());
        assertThat(replayed.proposal()).as("提出的写操作").isEqualTo(recorded.proposal());
        assertThat(replayed.reply()).as("回答").isEqualTo(recorded.reply());
        assertThat(MALL.writes()).as("回放时也一样：循环里不执行任何写操作").isEmpty();
    }

    /** agent-cases.json 加了事情却没重新录制时在这里失败；顺带打印每个模型办成了几件，只作参考。 */
    @Test
    void everyCaseIsRecordedForEveryModel() {
        List<String> names =
                AgentRecordings.cases().stream().map(AgentRecordings.Case::name).toList();
        List<AgentRecordings.Recording> recordings = AgentRecordings.recordings();

        assertThat(recordings).as("至少有一份录制").isNotEmpty();
        for (AgentRecordings.Recording recording : recordings) {
            assertThat(recording.cases())
                    .as(recording.model() + " 的录制覆盖 agent-cases.json 的全部事情，否则重新录制")
                    .extracting(AgentRecordings.RecordedCase::name)
                    .containsExactlyElementsOf(names);
            for (AgentRecordings.RecordedCase c : recording.cases()) {
                log.info(
                        "{}「{}」：{} 步，停止 {}，提出 {}",
                        recording.model(),
                        c.name(),
                        c.steps().size(),
                        c.stopReason(),
                        c.proposal() == null ? null : c.proposal().action());
            }
        }
    }

    /** 一个 plan() 按问题返回录下资料的 RagService。 */
    private static RagService recordedRetrieval(ChatModel model, AgentRecordings.RecordedCase recorded) {
        Map<String, List<RagSource>> byQuery = recorded.retrievals();
        RagFixtures.Kb unused =
                RagFixtures.knowledgeBase(new FakeEmbeddingModel(), "fake-ngram", RagFixtures.KNOWLEDGE);
        return new RagService(
                ChatClient.builder(model).build(),
                RagFixtures.retriever(unused, RagFixtures.properties()),
                TestPrompts.prompts("v3")) {
            @Override
            public RagPlan plan(String query) {
                List<RagSource> sources = byQuery.get(query);
                if (sources == null) {
                    throw new IllegalStateException("录制里没有用「" + query + "」查过规则：按 docs/ch07.md「录制回放」重新录制");
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
        private final int recordedCalls;
        private int calls;
        private Runnable afterLastCall = () -> {};

        ReplayModel(String model, List<AgentRecordings.Call> recorded) {
            this.model = model;
            this.recordedCalls = recorded.size();
            this.outputs = recorded.stream()
                    .collect(Collectors.toMap(
                            AgentRecordings.Call::promptHash, AgentRecordings.Call::output, (a, b) -> a));
        }

        int calls() {
            return calls;
        }

        /** 录下的最后一次调用回放完之后做的事（拨时钟）。 */
        void afterLastCall(Runnable action) {
            this.afterLastCall = action;
        }

        /** 和录制时的 Ollama 一样声明支持原生结构化输出：Schema 走选项、不拼进消息，指纹才对得上。 */
        @Override
        public ChatOptions getOptions() {
            return OllamaChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls++;
            String hash = AgentRecordings.promptHash(prompt);
            String output = outputs.get(hash);
            if (output == null) {
                throw new IllegalStateException("没有 " + model + " 对这个 Prompt（指纹 " + hash + "）的录制："
                        + "Agent 提示词、办事记录的拼法或工具结果的格式变了，按 docs/ch07.md「录制回放」重新录制");
            }
            if (calls == recordedCalls) {
                afterLastCall.run();
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(output))));
        }
    }
}
