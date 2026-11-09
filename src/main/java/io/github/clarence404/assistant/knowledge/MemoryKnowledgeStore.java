package io.github.clarence404.assistant.knowledge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.content.Content;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import tools.jackson.databind.json.JsonMapper;

/**
 * 内存向量库：Spring AI 的 {@link SimpleVectorStore}，可选存成 JSON 文件（第 4 章引入，默认的 {@code assistant.knowledge.store}）。
 *
 * <p>所在层：知识库的存储层，{@link KnowledgeStore} 的默认实现。由 {@link KnowledgeConfig} 在 {@code store=memory} 时创建。
 *
 * <p>SimpleVectorStore 怎么检索：把全部块的向量放在一个 Map 里，检索时把问题向量和<b>每一块</b>算一遍余弦相似度，排序取前 K 个。
 * 没有索引，块数上万后就慢了；本项目几十块，毫秒级。好处是零依赖：不装 Docker、不配数据库就能跑通本章。
 *
 * <p>持久化：配置了 {@code assistant.knowledge.memory.dir} 时，每次入库后把整个 Map 写成 JSON 文件（含向量），下次启动先读回来，
 * 增量入库发现文档没变就全部跳过，不用重新调模型。文件名带模型后缀（{@code knowledge_chunk_bge_m3.json}），换模型各用各的文件。
 *
 * <p>Spring AI 2.0.2 的几个实际行为（和直觉不同，代码里已处理）：
 *
 * <ul>
 *   <li>{@code add} 逐块调 {@code embed(Document)}，一块一次 HTTP 请求，不批量（PgVectorStore 是批量的）
 *   <li>{@code add} 传空列表直接抛 IllegalArgumentException，调用方要先判空
 *   <li>没有「列出全部块」的公开方法，存块的 Map 是 protected 字段 {@code store}，这里用一个子类把它露出来
 *   <li>{@code save(File)} 不会自动建父目录
 * </ul>
 */
public class MemoryKnowledgeStore implements KnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryKnowledgeStore.class);

    private final KnowledgeEmbedding embedding;
    private final Path file;
    private volatile InspectableSimpleVectorStore store;

    /**
     * 创建。
     *
     * @param embedding 当前向量模型
     * @param file JSON 文件路径；为 null 表示不持久化
     */
    public MemoryKnowledgeStore(KnowledgeEmbedding embedding, Path file) {
        this.embedding = embedding;
        this.file = file;
    }

    @Override
    public String type() {
        return "memory";
    }

    @Override
    public String location() {
        return file == null ? "内存（不持久化）" : file.toAbsolutePath().toString();
    }

    /** 第一次用到时创建；有 JSON 文件就先读回来，并检查文件里的向量维度和当前模型是否一致。 */
    @Override
    public VectorStore vectorStore() {
        InspectableSimpleVectorStore current = store();
        if (!current.contents().isEmpty()) {
            int stored = current.firstDimensions();
            int expected = embedding.dimensions();
            if (stored != expected) {
                throw new KnowledgeException(
                        KnowledgeFailure.INDEX_MODEL_MISMATCH,
                        "向量库 " + location() + " 里的向量是 " + stored + " 维，当前向量模型 " + embedding.modelName() + " 输出 "
                                + expected + " 维：不同模型的向量不能混在一起比较，请重建（POST /api/knowledge/ingest?rebuild=true）。",
                        null);
            }
        }
        return current;
    }

    @Override
    public List<StoredDocument> documents() {
        if (!embedding.configured()) {
            return List.of();
        }
        List<Map<String, Object>> metadata =
                store().contents().stream().map(Content::getMetadata).toList();
        return StoredDocument.summarize(metadata);
    }

    @Override
    public void persist() {
        if (file == null || store == null) {
            return;
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            store.save(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("保存内存向量库失败：" + file, e);
        }
    }

    @Override
    public void reset() {
        store().clear();
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new UncheckedIOException("删除内存向量库文件失败：" + file, e);
            }
        }
    }

    /** 懒加载：创建 SimpleVectorStore（只是持有模型的引用，不调用模型），有 JSON 文件就读回来。 */
    private synchronized InspectableSimpleVectorStore store() {
        if (store == null) {
            InspectableSimpleVectorStore created = new InspectableSimpleVectorStore(embedding.model());
            if (file != null && Files.isRegularFile(file)) {
                created.load(file.toFile());
                log.info("已从 {} 读回 {} 个知识块", file, created.contents().size());
            }
            store = created;
        }
        return store;
    }

    /**
     * 露出 SimpleVectorStore 内部 Map 的子类：Spring AI 只提供了按相似度检索，没有「列出全部」，而 protected 的 {@code store} 字段
     * 子类可以访问。
     */
    static final class InspectableSimpleVectorStore extends SimpleVectorStore {

        private static final JsonMapper JSON = JsonMapper.builder().build();

        InspectableSimpleVectorStore(EmbeddingModel embeddingModel) {
            super(SimpleVectorStore.builder(embeddingModel));
        }

        /**
         * 当前全部块。Map 的值类型 SimpleVectorStoreContent 在 Spring AI 2.0.2 里是包内可见的（不能在别的包里写出这个类名），
         * 好在它实现了公开接口 {@link Content}，按接口拿文字和元数据。
         */
        Collection<? extends Content> contents() {
            return this.store.values();
        }

        /**
         * 第一块向量的维度。向量在 Content 接口上取不到，借 Jackson 把它转成 Map 再看 embedding 数组的长度
         * （SimpleVectorStore 存 JSON 文件时也是用 Jackson 序列化它的）。
         */
        int firstDimensions() {
            Object first = this.store.values().iterator().next();
            Map<?, ?> json = JSON.convertValue(first, Map.class);
            return json.get("embedding") instanceof List<?> list ? list.size() : -1;
        }

        /** 清空。 */
        void clear() {
            this.store.clear();
        }
    }
}
