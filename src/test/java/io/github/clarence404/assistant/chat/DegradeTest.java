package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.error.ModelFailure;
import io.github.clarence404.assistant.guard.GuardProperties;
import io.github.clarence404.assistant.intent.Intent;
import io.github.clarence404.assistant.intent.IntentResult;
import io.github.clarence404.assistant.intent.IntentService;
import io.github.clarence404.assistant.mall.MallApiClient;
import io.github.clarence404.assistant.mall.MallApiProperties;
import io.github.clarence404.assistant.mcp.client.ExternalMcpTools;
import io.github.clarence404.assistant.prompt.TestPrompts;
import io.github.clarence404.assistant.rag.RagFixtures;
import io.github.clarence404.assistant.rag.RagSource;
import io.github.clarence404.assistant.rag.RagTrace;
import io.github.clarence404.assistant.tool.MallTools;
import io.github.clarence404.assistant.tool.ToolProperties;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * 第 9 章 TODO ch09-8：降级——规则类问题调模型失败时，检索到的资料还在手里，直接把原文给用户，而不是一句「稍后再试」。
 */
class DegradeTest {

    private static final IntentResult POLICY = new IntentResult(Intent.POLICY, 0.9, null, null);
    private static final ResourceAccessException DOWN =
            new ResourceAccessException("I/O error", new ConnectException("Connection refused"));

    private final FakeChatModel model = new FakeChatModel();

    @Test
    void ragAnswerFallsBackToOriginalText() {
        model.willFail(DOWN);

        ChatReply reply = service(true).answer("七天无理由退货要什么条件", POLICY, null);

        assertThat(reply.reply()).startsWith(ChatService.DEGRADED_HEAD).contains("[1] ");
        assertThat(reply.sources()).isNotEmpty();
        assertThat(reply.reply()).contains(reply.sources().getFirst().headingPath());
        assertThat(reply.rag().shortCircuit()).isEqualTo(RagTrace.MODEL_UNAVAILABLE);
        assertThat(reply.rag().warning()).isEqualTo(ModelFailure.MODEL_UNAVAILABLE.name());
    }

    @Test
    void circuitOpenAlsoDegrades() {
        model.willFail(new ModelCallException(ModelFailure.MODEL_CIRCUIT_OPEN, null));

        ChatReply reply = service(true).answer("七天无理由退货要什么条件", POLICY, null);

        assertThat(reply.reply()).startsWith(ChatService.DEGRADED_HEAD);
        assertThat(reply.rag().warning()).isEqualTo("MODEL_CIRCUIT_OPEN");
    }

    @Test
    void degradeOffThrowsLikeChapterFive() {
        model.willFail(DOWN);

        assertThatThrownBy(() -> service(false).answer("七天无理由退货要什么条件", POLICY, null))
                .isInstanceOf(ModelCallException.class);
    }

    /** 只降级规则类问题：查订单、闲聊离不开模型，照样 503。 */
    @Test
    void nonRagQuestionsStillFail() {
        model.willFail(DOWN);

        assertThatThrownBy(() -> service(true).answer("你好", IntentResult.unknown(), null))
                .isInstanceOf(ModelCallException.class);
    }

    @Test
    void degradedTextFormat() {
        RagSource first = source(1, "退换货规则 > 七天无理由退货 > 适用条件", "退换货规则 > 七天无理由退货 > 适用条件\n" + "签收后七天内".repeat(10));
        RagSource second = source(2, "退换货规则 > 退货运费", "退换货规则 > 退货运费\n质量问题由商城承担运费。");

        String text = ChatService.degradedText(List.of(first, second), 12);

        assertThat(text)
                .isEqualTo(ChatService.DEGRADED_HEAD
                        + "\n\n[1] 退换货规则 > 七天无理由退货 > 适用条件\n签收后七天内签收后七天内…"
                        + "\n\n[2] 退换货规则 > 退货运费\n质量问题由商城承担运费。");
    }

    /** 流式：一个字都还没说就失败了 → 先推资料，再推一段原文代替错误。 */
    @Test
    void streamDegradesWhenNothingWasSaid() {
        model.willStream(Flux.empty());
        model.willFail(DOWN);

        List<AnswerPart> parts = service(true)
                .answerStream("七天无理由退货要什么条件", POLICY, null)
                .collectList()
                .block();

        assertThat(parts)
                .last()
                .isInstanceOfSatisfying(
                        AnswerPart.Text.class, t -> assertThat(t.text()).startsWith(ChatService.DEGRADED_HEAD));
        assertThat(parts.subList(0, parts.size() - 1)).allMatch(p -> p instanceof AnswerPart.Source);
    }

    /** 流式：已经推了一半才失败，前半段已经给用户了，只能照旧以错误结束。 */
    @Test
    void streamFailsWhenHalfWayThrough() {
        model.willStream("七天");
        model.willFail(DOWN);

        StepVerifier.create(service(true).answerStream("七天无理由退货要什么条件", POLICY, null))
                .thenConsumeWhile(p -> p instanceof AnswerPart.Source)
                .expectNext(new AnswerPart.Text("七天"))
                .verifyError(ModelCallException.class);
    }

    private ChatService service(boolean degrade) {
        ChatClient chatClient = ChatClient.builder(model).build();
        GuardProperties defaults = GuardProperties.defaults();
        GuardProperties guard = new GuardProperties(
                defaults.enabled(),
                defaults.requestsPerMinute(),
                defaults.maxConcurrent(),
                defaults.acquireTimeout(),
                defaults.circuit(),
                defaults.injection(),
                new GuardProperties.Degrade(degrade, 200));
        return new ChatService(
                chatClient,
                new ChatProperties(Duration.ofSeconds(5), 0.6),
                TestPrompts.prompts("v3"),
                new IntentService(chatClient, new ClassPathResource("prompts/intent-v2.st")),
                new MallTools(new MallApiClient(
                        new MallApiProperties("http://127.0.0.1:9", Duration.ofMillis(200), Duration.ofMillis(200)))),
                new ToolProperties(true, true),
                RagFixtures.service(chatClient, RagFixtures.fakeKnowledgeBase(), RagFixtures.properties()),
                null,
                null,
                null,
                ExternalMcpTools.none(),
                guard);
    }

    private static RagSource source(int index, String heading, String content) {
        return new RagSource(
                index, "returns.md", "退换货规则", heading, List.of(0), 0.8, null, 0.8, List.of("vector"), "", content);
    }
}
