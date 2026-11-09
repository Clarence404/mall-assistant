package io.github.clarence404.assistant.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 知识库测试的公共材料：配置、把 {@code knowledge/*.md} 复制到临时目录（测试要改、要删文档）、组装不带 Spring 的服务（第 4 章引入）。
 */
final class KnowledgeFixtures {

    /** 和 application.yml 一样的前缀配置。 */
    static final List<KnowledgeProperties.ModelProfile> PROFILES = List.of(
            new KnowledgeProperties.ModelProfile("nomic-embed-text", "search_query: ", "search_document: "),
            new KnowledgeProperties.ModelProfile("bge-m3", "", ""));

    private KnowledgeFixtures() {}

    /**
     * 知识库配置：内存库、不持久化、不在启动时入库，切块 300 / 50。
     *
     * @param location 文档位置
     * @param model 向量模型名
     */
    static KnowledgeProperties properties(String location, String model) {
        return properties(location, model, 300, 50);
    }

    static KnowledgeProperties properties(String location, String model, int maxChars, int overlap) {
        return new KnowledgeProperties(
                KnowledgeProperties.StoreType.MEMORY,
                location,
                false,
                model,
                0,
                new KnowledgeProperties.Chunk(maxChars, overlap),
                new KnowledgeProperties.Search(4, 10),
                new KnowledgeProperties.Memory(""),
                new KnowledgeProperties.PgVector(
                        "jdbc:postgresql://localhost:5432/mall_assistant", "mall", "", "public", "", true, 4),
                PROFILES);
    }

    /** {@code file:} 通配符位置。 */
    static String location(Path dir) {
        return dir.toUri() + "*.md";
    }

    /**
     * 把 classpath 里的全部知识文档复制到目录里。
     *
     * @param dir 目标目录
     * @return 复制了几篇
     */
    static int copyKnowledge(Path dir) throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:knowledge/*.md");
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, dir.resolve(resource.getFilename()));
            }
        }
        return resources.length;
    }

    /**
     * 一套不带 Spring 的知识库：读取器 + 内存库 + 入库服务 + 检索服务，共用一个模型。
     *
     * @param properties 配置
     * @param model 假向量模型
     * @param file 内存库的 JSON 文件，null 表示不持久化
     */
    static Kit kit(KnowledgeProperties properties, FakeEmbeddingModel model, Path file) {
        KnowledgeEmbedding embedding = new KnowledgeEmbedding(model, properties);
        MemoryKnowledgeStore store = new MemoryKnowledgeStore(embedding, file);
        MarkdownKnowledgeReader reader = new MarkdownKnowledgeReader(properties.location());
        return new Kit(
                embedding,
                store,
                new KnowledgeIngestionService(reader, store, embedding, properties),
                new KnowledgeSearchService(store, embedding, properties));
    }

    record Kit(
            KnowledgeEmbedding embedding,
            MemoryKnowledgeStore store,
            KnowledgeIngestionService ingestion,
            KnowledgeSearchService search) {}
}
