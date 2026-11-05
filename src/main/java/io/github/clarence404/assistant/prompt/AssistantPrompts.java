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
        String path = "prompts/system-" + properties.version() + ".st";
        Resource template = new ClassPathResource(path);
        if (!template.exists()) {
            throw new IllegalStateException(
                    "找不到系统提示词模板 " + path + "，检查 assistant.prompt.version=" + properties.version());
        }
        return template;
    }

    /** 渲染模板用的变量。每次请求都重新计算，「今天」才不会停在应用启动那天。 */
    public Map<String, Object> variables() {
        return Map.of(
                "shopName", properties.shopName(),
                "assistantName", properties.assistantName(),
                "supportScope", properties.supportScope(),
                "today", LocalDate.now(clock).format(TODAY));
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
