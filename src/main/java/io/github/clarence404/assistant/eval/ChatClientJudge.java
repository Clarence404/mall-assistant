package io.github.clarence404.assistant.eval;

import io.github.clarence404.assistant.ops.UsageAdvisor;
import io.github.clarence404.assistant.rag.RagSource;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 用一个 ChatClient 当裁判：把资料、问题、回答交给它，要它按「忠实度」打 1～5 分（第 9 章引入）。
 *
 * <p>所在层：评测。背后的模型由调用方决定——真实评测（{@code EvalIT}）里用环境变量 {@code EVAL_JUDGE} 选：Ollama 上更大的模型，或者本机的 Claude CLI
 * （{@code claude-cli}，第 1 章扩展的命令行模型）。被评的小模型和裁判最好不是同一个：自己给自己打分，偏差最大。
 *
 * <p>提示词的写法：给出明确的评分标准（每一档什么样），要求先找依据再给分，温度 0；打分只看「忠实」和「答没答」，不看文笔——文笔是最容易让裁判偏心的地方。
 *
 * <p><b>为什么不用结构化输出</b>：第一版用了 {@code entity(Verdict.class)}，换成 Claude CLI（haiku）当裁判时，它先写一段 Markdown 分析、结构化输出的
 * 重试用完也没交出合格的 JSON，几乎每条都解析失败。「先分析再给分」本来就是裁判提示词想要的，所以改成：让它随便分析，最后一行只写一个 JSON 对象，
 * 代码从回答里找最后一个 {@code {"score": …}} 解析、校验分数范围。裁判的输出和被评模型的输出一样，是不可信的、要自己解析和校验的数据。
 */
public class ChatClientJudge implements EvalJudge {

    private static final Logger log = LoggerFactory.getLogger(ChatClientJudge.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 回答里最后一个带 score 的 JSON 对象（不跨行嵌套，裁判的结论就一行）。 */
    private static final Pattern VERDICT = Pattern.compile("\\{[^{}]*\"score\"[^{}]*}");

    /** 裁判的系统提示词。 */
    static final String SYSTEM = """
            你是一名严格的评审员，检查电商客服的回答是否忠于给定的参考资料。
            评分标准（只看忠实度和是否回答了问题，不看文笔）：
            5 分：每个说法都能在资料里找到依据，并且回答了用户的问题；
            4 分：基本忠于资料、回答了问题，有少量资料里没有但不矛盾的客套话；
            3 分：回答了问题，但有个别说法在资料里找不到依据；
            2 分：有和资料矛盾的说法，或编造了资料里没有的规则、数字、时限；
            1 分：大部分是编造的，或者没有回答问题。
            先简短地逐条找依据，最后一行只输出一个 JSON 对象，不要代码块：{"score": 1 到 5 的整数, "reason": "一句中文说明扣分点（满分就说依据在哪一条）"}""";

    /** 交给裁判的用户消息。 */
    static final String USER = """
            <参考资料>
            {sources}
            </参考资料>

            <用户问题>{question}</用户问题>

            <客服回答>
            {answer}
            </客服回答>""";

    private final ChatClient chatClient;

    /**
     * 创建。
     *
     * @param chatClient 裁判模型的 ChatClient
     */
    public ChatClientJudge(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public Verdict judge(String question, List<RagSource> sources, String answer) {
        String context =
                sources.stream().map(s -> "[" + s.index() + "] " + s.content()).collect(Collectors.joining("\n\n"));
        try {
            String output = chatClient
                    .prompt()
                    .system(SYSTEM)
                    .user(u -> u.text(USER)
                            .param("sources", context)
                            .param("question", question)
                            .param("answer", answer))
                    .options(ChatOptions.builder().temperature(0.0))
                    .advisors(UsageAdvisor.stage("judge"))
                    .call()
                    .content();
            Verdict verdict = parse(output);
            if (verdict == null) {
                log.warn("裁判没有给出有效的分数：{}", abbreviate(output));
            }
            return verdict;
        } catch (RuntimeException e) {
            log.warn("裁判打分失败：{}", e.toString());
            return null;
        }
    }

    /**
     * 从裁判的回答里取出结论：最后一个带 {@code "score"} 的 JSON 对象，分数必须是 1～5。
     *
     * @param output 裁判的原样输出
     * @return 结论；找不到或不合格时为 null
     */
    static Verdict parse(String output) {
        if (output == null) {
            return null;
        }
        Matcher matcher = VERDICT.matcher(output);
        String last = null;
        while (matcher.find()) {
            last = matcher.group();
        }
        if (last == null) {
            return null;
        }
        try {
            Verdict verdict = JSON.readValue(last, Verdict.class);
            return verdict.score() >= 1 && verdict.score() <= 5 ? verdict : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }
}
