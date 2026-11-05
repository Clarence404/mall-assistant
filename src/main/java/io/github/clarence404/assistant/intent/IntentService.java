package io.github.clarence404.assistant.intent;

import io.github.clarence404.assistant.error.ModelCallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;

/**
 * 意图识别：让模型按 {@link IntentResult} 的 JSON Schema 输出，再转成 Java 对象。
 *
 * <p>Schema 优先交给厂商的原生结构化输出（Ollama 的 {@code format}、OpenAI 的 {@code response_format}），由模型在解码时保证 JSON
 * 结构；不支持的模型，Spring AI 退回到把格式说明拼进用户消息。用 qwen2.5:0.5b 跑录制用的 12 条消息，格式说明拼进提示词时只分对
 * 2 条，改用原生方式分对 6 条：长长的英文 Schema 说明本身就会干扰小模型。
 *
 * <p>模型输出解析不了时重试一次（加一句更严格的提醒），还不行就返回 {@link IntentResult#unknown()}，不把解析异常抛给用户；
 * 模型本身调不通（连不上、超时）则抛 {@link ModelCallException}，和对话接口一样返回 503。
 */
@Service
public class IntentService {

    private static final Logger log = LoggerFactory.getLogger(IntentService.class);

    /** 分类要稳定、可复现，温度调到 0；回答用配置文件里的温度。 */
    static final double TEMPERATURE = 0.0;

    /** 用户消息模板：用标签把用户原文圈起来，模型更不容易把里面的话当成给它的指令。 */
    static final String USER_TEMPLATE = """
            请识别下面这条用户消息的意图：
            <message>
            {message}
            </message>""";

    /** 第二次尝试追加的提醒。注意不能写花括号：用户消息模板要经过模板引擎渲染。 */
    static final String STRICT_REMINDER = "注意：上一次的输出无法解析。这一次只输出一个 JSON 对象，不要输出任何解释、前后缀或 Markdown 代码块。";

    private final ChatClient chatClient;
    private final Resource intentPrompt;

    public IntentService(ChatClient chatClient, @Value("classpath:prompts/intent-v1.st") Resource intentPrompt) {
        this.chatClient = chatClient;
        this.intentPrompt = intentPrompt;
    }

    /** 识别一条用户消息的意图，结果已经过 {@link IntentResult#sanitized()} 校验。最多调两次模型，解析失败时兜底为 UNKNOWN。 */
    public IntentResult classify(String message) {
        IntentResult result = callModel(message, false);
        if (result == null) {
            log.info("意图识别：模型输出无法解析，带上更严格的提醒重试一次");
            result = callModel(message, true);
        }
        if (result == null) {
            log.warn("意图识别：重试后仍无法解析，按 UNKNOWN 处理");
            return IntentResult.unknown();
        }
        IntentResult sanitized = result.sanitized();
        log.debug("意图识别：{} -> {}", message, sanitized);
        return sanitized;
    }

    /**
     * 调一次模型并把输出转成 {@link IntentResult}。
     *
     * @param strict 是否在用户消息后追加 {@link #STRICT_REMINDER}
     * @return 解析结果；输出不是合法 JSON、或为空时返回 null
     * @throws ModelCallException 模型调用本身失败
     */
    IntentResult callModel(String message, boolean strict) {
        String template = strict ? USER_TEMPLATE + "\n\n" + STRICT_REMINDER : USER_TEMPLATE;
        try {
            return chatClient
                    .prompt()
                    .system(intentPrompt)
                    .user(u -> u.text(template).param("message", message))
                    .options(ChatOptions.builder().temperature(TEMPERATURE))
                    .call()
                    .entity(IntentResult.class, spec -> spec.useProviderStructuredOutput());
        } catch (JacksonException e) {
            log.info("意图识别：输出不是合法的 JSON：{}", e.getOriginalMessage());
            return null;
        } catch (RuntimeException e) {
            throw ModelCallException.from(e);
        }
    }
}
