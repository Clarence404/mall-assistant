package io.github.clarence404.assistant.ops;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Token 用量的配置，前缀 {@code assistant.usage}（第 9 章引入）。
 *
 * <p>所在层：上线运维的配置。{@link UsageLedger}（记账、每日预算）读它。
 *
 * @param dailyTokenBudget 每个调用方（登录用户按商城用户，匿名按 IP）每天最多用多少 Token（输入 + 输出）；0 = 不限。到了之后对话接口返回
 *     TOKEN_BUDGET_EXCEEDED（429），第二天（按 {@code zone} 的零点）自动恢复
 * @param zone 「一天」按哪个时区算
 * @param maxConversations 按会话汇总时最多记多少个会话（超了丢最早的），防止内存无限增长
 */
@ConfigurationProperties("assistant.usage")
public record UsageProperties(
        @DefaultValue("100000") long dailyTokenBudget,
        @DefaultValue("Asia/Shanghai") ZoneId zone,
        @DefaultValue("500") int maxConversations) {

    /**
     * 默认值（测试里手工组装时用）。
     *
     * @param dailyTokenBudget 每日预算
     * @return 配置
     */
    public static UsageProperties of(long dailyTokenBudget) {
        return new UsageProperties(dailyTokenBudget, ZoneId.of("Asia/Shanghai"), 500);
    }
}
