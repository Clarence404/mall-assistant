package io.github.clarence404.assistant.chat;

import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.intent.IntentResult;
import io.github.clarence404.assistant.intent.IntentService;
import io.github.clarence404.assistant.prompt.AssistantPrompts;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/** 和模型对话：先识别意图，超出范围的直接拒答；其余一问一答（阻塞）或逐段输出（流式）。调用失败统一转成 {@link ModelCallException}。 */
@Service
public class ChatService {

    private final ChatClient chatClient;
    private final ChatProperties properties;
    private final AssistantPrompts prompts;
    private final IntentService intentService;

    public ChatService(
            ChatClient chatClient, ChatProperties properties, AssistantPrompts prompts, IntentService intentService) {
        this.chatClient = chatClient;
        this.properties = properties;
        this.prompts = prompts;
        this.intentService = intentService;
    }

    /** 阻塞接口的完整流程：识别意图 → 超出范围就回拒答话术（不调回答模型）→ 否则让模型回答。 */
    public ChatReply chat(String message) {
        // TODO ch02-6：先用 intentService 识别意图；shouldRefuse 为 true 时直接返回 prompts.refusal()，不再调回答模型（省一次调用）；
        //  否则用 reply(message) 让模型回答。两种情况都把识别出的意图放进 ChatReply。
        //  验收：IntentApiTest.chatReplyIncludesIntent / outOfScopeIsRefusedWithoutCallingAnswerModel /
        //  lowConfidenceOutOfScopeIsStillAnswered
        return new ChatReply(reply(message), IntentResult.unknown());
    }

    /** 流式接口的回答部分：意图已经识别好，超出范围就只推一段拒答话术，否则流式调用模型。 */
    public Flux<String> answerStream(String message, IntentResult intent) {
        // TODO ch02-7：shouldRefuse 为 true 时返回只有一段拒答话术的 Flux，不调模型；否则 stream(message)。
        //  验收：IntentApiTest.streamRefusesOutOfScopeWithoutCallingAnswerModel
        return stream(message);
    }

    /** 只有把握够大的 OUT_OF_SCOPE 才拒答。 */
    boolean shouldRefuse(IntentResult intent) {
        // TODO ch02-6：意图是 Intent.OUT_OF_SCOPE，且 confidence 不低于 properties.refuseMinConfidence() 时返回 true。
        //  把握不够就照常回答：错拒一个正常问题，比多回答一句无关问题代价大。
        return false;
    }

    /** 阻塞调用：等模型生成完整回答再返回。超时由 HTTP 客户端的 read-timeout 控制（见 application.yml）。 */
    public String reply(String message) {
        try {
            String content = chatClient
                    .prompt()
                    .system(prompts::applySystem)
                    .user(message)
                    .call()
                    .content();
            return Objects.requireNonNullElse(content, "");
        } catch (RuntimeException e) {
            throw ModelCallException.from(e);
        }
    }

    /** 流式调用：模型每生成一段就推一段。首段和相邻两段之间超过 streamTimeout 没动静，按超时结束。 */
    public Flux<String> stream(String message) {
        return chatClient.prompt().system(prompts::applySystem).user(message).stream()
                .content()
                .timeout(properties.streamTimeout())
                .onErrorMap(ModelCallException::from);
    }
}
