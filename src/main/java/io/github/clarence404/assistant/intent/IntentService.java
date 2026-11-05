package io.github.clarence404.assistant.intent;

import io.github.clarence404.assistant.error.ModelCallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

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
        // TODO ch02-4：用 callModel 识别意图，解析失败（返回 null）时带上 STRICT_REMINDER 再试一次（callModel(message, true)），
        //  两次都失败就返回 IntentResult.unknown()；成功时返回 sanitized() 之后的结果。
        //  模型调不通抛出的 ModelCallException 不要捕获，原样往上抛（接口会返回 503）。
        //  现在直接返回 UNKNOWN、不调模型，所以对话接口照常可用（第 1 章的测试仍然通过）。
        //  验收：IntentServiceTest.garbageIsRetried… / retrySucceedsOnSecondAttempt / modelUnavailableIsNotSwallowed，
        //  以及 IntentReplayTest（回放真实模型的输出）
        return IntentResult.unknown();
    }

    /**
     * 调一次模型并把输出转成 {@link IntentResult}。
     *
     * @param strict 是否在用户消息后追加 {@link #STRICT_REMINDER}
     * @return 解析结果；输出不是合法 JSON、或为空时返回 null
     * @throws ModelCallException 模型调用本身失败
     */
    IntentResult callModel(String message, boolean strict) {
        // TODO ch02-3：调一次模型，让它直接返回 IntentResult：
        //  1. 系统消息用 intentPrompt；用户消息用 USER_TEMPLATE 渲染，变量 message 填用户原文；strict 为 true 时模板后面再接上
        //     "\n\n" + STRICT_REMINDER
        //  2. 本次请求把温度设为 TEMPERATURE（只覆盖这一项，模型名等仍用配置文件的）
        //  3. 用 entity 把输出转成 IntentResult，并让 Spring AI 把 JSON Schema 交给厂商的原生结构化输出
        //  4. 输出不是合法 JSON（Jackson 3 的 tools.jackson.core.JacksonException）→ 返回 null；
        //     其他 RuntimeException（连不上、超时……）→ 转成 ModelCallException 抛出
        //  验收：IntentServiceTest.parsesValidJsonIntoIntentResult / schemaGoesToProviderAndTemperatureIsZero /
        //  markdownFenceAroundJsonIsTolerated
        throw new UnsupportedOperationException("TODO ch02-3：实现 IntentService#callModel");
    }
}
