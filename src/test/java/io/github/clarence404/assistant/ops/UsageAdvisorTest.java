package io.github.clarence404.assistant.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.chat.FakeChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ConnectException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;

/**
 * 第 9 章 TODO ch09-4：Token 从哪来——模型报告的优先，没有就按字数估算；每次调用记进这一轮的账本。
 *
 * <p>手法：{@link FakeChatModel} 不报告用量（和命令行模型的流式输出一样），走估算；{@link ReportingModel} 像 Ollama 一样在响应里带用量，走真实值。
 */
class UsageAdvisorTest {

    private SimpleMeterRegistry registry;
    private UsageAdvisor advisor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        advisor = new UsageAdvisor(new ChatMetrics(registry));
    }

    @Test
    void estimateCountsCjkDigitsAndOthers() {
        assertThat(TokenEstimator.estimate((String) null)).isZero();
        assertThat(TokenEstimator.estimate("")).isZero();
        // 10 个汉字 × 0.7 = 7
        assertThat(TokenEstimator.estimate("七天无理由退货的条件")).isEqualTo(7);
        // 23 位订单号：一位一个
        assertThat(TokenEstimator.estimate("21182516707889029120785")).isEqualTo(23);
        // 9 个字母：9 / 4.5 = 2
        assertThat(TokenEstimator.estimate("hello you")).isEqualTo(2);
        // 混合：2 个汉字（1.4）+ 3 个数字（3）+ 1 个空格（0.22）= 4.62，向上取整 5
        assertThat(TokenEstimator.estimate("订单 123")).isEqualTo(5);
        // 全角标点算汉字：「，」「？」各 0.7
        assertThat(TokenEstimator.estimate("，？")).isEqualTo(2);
    }

    @Test
    void estimateMessagesAddsRoleOverhead() {
        assertThat(TokenEstimator.estimate(List.of(new SystemMessage("你好"), new UserMessage("你好"))))
                .isEqualTo((2 + TokenEstimator.PER_MESSAGE) * 2);
    }

    @Test
    void reportedUsageWins() {
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("好的"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(120, 30)).build());

        assertThat(UsageAdvisor.usageOf(response, new Prompt("问题"))).isEqualTo(new TokenUsage(120, 30, false));
    }

    @Test
    void missingUsageIsEstimated() {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("七天无理由退货的条件"))));
        Prompt prompt = new Prompt(List.of(new UserMessage("你好")));

        TokenUsage usage = UsageAdvisor.usageOf(response, prompt);

        assertThat(usage).isEqualTo(new TokenUsage(2 + TokenEstimator.PER_MESSAGE, 7, true));
        assertThat(UsageAdvisor.usageOf(null, prompt).output()).isZero();
        assertThat(UsageAdvisor.usageOf(null, prompt).estimated()).isTrue();
    }

    /** 阻塞调用：这一轮绑定在当前线程上，advisor 找得到它；步骤名来自 {@link UsageAdvisor#stage(String)}。 */
    @Test
    void blockingCallIsRecordedIntoTheBoundTurn() {
        ChatClient client = ChatClient.builder(new ReportingModel())
                .defaultAdvisors(advisor)
                .build();
        TurnRecord record = new TurnRecord("user:10001", null);

        try (TurnRecord.Binding bound = record.bind()) {
            client.prompt()
                    .user("七天无理由退货要什么条件")
                    .advisors(UsageAdvisor.stage("rag"))
                    .call()
                    .content();
        }

        assertThat(record.calls()).singleElement().satisfies(call -> {
            assertThat(call.stage()).isEqualTo("rag");
            assertThat(call.model()).isEqualTo("qwen2.5:1.5b");
            assertThat(call.inputTokens()).isEqualTo(120);
            assertThat(call.outputTokens()).isEqualTo(30);
            assertThat(call.estimated()).isFalse();
        });
        assertThat(TurnRecord.current()).isNull();
        assertThat(registry.get("assistant.model.tokens")
                        .tags("type", "input", "stage", "rag")
                        .counter()
                        .count())
                .isEqualTo(120);
    }

    /** 流式调用：账本放在请求上下文里（流在别的线程上推数据）；假模型不报告用量，按拼起来的文字估算。 */
    @Test
    void streamingCallUsesTheRecordFromTheRequestContext() {
        FakeChatModel model = new FakeChatModel();
        model.willStream("七天", "无理由", "退货");
        ChatClient client = ChatClient.builder(model).defaultAdvisors(advisor).build();
        TurnRecord record = new TurnRecord("ip:127.0.0.1", null);

        List<String> parts = client.prompt().user("你好").advisors(UsageAdvisor.stage("answer", record)).stream()
                .content()
                .collectList()
                .block();

        assertThat(parts).containsExactly("七天", "无理由", "退货");
        assertThat(record.calls()).singleElement().satisfies(call -> {
            assertThat(call.stage()).isEqualTo("answer");
            assertThat(call.outputTokens()).isEqualTo(TokenEstimator.estimate("七天无理由退货"));
            assertThat(call.estimated()).isTrue();
        });
        assertThat(record.usage().estimated()).isTrue();
    }

    @Test
    void callsWithoutATurnOnlyGoToMetrics() {
        ChatClient client = ChatClient.builder(new ReportingModel())
                .defaultAdvisors(advisor)
                .build();

        client.prompt().user("你好").call().content();

        assertThat(registry.get("assistant.model.calls")
                        .tags("stage", UsageAdvisor.OTHER, "outcome", "OK")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void failedCallIsCountedAndRethrown() {
        FakeChatModel model = new FakeChatModel();
        model.willFail(new ResourceAccessException("连不上", new ConnectException("refused")));
        ChatClient client = ChatClient.builder(model).defaultAdvisors(advisor).build();
        TurnRecord record = new TurnRecord("ip:127.0.0.1", null);

        try (TurnRecord.Binding bound = record.bind()) {
            assertThatThrownBy(() -> client.prompt()
                            .user("你好")
                            .advisors(UsageAdvisor.stage("answer"))
                            .call()
                            .content())
                    .isInstanceOf(ResourceAccessException.class);
        }

        assertThat(record.calls()).isEmpty();
        assertThat(registry.get("assistant.model.calls")
                        .tags("stage", "answer", "outcome", "ERROR")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void usageTraceSumsCalls() {
        UsageTrace trace = UsageTrace.of(List.of(
                new ModelCall("intent", null, 100, 20, false, 10), new ModelCall("rag", null, 800, 150, true, 20)));

        assertThat(trace.inputTokens()).isEqualTo(900);
        assertThat(trace.outputTokens()).isEqualTo(170);
        assertThat(trace.estimated()).isTrue();
        assertThat(trace.withBudget(5000, 100000).todayTokens()).isEqualTo(5000);
    }

    /** 像 Ollama 一样在响应里报告用量的假模型。 */
    static final class ReportingModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage("好的"))),
                    ChatResponseMetadata.builder()
                            .usage(new DefaultUsage(120, 30))
                            .model("qwen2.5:1.5b")
                            .build());
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getOptions() {
            return ChatOptions.builder().build();
        }
    }
}
