package io.github.clarence404.assistant.prompt;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 提示词相关配置，前缀 {@code assistant.prompt}。
 *
 * <p>所在层：业务层的配置（第 2 章引入）。由 {@code @ConfigurationPropertiesScan} 绑定，注入 {@link AssistantPrompts}。
 * 默认值与 application.yml 里写的一致；可以用环境变量覆盖，如 {@code ASSISTANT_PROMPT_VERSION=v1}（Spring Boot 的宽松绑定）。
 *
 * @param version 系统提示词版本，对应 {@code prompts/system-<version>.st}，如 v1、v2；改提示词时新增一个版本，旧版本留着对比与回滚
 * @param shopName 商城名，渲染进系统提示词与拒答话术
 * @param assistantName 客服的名字
 * @param supportScope 客服能回答的范围，一句话列出
 * @param zone 计算「今天」用的时区：服务器常跑在 UTC，按商城所在地算日期
 */
@ConfigurationProperties("assistant.prompt")
public record PromptProperties(
        @DefaultValue("v2") String version,
        @DefaultValue("示例商城") String shopName,
        @DefaultValue("小示") String assistantName,
        @DefaultValue("商品、下单、支付、物流、退换货与售后、账户与积分") String supportScope,
        @DefaultValue("Asia/Shanghai") ZoneId zone) {}
