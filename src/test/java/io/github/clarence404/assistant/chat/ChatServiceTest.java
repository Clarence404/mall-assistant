package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.error.ModelFailure;
import io.github.clarence404.assistant.intent.IntentService;
import io.github.clarence404.assistant.mall.MallApiClient;
import io.github.clarence404.assistant.mall.MallApiProperties;
import io.github.clarence404.assistant.prompt.TestPrompts;
import io.github.clarence404.assistant.rag.RagFixtures;
import io.github.clarence404.assistant.tool.MallTools;
import io.github.clarence404.assistant.tool.ToolProperties;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * 第 1 章：ChatClient 与 ChatService 的阻塞、流式调用和错误归类，假模型，不联网。系统提示词的模板渲染见第 2 章的 AssistantPromptsTest。
 *
 * <p>手法：纯单元测试，不启动 Spring，手工 new 出 ChatClient（底下是 {@link FakeChatModel}）、IntentService 和 ChatService，比接口测试快得多。
 * 流式结果用 Reactor 的 {@link StepVerifier} 断言：它订阅 Flux，逐个检查发出的元素，最后检查是正常结束还是以哪种异常结束。
 */
class ChatServiceTest {

    private final FakeChatModel model = new FakeChatModel();
    private ChatService service;

    @BeforeEach
    void setUp() {
        ChatClient chatClient = new ChatClientConfig().chatClient(ChatClient.builder(model));
        IntentService intentService = new IntentService(chatClient, new ClassPathResource("prompts/intent-v2.st"));
        // 流式空闲超时只给 300 毫秒，「模型一直不吭声」的测试很快就能结束；0.6 是拒答的置信度门槛，与 application.yml 一致
        // 这里只测第 1 章的 reply / stream，不会调商城，商城地址随便给一个（工具调用见第 3 章的 ToolCallingFlowTest）
        MallTools tools = new MallTools(new MallApiClient(
                new MallApiProperties("http://127.0.0.1:1", Duration.ofSeconds(1), Duration.ofSeconds(1))));
        service = new ChatService(
                chatClient,
                new ChatProperties(Duration.ofMillis(300), 0.6),
                TestPrompts.prompts("v2"),
                intentService,
                tools,
                new ToolProperties(true, true),
                RagFixtures.disabled(chatClient));
    }

    @Test
    void systemPromptIsSentWithEveryRequest() {
        service.reply("你们支持七天无理由退货吗？");

        SystemMessage system = model.lastPrompt().getSystemMessage();
        assertThat(system.getText()).contains("客服");
        assertThat(model.lastPrompt().getUserMessage().getText()).isEqualTo("你们支持七天无理由退货吗？");
    }

    @Test
    void replyReturnsWholeAnswer() {
        model.willReply("支持的，签收后七天内可以申请。");

        assertThat(service.reply("能退货吗")).isEqualTo("支持的，签收后七天内可以申请。");
    }

    @Test
    void streamEmitsChunksInOrder() {
        model.willStream("签收后", "七天内", "可以申请退货。");

        StepVerifier.create(service.stream("能退货吗"))
                .expectNext("签收后", "七天内", "可以申请退货。")
                .verifyComplete();
        assertThat(model.lastPrompt().getSystemMessage().getText()).contains("客服");
    }

    @Test
    void replyMapsConnectionRefusedToModelUnavailable() {
        model.willFail(new ResourceAccessException("I/O error", new ConnectException("Connection refused")));

        assertThatThrownBy(() -> service.reply("在吗"))
                .isInstanceOfSatisfying(
                        ModelCallException.class,
                        e -> assertThat(e.failure()).isEqualTo(ModelFailure.MODEL_UNAVAILABLE));
    }

    @Test
    void replyMapsReadTimeoutToModelTimeout() {
        model.willFail(new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> service.reply("在吗"))
                .isInstanceOfSatisfying(
                        ModelCallException.class, e -> assertThat(e.failure()).isEqualTo(ModelFailure.MODEL_TIMEOUT));
    }

    /** {@code Flux.never()} 永远不发元素也不结束：验证 ChatService 的 timeout 能把它截断并归为 MODEL_TIMEOUT，而不是让请求一直挂着。 */
    @Test
    void streamTimesOutWhenModelStaysSilent() {
        model.willStream(Flux.never());

        StepVerifier.create(service.stream("在吗"))
                .expectErrorSatisfies(e -> assertThat(e)
                        .isInstanceOfSatisfying(
                                ModelCallException.class,
                                ex -> assertThat(ex.failure()).isEqualTo(ModelFailure.MODEL_TIMEOUT)))
                .verify(Duration.ofSeconds(5));
    }

    /** 已经发出一段之后模型出错：前面的片段照常收到，随后以 MODEL_UNAVAILABLE 结束。 */
    @Test
    void streamMapsMidwayFailureToModelUnavailable() {
        model.willStream("您好，");
        model.willFail(new IllegalStateException("HTTP 500 - model crashed"));

        StepVerifier.create(service.stream("在吗"))
                .expectNext("您好，")
                .expectErrorSatisfies(e -> assertThat(e)
                        .isInstanceOfSatisfying(
                                ModelCallException.class,
                                ex -> assertThat(ex.failure()).isEqualTo(ModelFailure.MODEL_UNAVAILABLE)))
                .verify(Duration.ofSeconds(5));
    }
}
