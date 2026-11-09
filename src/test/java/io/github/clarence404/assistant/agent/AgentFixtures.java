package io.github.clarence404.assistant.agent;

import io.github.clarence404.assistant.aftersales.AfterSalesStore;
import io.github.clarence404.assistant.aftersales.InMemoryAfterSalesStore;
import io.github.clarence404.assistant.chat.ChatClientConfig;
import io.github.clarence404.assistant.chat.ChatProperties;
import io.github.clarence404.assistant.chat.ChatService;
import io.github.clarence404.assistant.chat.FakeChatModel;
import io.github.clarence404.assistant.intent.IntentService;
import io.github.clarence404.assistant.mall.MallApiClient;
import io.github.clarence404.assistant.mall.MallApiProperties;
import io.github.clarence404.assistant.mall.StubMallApi;
import io.github.clarence404.assistant.memory.ConversationOwner;
import io.github.clarence404.assistant.memory.MemoryFixtures;
import io.github.clarence404.assistant.memory.QuestionCondenser;
import io.github.clarence404.assistant.prompt.AssistantPrompts;
import io.github.clarence404.assistant.prompt.TestPrompts;
import io.github.clarence404.assistant.rag.RagFixtures;
import io.github.clarence404.assistant.rag.RagService;
import io.github.clarence404.assistant.tool.MallTools;
import io.github.clarence404.assistant.tool.ToolProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.io.ClassPathResource;

/**
 * 第 7 章测试的公共材料：不启动 Spring，手工组装一整套带 Agent 的 ChatService（第 6 章 {@link MemoryFixtures} 的做法）。
 *
 * <p>模型是 {@link FakeChatModel}（或录制回放时的回放模型），知识库是假向量模型 + 6 篇真实规则文档，商城是 {@link StubMallApi}，记忆、售后申请单都在内存里，
 * 时钟可以拨动（测确认过期）。配置同 application.yml：最多 6 步、同一调用 2 次、确认 5 分钟有效。
 */
public final class AgentFixtures {

    /** 演示账号在假商城里的归属。 */
    public static final ConversationOwner DEMO_USER = ConversationOwner.user("10001");

    private AgentFixtures() {}

    /**
     * 默认的一套：假模型、假知识库、方案 B。
     *
     * @param model 假模型
     * @param mall 假商城
     * @return 一套
     */
    public static Agent agent(FakeChatModel model, StubMallApi mall) {
        ChatClient chatClient = new ChatClientConfig().chatClient(ChatClient.builder(model));
        return agent(
                chatClient,
                RagFixtures.service(chatClient, RagFixtures.fakeKnowledgeBase(), RagFixtures.properties()),
                mall,
                AgentProperties.defaults(AgentProperties.Mode.LOOP));
    }

    /**
     * 指定模型、RAG（录制回放、提示注入测试用）和配置。
     *
     * @param model 模型
     * @param rag RAG 服务
     * @param mall 假商城
     * @param properties Agent 配置
     * @return 一套
     */
    public static Agent agent(ChatModel model, RagService rag, StubMallApi mall, AgentProperties properties) {
        return agent(new ChatClientConfig().chatClient(ChatClient.builder(model)), rag, mall, properties);
    }

    private static Agent agent(ChatClient chatClient, RagService rag, StubMallApi mall, AgentProperties properties) {
        MemoryFixtures.Memory memory = MemoryFixtures.memory(chatClient, mall, MemoryFixtures.properties());
        MallApiClient mallApi =
                new MallApiClient(new MallApiProperties(mall.baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(2)));
        MallTools mallTools = new MallTools(mallApi);
        AssistantPrompts prompts = TestPrompts.prompts("v3");
        AfterSalesStore afterSales = new InMemoryAfterSalesStore();
        ConfirmationService confirmations = new ConfirmationService(
                mallApi, afterSales, memory.memory(), memory.owners(), properties, memory.clock());
        AgentToolbox toolbox = new AgentToolbox(mallApi, mallTools, rag, properties);
        WriteActionGuard guard = new WriteActionGuard(afterSales);
        AgentLoop loop = new AgentLoop(
                new AgentPlanner(chatClient, prompts, properties), toolbox, guard, confirmations, properties);
        NativeAgentRunner nativeRunner =
                new NativeAgentRunner(chatClient, prompts, toolbox, guard, confirmations, properties);
        AgentService agentService = new AgentService(
                loop,
                nativeRunner,
                memory.owners(),
                confirmations,
                properties,
                new ToolProperties(true, true),
                memory.clock());
        ChatService chat = new ChatService(
                chatClient,
                new ChatProperties(Duration.ofSeconds(5), 0.6),
                prompts,
                new IntentService(chatClient, new ClassPathResource("prompts/intent-v2.st")),
                mallTools,
                new ToolProperties(true, true),
                rag,
                memory.memory(),
                new QuestionCondenser(chatClient, MemoryFixtures.properties()),
                agentService);
        return new Agent(chat, agentService, confirmations, afterSales, memory);
    }

    /**
     * 一套带 Agent 的对话服务与它的零件。
     *
     * @param chat ChatService（入口）
     * @param service AgentService
     * @param confirmations 确认服务（模拟用户点按钮）
     * @param afterSales 售后申请单存储
     * @param memory 记忆（断言写回了什么、拨时钟）
     */
    public record Agent(
            ChatService chat,
            AgentService service,
            ConfirmationService confirmations,
            AfterSalesStore afterSales,
            MemoryFixtures.Memory memory) {

        /**
         * 直接让 Agent 办一件事（不经过意图识别），收集推出的事件。
         *
         * @param message 用户原话
         * @param token 商城令牌
         * @param conversationId 会话 ID，可以为 null
         * @param events 收集事件的列表
         * @return 结果
         */
        public AgentResult run(String message, String token, String conversationId, List<AgentEvent> events) {
            return service.run(message, List.of(), token, conversationId, events::add, () -> false);
        }
    }

    /**
     * 一步 Agent 输出的 JSON（测试里拼模型输出用），四个字段和 {@link AgentDecision} 一样：action、input、orderNo、thought。
     *
     * @param action 工具名
     * @param fields 参数：键、值交替，如 {@code "orderNo", "2118...", "address", "杭州市…"}；orderNo 之外的键（query、address、reason、
     *     answer、skuId）只是为了测试读起来清楚，值都放进 input
     * @return JSON
     */
    public static String act(String action, String... fields) {
        String orderNo = "";
        String input = "";
        for (int i = 0; i + 1 < fields.length; i += 2) {
            if ("orderNo".equals(fields[i])) {
                orderNo = fields[i + 1];
            } else {
                input = fields[i + 1];
            }
        }
        return "{\"action\":\"" + action + "\",\"input\":\"" + input + "\",\"orderNo\":\"" + orderNo
                + "\",\"thought\":\"" + action + " 这一步\"}";
    }
}
