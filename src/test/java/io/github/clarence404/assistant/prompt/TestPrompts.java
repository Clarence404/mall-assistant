package io.github.clarence404.assistant.prompt;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/** 测试用的提示词配置：与 application.yml 的默认值一致，时钟固定在 2026-11-05（星期四）。 */
public final class TestPrompts {

    public static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 北京时间 2026-11-05 10:00；UTC 是 02:00，同一天。 */
    public static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-05T02:00:00Z"), SHANGHAI);

    public static final String SUPPORT_SCOPE = "商品、下单、支付、物流、退换货与售后、账户与积分";

    private TestPrompts() {}

    public static PromptProperties properties(String version) {
        return new PromptProperties(version, "示例商城", "小示", SUPPORT_SCOPE, SHANGHAI);
    }

    public static AssistantPrompts prompts(String version) {
        return new AssistantPrompts(properties(version), FIXED_CLOCK);
    }
}
