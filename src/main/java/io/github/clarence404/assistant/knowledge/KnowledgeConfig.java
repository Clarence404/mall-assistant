package io.github.clarence404.assistant.knowledge;

import java.nio.file.Path;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识库的公共装配：向量模型信息、文档读取器，以及默认的内存向量库（第 4 章引入）。pgvector 的装配见 {@link PgVectorConfig}。
 *
 * <p>所在层：知识库的配置。下游是 Spring AI 自动配置的 {@link EmbeddingModel}：哪家的模型由各 profile 的
 * {@code spring.ai.model.embedding} 决定（ollama / openai），命令行模型的 profile 也用 Ollama 的向量模型。
 */
@Configuration(proxyBeanMethods = false)
public class KnowledgeConfig {

    /**
     * 当前向量模型。用 {@link ObjectProvider} 取：{@code spring.ai.model.embedding=none} 时容器里没有 EmbeddingModel，
     * 应用照常启动，知识库接口返回 503。
     *
     * @param embeddingModels 容器里的向量模型（可能没有）
     * @param properties 知识库配置
     * @return 向量模型信息
     */
    @Bean
    public KnowledgeEmbedding knowledgeEmbedding(
            ObjectProvider<EmbeddingModel> embeddingModels, KnowledgeProperties properties) {
        return new KnowledgeEmbedding(embeddingModels.getIfUnique(), properties);
    }

    /**
     * 知识文档读取器。
     *
     * @param properties 知识库配置（{@code location}）
     * @return 读取器
     */
    @Bean
    public MarkdownKnowledgeReader markdownKnowledgeReader(KnowledgeProperties properties) {
        return new MarkdownKnowledgeReader(properties.location());
    }

    /**
     * 默认的内存向量库（{@code assistant.knowledge.store} 不写或写 memory 时）。JSON 文件名带模型后缀，换模型各用各的文件。
     *
     * @param embedding 当前向量模型
     * @param properties 知识库配置（{@code memory.dir}）
     * @return 内存向量库
     */
    @Bean
    @ConditionalOnProperty(name = "assistant.knowledge.store", havingValue = "memory", matchIfMissing = true)
    public MemoryKnowledgeStore memoryKnowledgeStore(KnowledgeEmbedding embedding, KnowledgeProperties properties) {
        String dir = properties.memory().dir();
        Path file = dir == null || dir.isBlank()
                ? null
                : Path.of(dir).resolve(PgVectorConfig.TABLE_PREFIX + embedding.storeSuffix() + ".json");
        return new MemoryKnowledgeStore(embedding, file);
    }
}
