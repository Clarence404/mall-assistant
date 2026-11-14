package io.github.clarence404.assistant.prompt;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * 客服的提示词模板：按 {@code assistant.prompt.version} 选系统提示词文件，每次请求时用当前变量渲染。
 *
 * <p>模板放在 {@code src/main/resources/prompts/}，占位符写成 {@code {shopName}} 这样（StringTemplate 语法），由 Spring AI
 * 在发请求前填值。
 *
 * <p>所在层：业务层（第 2 章引入；第 1 章的人设是 {@code prompts/system.st}，在 {@code ChatClientConfig} 里用 {@code defaultSystem} 固定设置，没有变量）。上游是 {@code ChatService}
 * （回答时设置系统消息、拒答时取话术）；下游是 classpath 下的模板文件与 {@link PromptProperties}。
 *
 * <p>为什么把提示词做成「文件 + 变量 + 版本」：
 *
 * <ul>
 *   <li>文件：提示词是要反复打磨的「产品文案」，放在 .st 文件里比写在 Java 字符串里好改、好 diff
 *   <li>变量：商城名、客服名、服务范围、今天的日期这些会变的东西做成占位符，改配置不用改模板；日期每次请求重新算
 *   <li>版本：改提示词就新增 {@code system-v3.st}，旧文件留着，用 {@code assistant.prompt.version} 切换，效果变差可以立刻切回，
 *       也方便拿同一批问题对比两版的回答
 * </ul>
 *
 * <p>目前有哪些模板、各自的用途、由谁加载、用到哪些变量，见同目录的说明 {@code src/main/resources/prompts/README.md}
 * （模板文件里不能写注释，否则会一起发给模型，所以说明都写在那里）。
 */
@Component
public class AssistantPrompts {

    private static final Logger log = LoggerFactory.getLogger(AssistantPrompts.class);
    private static final DateTimeFormatter TODAY = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA);

    private final PromptProperties properties;
    private final Clock clock;
    private final Resource refusalTemplate = new ClassPathResource("prompts/refusal.st");

    /**
     * Spring 用的构造器：「今天」按 {@code assistant.prompt.zone} 时区的系统时钟计算。
     *
     * @param properties 提示词配置
     * @throws IllegalStateException 配置的版本找不到对应模板文件，应用启动失败
     */
    @Autowired
    public AssistantPrompts(PromptProperties properties) {
        this(properties, Clock.system(properties.zone()));
    }

    /**
     * 测试用：传入固定的时钟，「今天」就是确定的。
     *
     * @param properties 提示词配置
     * @param clock 计算「今天」用的时钟
     * @throws IllegalStateException 配置的版本找不到对应模板文件
     */
    public AssistantPrompts(PromptProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        // 启动时就确认模板文件存在：版本号写错了应当起不来，而不是等到第一个用户提问才报错
        log.info("系统提示词：{}", systemTemplate().getFilename());
    }

    /**
     * 当前版本的系统提示词模板：{@code classpath:prompts/system-<version>.st}，文件不存在时抛 IllegalStateException。
     *
     * @return 模板文件（未渲染，占位符还在）
     * @throws IllegalStateException 文件不存在
     */
    public Resource systemTemplate() {
        String path = "prompts/system-" + properties.version() + ".st";
        Resource template = new ClassPathResource(path);
        if (!template.exists()) {
            throw new IllegalStateException(
                    "找不到系统提示词模板 " + path + "，检查 assistant.prompt.version=" + properties.version());
        }
        return template;
    }

    /**
     * 渲染模板用的变量。每次请求都重新计算，「今天」才不会停在应用启动那天。
     *
     * <p>为什么要告诉模型今天的日期：模型的知识停在训练数据截止的那天，自己不知道「现在」是什么时候，问到「今天发货明天能到吗」
     * 之类的问题容易答错。模板里没用到的变量（如 v1 不用 today）多传了也没关系。
     *
     * @return 变量名 → 值，键名与模板里的占位符一一对应
     */
    public Map<String, Object> variables() {
        return Map.of(
                "shopName", properties.shopName(),
                "assistantName", properties.assistantName(),
                "supportScope", properties.supportScope(),
                "today", LocalDate.now(clock).format(TODAY));
    }

    /**
     * 给一次请求设置系统消息：{@code chatClient.prompt().system(prompts::applySystem)}。
     *
     * <p>只把模板和变量交给 Spring AI，真正的替换在发请求前由它完成（模板里有占位符却没给值时会报错）。
     *
     * @param spec ChatClient 提供的系统消息配置器
     */
    public void applySystem(ChatClient.PromptSystemSpec spec) {
        spec.text(systemTemplate()).params(variables());
    }

    /**
     * 拒答话术：超出客服范围的问题直接回这句，不调模型。
     *
     * <p>这里自己用 {@link PromptTemplate} 渲染：话术是直接给用户看的固定文本，不需要经过模型。
     *
     * @return 渲染好的话术，去掉了首尾空白（模板文件末尾的换行）
     */
    public String refusal() {
        return render(refusalTemplate);
    }

    /**
     * 渲染一段直接给用户看的固定话术（不经过模型），变量同 {@link #variables()}。第 5 章 RAG 的「没找到相关规定」「规则库暂时查不到」也用它。
     *
     * @param template 模板文件
     * @return 渲染好的话术，去掉了首尾空白
     */
    public String render(Resource template) {
        return PromptTemplate.builder()
                .resource(template)
                .variables(variables())
                .build()
                .render()
                .strip();
    }
}
