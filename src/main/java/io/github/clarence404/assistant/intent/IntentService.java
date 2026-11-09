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
 *
 * <p>为什么两种失败区别对待：解析失败是「模型这次没按格式说」，模型的输出带随机性，换个说法再问一次很可能就对了，重试一次划算；
 * 连接失败、超时是「模型服务本身有问题」，马上再调大概率还是失败，只会让用户多等一倍时间。HTTP 层面的重试已经交给底层客户端
 * （ollama 下是 {@code spring.ai.retry.*}，openai 下是 {@code spring.ai.openai.max-retries}，见各 yml），这里不再叠加一层。重试只做一次：每次都是一次完整的模型调用，
 * 次数越多越慢、越费 token。
 *
 * <p>所在层：业务层（第 2 章引入）。上游是 {@code ChatService}（每次对话先调它）和 {@link IntentController}；下游是共享的
 * {@link ChatClient} 和提示词文件 {@code prompts/intent-v2.st}（第 5 章加了规则类 POLICY；第 2～4 章用的 intent-v1.st 留着对比）。
 */
@Service
public class IntentService {

    private static final Logger log = LoggerFactory.getLogger(IntentService.class);

    /**
     * 分类要稳定、可复现，温度调到 0；回答用配置文件里的温度。
     *
     * <p>温度（temperature）控制模型选词的随机程度：模型每一步都在给所有候选 token 打分，温度越高越可能选中分数不是最高的那个，
     * 输出更多样；温度为 0 时基本总选分数最高的，同样的输入几乎总得到同样的输出。分类、抽取这种「只有一个正确答案」的任务要的是
     * 稳定，所以用 0；客服回答希望措辞自然一点，用配置文件里的 0.3。录制回放测试也依赖这一点（同一模型连续录几次结果一致）。
     */
    static final double TEMPERATURE = 0.0;

    /**
     * 意图识别最多输出多少个 token（第 7 章补充）：一个意图 JSON 只有四十来个 token。结构化输出约束得住 JSON 的形状、约束不住长度，
     * 第 7 章录制时 qwen2.5:0.5b 识别「订单 … 买错了，帮我取消」时在某个字段里一直写下去，60 秒读超时、整轮对话失败；限住之后最多是这次解析失败，
     * 走原来的「重试一次、再不行按 UNKNOWN」。只限长度，不改提示词和消息，前几章的录制照样能回放。
     */
    static final int MAX_TOKENS = 200;

    /**
     * 用户消息模板：用标签把用户原文圈起来，模型更不容易把里面的话当成给它的指令。
     *
     * <p>{@code {message}} 是模板变量，由 Spring AI 的模板引擎（StringTemplate）在发请求前替换成用户原文。用户原文里即使有
     * 「忽略上面的规则」这样的话，在标签里也更像是「待分类的数据」，这是防提示词注入的常用写法（只能降低风险，不能杜绝）。
     */
    static final String USER_TEMPLATE = """
            请识别下面这条用户消息的意图：
            <message>
            {message}
            </message>""";

    /** 第二次尝试追加的提醒。注意不能写花括号：用户消息模板要经过模板引擎渲染。 */
    static final String STRICT_REMINDER = "注意：上一次的输出无法解析。这一次只输出一个 JSON 对象，不要输出任何解释、前后缀或 Markdown 代码块。";

    private final ChatClient chatClient;
    private final Resource intentPrompt;

    /**
     * 创建意图识别服务。
     *
     * @param chatClient 共享的 ChatClient（见 {@code ChatClientConfig}）
     * @param intentPrompt 意图识别的系统提示词：取值说明 + few-shot 示例（给模型看几个「输入 → 输出」的例子，比纯文字说明更管用）；
     *     测试里可以换成别的文件
     */
    public IntentService(ChatClient chatClient, @Value("classpath:prompts/intent-v2.st") Resource intentPrompt) {
        this.chatClient = chatClient;
        this.intentPrompt = intentPrompt;
    }

    /**
     * 识别一条用户消息的意图，结果已经过 {@link IntentResult#sanitized()} 校验。最多调两次模型，解析失败时兜底为 UNKNOWN。
     *
     * @param message 用户原文
     * @return 校验过的意图，从不返回 null
     * @throws ModelCallException 模型调用本身失败（连不上、超时），不重试、不兜底
     */
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
     * <p>这一串调用拼出的 Prompt：系统消息 = intent-v2.st 的内容；用户消息 = {@link #USER_TEMPLATE} 填入原文；选项 = 温度 0。
     * {@code entity(IntentResult.class, ...)} 做两件事：发请求前根据 record 生成 JSON Schema，收到回答后用 Jackson 转成对象
     * （能容忍模型在 JSON 外面包一层 Markdown 代码块）。{@code useProviderStructuredOutput()} 表示优先把 Schema 作为请求参数交给厂商
     * 原生的结构化输出（Ollama 的 {@code format}），模型在生成每个 token 时就被限制在 Schema 允许的范围内；不支持原生方式的模型，
     * Spring AI 会把格式说明拼进用户消息，只能靠模型「自觉」遵守。
     *
     * @param message 用户原文
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
                    .options(ChatOptions.builder().temperature(TEMPERATURE).maxTokens(MAX_TOKENS))
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
