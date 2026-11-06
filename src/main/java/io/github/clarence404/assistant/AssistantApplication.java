package io.github.clarence404.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 启动类：智能客服应用的入口（第 1 章引入）。
 *
 * <p>整体分层，读代码时可以按这个顺序往下走：
 *
 * <ul>
 *   <li>接口层：{@code chat.ChatController}（{@code /api/chat}、{@code /api/chat/stream}）、{@code intent.IntentController}
 *       （{@code /api/intent}），以及 {@code static/index.html} 聊天页
 *   <li>业务层：{@code chat.ChatService}（意图 → 拒答或回答，回答时可以用工具）、{@code intent.IntentService}（结构化输出的意图识别）、
 *       {@code prompt.AssistantPrompts}（提示词模板）
 *   <li>工具（第 3 章）：{@code tool.MallTools}（查订单、查商品，模型或代码调用）、{@code tool.ToolCallRecorder}（记录每次工具调用）
 *   <li>商城接入（第 3 章）：{@code mall.MallApiClient} 调 mall-evolution 网关，{@code mall.MallLoginController} 是页面登录的代理接口
 *   <li>模型接入：{@code chat.ChatClientConfig} 组装 ChatClient；背后的 ChatModel 由 Spring AI Starter 按 profile 自动配置
 *       （ollama / openai），或由 {@code cli} 包里的 CliChatModel 提供（claude-cli / codex-cli profile）
 *   <li>错误处理：{@code error} 包把各种底层异常归成两类错误码，阻塞接口返回 ProblemDetail，流式接口推 error 事件
 * </ul>
 *
 * <p>{@link ConfigurationPropertiesScan} 让 {@code ChatProperties}、{@code PromptProperties}、{@code ToolProperties}、{@code MallApiProperties} 这些 {@code @ConfigurationProperties}
 * record 不用逐个登记就能绑定 {@code application.yml} 里的 {@code assistant.*} 配置。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AssistantApplication {

    /**
     * 启动 Spring Boot。没指定 profile 时用 {@code application.yml} 里的 {@code spring.profiles.default=ollama}。
     *
     * @param args 命令行参数，如 {@code --spring.profiles.active=openai}
     */
    public static void main(String[] args) {
        SpringApplication.run(AssistantApplication.class, args);
    }
}
