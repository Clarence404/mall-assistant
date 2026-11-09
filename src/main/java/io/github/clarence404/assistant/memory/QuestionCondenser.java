package io.github.clarence404.assistant.memory;

import io.github.clarence404.assistant.rag.Terms;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * 追问改写（condense question）：先让模型结合对话历史，把追问改写成一句不依赖上文的独立问题，再拿它去识别意图、去检索（第 6 章引入）。
 *
 * <p>所在层：业务层。上游是 {@code ChatService#begin}（有历史时，每一轮开头调一次）；下游是共享的 {@link ChatClient} 和提示词
 * {@code prompts/memory-condense.st}。
 *
 * <p><b>为什么要改写</b>：先问「七天无理由退货要什么条件」，再问「那运费谁出」。第二句拿去检索，向量和关键词都只看得到「运费」两个字，
 * 很可能检索到「买多少钱包邮」（发货运费），而不是「退货运费」；检索错了，模型再聪明也只能照着错的资料答。意图识别也一样只看这一句：实测 1.5b
 * 把「那能抵多少钱」分成了问商品（PRODUCT_INFO），不走 RAG，然后凭常识编了一个「1000 积分 = 1 元」。检索器和意图识别都没有记忆，它们只认得你给的这一句话，
 * 所以「上文」必须由改写这一步翻译进问题里：「七天无理由退货的运费谁出」「积分能抵多少钱」。
 *
 * <p>为什么不直接把历史拼在问题前面去检索：几轮历史混在一起，向量会被前面的话题「冲淡」，关键词检索也会被历史里的词带偏；换了话题时更糟。
 *
 * <p><b>小模型改写得并不好</b>（实测见 docs/ch06.md）：会照抄示例、会把新话题改回旧话题（「密码输错几次会被锁」→「七天无理由退货要什么条件」）、
 * 会把上文的回答塞进来。所以：
 *
 * <ul>
 *   <li>示例用几组「历史 → 最新一句 → 改写」的对话轮次给（few-shot），而且故意选和商城规则无关的话题（会员等级、快递公司、道谢），模型照抄了也不至于答非所问
 *   <li>结果要校验（{@link #plausible}）：原话里的关键词（{@link Terms} 切出来的词）至少六成要还在改写结果里，不然就是改跑题了，退回原话
 * </ul>
 *
 * <p>代价：每个追问多一次模型调用（本地 1.5b 约 0.5～2 秒，命令行模型 3～7 秒），所以有开关 {@code assistant.memory.condense.enabled}；温度 0，
 * 同样的历史得到同样的改写。改写失败（模型挂了、超时、输出明显不对）不影响回答，退回原话。
 */
@Component
public class QuestionCondenser {

    private static final Logger log = LoggerFactory.getLogger(QuestionCondenser.class);

    /** 改写和意图识别一样要稳定：温度 0。 */
    static final double TEMPERATURE = 0.0;

    /**
     * 用户消息模板：最近几轮对话 + 最新一句。历史用标签圈起来，里面的话只是待改写的材料。
     *
     * <p>{@code {history}} 和 {@code {question}} 是模板变量，值里的花括号不会再被当成占位符。
     */
    static final String USER_TEMPLATE = """
            <对话历史>
            {history}
            </对话历史>
            最新一句：{question}
            改写后的问题：""";

    /** 示例里的历史、最新一句和改写结果：放在系统消息之后，一组是一问一答两条消息。话题故意和商城规则无关，见类注释。 */
    static final List<List<String>> EXAMPLES = List.of(
            List.of("用户：会员等级怎么划分\n助手：按近 12 个月的消费金额分为普通、银卡、金卡三级。", "那怎么升级", "会员等级怎么升级"),
            List.of("用户：订单 1849203385720832512 发货了吗\n助手：这笔订单已发货。", "它是哪家快递", "订单 1849203385720832512 是哪家快递"),
            List.of("用户：会员等级怎么划分\n助手：按近 12 个月的消费金额分为三级。", "谢谢", "谢谢"));

    /** 原话里的关键词至少有这么大比例出现在改写结果里，才算没改跑题。 */
    static final double MIN_TERM_COVERAGE = 0.6;

    /** 给模型看的历史里，每条消息最多多少个字：助手的长回答截短，改写只需要知道在聊什么。 */
    static final int MESSAGE_PREVIEW_CHARS = 200;

    /** 小模型有时会在结果前面加上「改写后的问题：」之类的前缀，或者用引号括起来。 */
    private static final Pattern LABEL = Pattern.compile("^(?:改写后的问题|改写后|问题|最新一句)\\s*[:：]\\s*");

    private static final Pattern QUOTES = Pattern.compile("^[「“\"'『]+|[」”\"'』]+$");

    private final ChatClient chatClient;
    private final MemoryProperties properties;
    private final Resource systemPrompt = new ClassPathResource("prompts/memory-condense.st");

    /**
     * 创建。
     *
     * @param chatClient 共享的 ChatClient
     * @param properties {@code assistant.memory.*}（开关、看几轮、结果最长多少字）
     */
    public QuestionCondenser(ChatClient chatClient, MemoryProperties properties) {
        this.chatClient = chatClient;
        this.properties = properties;
    }

    /**
     * 把追问改写成独立问题。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>开关关着、或者没有历史：不调模型，返回 null
     *   <li>否则调一次模型：系统消息 = {@code memory-condense.st}；接着是 {@link #examples()}（示例的一问一答）；最后的用户消息 =
     *       {@link #USER_TEMPLATE}，{@code history} 是最近 {@code condense.history-turns} 轮的 {@link #transcript}，{@code question} 是用户这一句；
     *       温度 {@link #TEMPERATURE}；不注册工具
     *   <li>模型的输出用 {@link #clean} 清理；清理后为空、超过 {@code condense.max-chars} 个字、和原话一样、或者 {@link #plausible} 不通过，都返回 null
     *   <li>模型调用失败（任何 RuntimeException）：记一行 warn，返回 null，不往外抛
     * </ul>
     *
     * @param message 用户这一句（原话）
     * @param history 带给模型的历史，按时间从早到晚
     * @return 改写后的问题；没改写时为 null（调用方就用原话）
     */
    public String condense(String message, List<Message> history) {
        // TODO ch06-3：有历史、开关开着时调一次模型（系统提示词 + examples() + USER_TEMPLATE，温度 0），clean 之后按四种情况返回 null，失败不抛
        return null;
    }

    /**
     * 示例：每组一条用户消息（填好模板的历史和最新一句）+ 一条助手消息（改写结果）。
     *
     * @return 示例消息，按顺序
     */
    static List<Message> examples() {
        List<Message> messages = new ArrayList<>();
        for (List<String> example : EXAMPLES) {
            messages.add(new UserMessage(
                    USER_TEMPLATE.replace("{history}", example.get(0)).replace("{question}", example.get(1))));
            messages.add(new AssistantMessage(example.get(2)));
        }
        return messages;
    }

    /**
     * 改写结果是否可信：原话的关键字（{@link Terms#of} 切出的单个汉字和数字、英文词，去掉「的、那、吗」等）至少 {@link #MIN_TERM_COVERAGE}
     * 出现在结果里。改写只应该「补」上文，不应该「换」掉用户问的东西。只看单字不看双字词：「运费谁出」改写成「运费由谁承担」是正常的换说法，
     * 双字词「谁出」对不上，单字「运、费、谁」都还在。原话没有关键字（全是语气词）时不做要求。
     *
     * @param original 原话
     * @param condensed 改写结果
     * @return 可信时为 true
     */
    static boolean plausible(String original, String condensed) {
        Set<String> terms = new LinkedHashSet<>();
        for (String term : Terms.of(original)) {
            if (term.length() == 1 || Character.UnicodeScript.of(term.charAt(0)) != Character.UnicodeScript.HAN) {
                terms.add(term);
            }
        }
        if (terms.isEmpty()) {
            return true;
        }
        String lower = condensed.toLowerCase(java.util.Locale.ROOT);
        long kept = terms.stream().filter(lower::contains).count();
        return (double) kept / terms.size() >= MIN_TERM_COVERAGE;
    }

    /**
     * 最近几轮历史写成「用户：…」「助手：…」一行一条，每条最多 {@link #MESSAGE_PREVIEW_CHARS} 个字。
     *
     * @param history 历史
     * @param turns 取最近几轮
     * @return 对话文本
     */
    static String transcript(List<Message> history, int turns) {
        List<List<Message>> all = HistoryWindow.turns(history);
        return all.subList(Math.max(0, all.size() - turns), all.size()).stream()
                .flatMap(List::stream)
                .map(m -> (m.getMessageType() == MessageType.USER ? "用户：" : "助手：") + preview(MemoryMessages.text(m)))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 清理模型的输出：取第一行非空的文字，去掉「改写后的问题：」之类的前缀和首尾引号。
     *
     * @param output 模型原样输出，可以为 null
     * @return 清理后的文字，可能为空串
     */
    static String clean(String output) {
        if (output == null) {
            return "";
        }
        String line = output.strip()
                .lines()
                .map(String::strip)
                .filter(l -> !l.isEmpty())
                .findFirst()
                .orElse("");
        line = LABEL.matcher(line).replaceFirst("");
        return QUOTES.matcher(line).replaceAll("").strip();
    }

    private static String preview(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= MESSAGE_PREVIEW_CHARS ? oneLine : oneLine.substring(0, MESSAGE_PREVIEW_CHARS) + "…";
    }
}
