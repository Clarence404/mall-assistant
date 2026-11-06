package io.github.clarence404.assistant.prompt;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 测试用的提示词配置：与 application.yml 的默认值一致，时钟固定在 2026-11-05（星期四）。第 2 章引入。
 *
 * <p>系统提示词里有「今天是几号」，如果用真实时钟，断言每天都要变；注入一个固定的 {@link Clock}，渲染结果就是确定的。
 * 不启动 Spring 的单元测试（如 ChatServiceTest、AssistantPromptsTest）用它直接 new 出 {@link AssistantPrompts}。
 */
public final class TestPrompts {

    public static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 北京时间 2026-11-05 10:00；UTC 是 02:00，同一天。 */
    public static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-05T02:00:00Z"), SHANGHAI);

    public static final String SUPPORT_SCOPE = "商品、下单、支付、物流、退换货与售后、账户与积分";

    private TestPrompts() {}

    /** 指定版本的提示词配置，其余变量与 application.yml 相同。 */
    public static PromptProperties properties(String version) {
        return new PromptProperties(version, "示例商城", "小示", SUPPORT_SCOPE, SHANGHAI);
    }

    /** 指定版本、固定时钟的 {@link AssistantPrompts}。版本对应的模板文件不存在时，构造即抛出异常。 */
    public static AssistantPrompts prompts(String version) {
        return new AssistantPrompts(properties(version), FIXED_CLOCK);
    }
}
