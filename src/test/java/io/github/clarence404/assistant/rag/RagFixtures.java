package io.github.clarence404.assistant.rag;

import io.github.clarence404.assistant.intent.Intent;
import io.github.clarence404.assistant.knowledge.FakeEmbeddingModel;
import io.github.clarence404.assistant.knowledge.KnowledgeEmbedding;
import io.github.clarence404.assistant.knowledge.KnowledgeIngestionService;
import io.github.clarence404.assistant.knowledge.KnowledgeProperties;
import io.github.clarence404.assistant.knowledge.KnowledgeSearchService;
import io.github.clarence404.assistant.knowledge.MarkdownKnowledgeReader;
import io.github.clarence404.assistant.knowledge.MemoryKnowledgeStore;
import io.github.clarence404.assistant.prompt.TestPrompts;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * RAG 测试的公共材料：不带 Spring 的知识库（内存向量库 + 指定的向量模型）、检索器与 RagService（第 5 章引入）。
 *
 * <p>单元测试里的向量模型是 {@link FakeEmbeddingModel}（字面越像越近，结果确定）；它的相似度分布和真实模型完全不同（同一个问题，相关块
 * 多在 0.2～0.35），所以门槛也单独定：{@link #FAKE_MIN_SCORE}。真实模型的门槛见 application.yml 与 docs/ch05.md 的实测。
 */
public final class RagFixtures {

    /** 默认的知识文档（6 篇示例商城规则）。 */
    public static final String KNOWLEDGE = "classpath:knowledge/*.md";

    /** 假向量模型下的相似度门槛。 */
    public static final double FAKE_MIN_SCORE = 0.25;

    private RagFixtures() {}

    /**
     * 和 application.yml 一样的配置，只有相似度门槛按假向量模型调整。
     *
     * @return 配置
     */
    public static RagProperties properties() {
        return properties(FAKE_MIN_SCORE, true);
    }

    /**
     * 指定门槛与是否混合检索，其余同 application.yml。
     *
     * @param minScore 向量相似度门槛
     * @param hybrid 是否混合检索
     * @return 配置
     */
    public static RagProperties properties(double minScore, boolean hybrid) {
        return new RagProperties(
                true,
                List.of(Intent.AFTER_SALES, Intent.POLICY),
                4,
                10,
                minScore,
                0.6,
                1500,
                hybrid,
                60,
                false,
                80,
                "rag-v1");
    }

    /**
     * 一套不带 Spring 的知识库：内存向量库、不持久化，切块 300 / 50（和 application.yml 一样）。
     *
     * @param model 向量模型（单元测试用假的，集成测试用真实的 Ollama）
     * @param modelName 向量模型名（决定前缀）
     * @param location 文档位置
     * @return 知识库
     */
    public static Kb knowledgeBase(EmbeddingModel model, String modelName, String location) {
        KnowledgeProperties properties = new KnowledgeProperties(
                KnowledgeProperties.StoreType.MEMORY,
                location,
                false,
                modelName,
                0,
                new KnowledgeProperties.Chunk(300, 50),
                new KnowledgeProperties.Search(4, 10),
                new KnowledgeProperties.Memory(""),
                new KnowledgeProperties.PgVector(
                        "jdbc:postgresql://localhost:5432/mall_assistant", "mall", "", "public", "", true, 4),
                List.of(
                        new KnowledgeProperties.ModelProfile("nomic-embed-text", "search_query: ", "search_document: "),
                        new KnowledgeProperties.ModelProfile("bge-m3", "", "")));
        KnowledgeEmbedding embedding = new KnowledgeEmbedding(model, properties);
        MemoryKnowledgeStore store = new MemoryKnowledgeStore(embedding, null);
        KnowledgeIngestionService ingestion = new KnowledgeIngestionService(
                new MarkdownKnowledgeReader(properties.location()), store, embedding, properties);
        return new Kb(store, ingestion, new KnowledgeSearchService(store, embedding, properties));
    }

    /**
     * 假向量模型 + 6 篇示例文档，已入库。
     *
     * @return 知识库
     */
    public static Kb fakeKnowledgeBase() {
        Kb kb = knowledgeBase(new FakeEmbeddingModel(), "fake-ngram", KNOWLEDGE);
        kb.ingestion().ingest(false);
        return kb;
    }

    /**
     * 检索器。
     *
     * @param kb 知识库
     * @param properties RAG 配置
     * @return 检索器
     */
    public static RagRetriever retriever(Kb kb, RagProperties properties) {
        return new RagRetriever(kb.search(), kb.store(), properties);
    }

    /**
     * RagService：提示词变量用测试的固定日期（2026-11-05）。
     *
     * @param chatClient ChatClient
     * @param kb 知识库
     * @param properties RAG 配置
     * @return RagService
     */
    public static RagService service(ChatClient chatClient, Kb kb, RagProperties properties) {
        return new RagService(chatClient, retriever(kb, properties), TestPrompts.prompts("v3"));
    }

    /**
     * 关掉的 RagService：前几章的测试组装 ChatService 时用，任何意图都不走 RAG，也不会碰知识库。
     *
     * @param chatClient ChatClient
     * @return RagService
     */
    public static RagService disabled(ChatClient chatClient) {
        RagProperties off = new RagProperties(false, List.of(), 4, 10, 0.6, 0.6, 1500, true, 60, false, 80, "rag-v1");
        Kb kb = knowledgeBase(new FakeEmbeddingModel(), "fake-ngram", KNOWLEDGE);
        return new RagService(chatClient, retriever(kb, off), TestPrompts.prompts("v3"));
    }

    /**
     * 一套知识库。
     *
     * @param store 内存向量库
     * @param ingestion 入库
     * @param search 相似度检索
     */
    public record Kb(MemoryKnowledgeStore store, KnowledgeIngestionService ingestion, KnowledgeSearchService search) {}
}
