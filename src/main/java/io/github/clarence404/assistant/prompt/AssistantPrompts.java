package io.github.clarence404.assistant.prompt;

import java.time.Clock;
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
 */
@Component
public class AssistantPrompts {

    private static final Logger log = LoggerFactory.getLogger(AssistantPrompts.class);
    private static final DateTimeFormatter TODAY = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA);

    private final PromptProperties properties;
    private final Clock clock;
    private final Resource refusalTemplate = new ClassPathResource("prompts/refusal.st");

    @Autowired
    public AssistantPrompts(PromptProperties properties) {
        this(properties, Clock.system(properties.zone()));
    }

    /** 测试用：传入固定的时钟，「今天」就是确定的。 */
    public AssistantPrompts(PromptProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        // 启动时就确认模板文件存在：版本号写错了应当起不来，而不是等到第一个用户提问才报错
        log.info("系统提示词：{}", systemTemplate().getFilename());
    }

    /** 当前版本的系统提示词模板：{@code classpath:prompts/system-<version>.st}，文件不存在时抛 IllegalStateException。 */
    public Resource systemTemplate() {
        // TODO ch02-1：按 properties.version() 选模板文件 prompts/system-<version>.st（如 v2 → system-v2.st）。
        //  文件不存在时抛 IllegalStateException，消息里带上文件路径：版本号写错了，应用启动时（构造器里会调用本方法）就该失败。
        //  验收：AssistantPromptsTest.versionPropertySelectsTemplateFile / unknownVersionFailsFastAtStartup
        return new ClassPathResource("prompts/system-v1.st");
    }

    /** 渲染模板用的变量。每次请求都重新计算，「今天」才不会停在应用启动那天。 */
    public Map<String, Object> variables() {
        // TODO ch02-2：返回模板里用到的全部变量：shopName、assistantName、supportScope（取自 properties）
        //  和 today（用 clock 取当天日期，按 TODAY 格式化成「2026年11月5日 星期四」）。
        //  现在返回空表，Spring AI 就不渲染模板，模型收到的是带 {shopName} 的原文；少给一个变量则渲染时报错。
        //  验收：AssistantPromptsTest.variablesCarryShopScopeAndTodayInShopTimeZone /
        //  renderedSystemMessageHasNoPlaceholdersLeft / refusalNamesShopAndScope
        return Map.of();
    }

    /** 给一次请求设置系统消息：{@code chatClient.prompt().system(prompts::applySystem)}。 */
    public void applySystem(ChatClient.PromptSystemSpec spec) {
        spec.text(systemTemplate()).params(variables());
    }

    /** 拒答话术：超出客服范围的问题直接回这句，不调模型。 */
    public String refusal() {
        return PromptTemplate.builder()
                .resource(refusalTemplate)
                .variables(variables())
                .build()
                .render()
                .strip();
    }
}
