package io.github.clarence404.assistant.eval;

import io.github.clarence404.assistant.aftersales.AfterSalesStore;
import io.github.clarence404.assistant.aftersales.InMemoryAfterSalesStore;
import io.github.clarence404.assistant.agent.AgentLoop;
import io.github.clarence404.assistant.agent.AgentPlanner;
import io.github.clarence404.assistant.agent.AgentProperties;
import io.github.clarence404.assistant.agent.AgentService;
import io.github.clarence404.assistant.agent.AgentToolbox;
import io.github.clarence404.assistant.agent.ConfirmationService;
import io.github.clarence404.assistant.agent.NativeAgentRunner;
import io.github.clarence404.assistant.agent.WriteActionGuard;
import io.github.clarence404.assistant.chat.ChatProperties;
import io.github.clarence404.assistant.chat.ChatService;
import io.github.clarence404.assistant.guard.InjectionFilter;
import io.github.clarence404.assistant.intent.IntentService;
import io.github.clarence404.assistant.mall.MallApiClient;
import io.github.clarence404.assistant.mall.MallApiProperties;
import io.github.clarence404.assistant.mall.StubMallApi;
import io.github.clarence404.assistant.mcp.client.ExternalMcpTools;
import io.github.clarence404.assistant.memory.MemoryFixtures;
import io.github.clarence404.assistant.memory.QuestionCondenser;
import io.github.clarence404.assistant.ops.ChatMetrics;
import io.github.clarence404.assistant.ops.TurnRecord;
import io.github.clarence404.assistant.ops.UsageAdvisor;
import io.github.clarence404.assistant.prompt.AssistantPrompts;
import io.github.clarence404.assistant.prompt.TestPrompts;
import io.github.clarence404.assistant.rag.RagFixtures;
import io.github.clarence404.assistant.rag.RagProperties;
import io.github.clarence404.assistant.rag.RagService;
import io.github.clarence404.assistant.tool.MallTools;
import io.github.clarence404.assistant.tool.ToolProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 第 9 章评测用的整套被测对象：不启动 Spring，手工组装和线上一样的 ChatService（意图、工具、RAG + 注入过滤、记忆、Agent、Token 记账），
 * 只把对话模型、向量模型换成调用方给的（真实的 Ollama、录制回放），商城换成 {@link StubMallApi}（数据固定，结果可重复）。
 *
 * <p>和线上的差别（讲义里写明）：提示词里的「今天」固定为 2026-11-05（{@link TestPrompts}），录制回放才对得上；Agent 的时钟不走（不会因为总时长停下，
 * 只有步数上限）；知识库是 6 篇规则 + 两篇带提示注入的文档（{@code eval/knowledge/}），内存向量库；不降级（评测要看模型本身的表现）。
 */
final class EvalFixtures {

    /** 评测集。 */
    static final String CASES = "eval/cases-v1.jsonl";

    private EvalFixtures() {}

    /** 读评测集。 */
    static EvalSet evalSet() {
        return EvalSet.load(new ClassPathResource(CASES));
    }

    /**
     * 组装被测对象。
     *
     * @param chatModel 对话模型
     * @param embeddingModel 向量模型
     * @param embeddingName 向量模型名（决定前缀与相似度门槛）
     * @param mall 假商城
     * @param knowledgeDir 放知识文档的空目录
     * @param filter 提示注入过滤器
     * @return 被测对象
     * @throws IOException 复制知识文档失败
     */
    static EvalTarget target(
            ChatModel chatModel,
            EmbeddingModel embeddingModel,
            String embeddingName,
            StubMallApi mall,
            Path knowledgeDir,
            InjectionFilter filter)
            throws IOException {
        copy("classpath:knowledge/*.md", knowledgeDir);
        copy("classpath:eval/knowledge/*.md", knowledgeDir);
        RagFixtures.Kb kb = RagFixtures.knowledgeBase(embeddingModel, embeddingName, knowledgeDir.toUri() + "*.md");
        kb.ingestion().ingest(false);

        // 共享 ChatClient 上挂 Token 记账（和线上一样），回答里才有 usage
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new UsageAdvisor(ChatMetrics.noop()))
                .build();
        double minScore = embeddingName.startsWith("bge-m3") ? 0.6 : RagFixtures.FAKE_MIN_SCORE;
        RagProperties ragProperties = RagFixtures.properties(minScore, true);
        RagService rag = new RagService(
                chatClient,
                RagFixtures.retriever(kb, ragProperties),
                TestPrompts.prompts("v3"),
                filter,
                ChatMetrics.noop());

        MemoryFixtures.Memory memory = MemoryFixtures.memory(chatClient, mall, MemoryFixtures.properties());
        MallApiClient mallApi =
                new MallApiClient(new MallApiProperties(mall.baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(2)));
        MallTools mallTools = new MallTools(mallApi);
        AssistantPrompts prompts = TestPrompts.prompts("v3");
        AgentProperties agentProperties = AgentProperties.defaults(AgentProperties.Mode.LOOP);
        AfterSalesStore afterSales = new InMemoryAfterSalesStore();
        ConfirmationService confirmations = new ConfirmationService(
                mallApi, afterSales, memory.memory(), memory.owners(), agentProperties, memory.clock());
        AgentToolbox toolbox = new AgentToolbox(mallApi, mallTools, rag, agentProperties);
        WriteActionGuard guard = new WriteActionGuard(afterSales);
        AgentLoop loop = new AgentLoop(
                new AgentPlanner(chatClient, prompts, agentProperties), toolbox, guard, confirmations, agentProperties);
        NativeAgentRunner nativeRunner =
                new NativeAgentRunner(chatClient, prompts, toolbox, guard, confirmations, agentProperties);
        AgentService agent = new AgentService(
                loop,
                nativeRunner,
                memory.owners(),
                confirmations,
                agentProperties,
                new ToolProperties(true, true),
                memory.clock());
        ChatService chat = new ChatService(
                chatClient,
                new ChatProperties(Duration.ofSeconds(60), 0.6),
                prompts,
                new IntentService(chatClient, new ClassPathResource("prompts/intent-v2.st")),
                mallTools,
                new ToolProperties(true, true),
                rag,
                memory.memory(),
                new QuestionCondenser(chatClient, MemoryFixtures.properties()),
                agent,
                ExternalMcpTools.none());
        return (message, conversationId, login) ->
                chat.chat(message, conversationId, login ? StubMallApi.TOKEN : null, TurnRecord.detached());
    }

    private static void copy(String pattern, Path dir) throws IOException {
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources(pattern)) {
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, dir.resolve(resource.getFilename()));
            }
        }
    }
}
