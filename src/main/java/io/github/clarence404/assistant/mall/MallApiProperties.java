package io.github.clarence404.assistant.mall;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 调用商城（mall-evolution）接口的配置，前缀 {@code assistant.mall}。
 *
 * <p>所在层：外部系统接入的配置（第 3 章引入）。由 {@code @ConfigurationPropertiesScan} 绑定，注入 {@link MallApiClient}。
 *
 * <p>为什么超时设得这么短（连接 2 秒、读 5 秒），比调模型的 60 秒短得多：工具调用发生在「回答」的中途，模型和用户都在等它。
 * 商城接口正常几十毫秒就返回，5 秒还没回来多半是出了问题，与其让整次对话卡住，不如尽快返回「商城服务暂时不可用」，
 * 让模型照实告诉用户。
 *
 * @param baseUrl 商城网关地址：智能客服和普通前端一样只走网关（9000）的对外接口，不直连 mall-app 或数据库；
 *     可用环境变量 {@code MALL_API_BASE_URL} 覆盖
 * @param connectTimeout 建立 TCP 连接最多等多久：网关没启动时快速失败
 * @param readTimeout 发出请求后等响应最多多久
 */
@ConfigurationProperties("assistant.mall")
public record MallApiProperties(
        @DefaultValue("http://localhost:9000") String baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout) {}
