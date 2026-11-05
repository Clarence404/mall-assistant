package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.error.ModelFailure;
import io.github.clarence404.assistant.intent.IntentService;
import io.github.clarence404.assistant.prompt.TestPrompts;
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

/** 第 1 章：ChatClient 与 ChatService 的阻塞、流式调用和错误归类，假模型，不联网。系统提示词的模板渲染见第 2 章的 AssistantPromptsTest。 */
class ChatServiceTest {

    private final FakeChatModel model = new FakeChatModel();
    private ChatService service;

    @BeforeEach
    void setUp() {
        ChatClient chatClient = new ChatClientConfig().chatClient(ChatClient.builder(model));
        IntentService intentService = new IntentService(chatClient, new ClassPathResource("prompts/intent-v1.st"));
        service = new ChatService(
                chatClient, new ChatProperties(Duration.ofMillis(300), 0.6), TestPrompts.prompts("v2"), intentService);
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
