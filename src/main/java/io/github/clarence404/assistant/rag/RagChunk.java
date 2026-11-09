package io.github.clarence404.assistant.rag;

import io.github.clarence404.assistant.knowledge.ChunkMetadata;
import io.github.clarence404.assistant.knowledge.KnowledgeHit;
import java.util.Map;
import org.springframework.ai.document.Document;

/**
 * RAG 里流转的一块知识：向量检索的结果（{@link KnowledgeHit}）和关键词检索的结果（库里的 {@link Document}）都先转成它，
 * 才能按同一个键合并、去重（第 5 章引入）。
 *
 * <p>所在层：RAG 的数据。上游是 {@link RagRetriever}（两路检索结果）与 {@link KeywordIndex}（建索引）；下游是 {@link ContextPacker}
 * （拼成编了号的引用来源）。
 *
 * @param key 块的唯一键：{@code 来源文件#块序号}。两路检索找到同一块时靠它认出来（向量检索的结果里没有块 ID，用来源 + 序号）
 * @param content 块文字：标题路径 + 换行 + 正文（第 4 章入库时就是这样拼的）
 * @param source 来源文件名，如 {@code returns.md}
 * @param title 文档标题，如「退换货规则」
 * @param headingPath 标题路径，如「退换货规则 > 质量问题退换货 > 申请时效」
 * @param chunkIndex 块在文档里的序号，从 0 开始；同一节被切成几块时，序号相邻的块可以合并
 */
public record RagChunk(String key, String content, String source, String title, String headingPath, int chunkIndex) {

    /**
     * 由向量检索的命中转换。
     *
     * @param hit 第 4 章 {@code KnowledgeSearchService} 的检索结果
     * @return 块
     */
    public static RagChunk from(KnowledgeHit hit) {
        int index = hit.chunkIndex() == null ? 0 : hit.chunkIndex();
        return new RagChunk(
                key(hit.source(), index), hit.content(), hit.source(), hit.title(), hit.headingPath(), index);
    }

    /**
     * 由库里的块（{@code KnowledgeStore#chunks()}）转换。
     *
     * @param document 块
     * @return 块
     */
    public static RagChunk from(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        String source = text(metadata, ChunkMetadata.SOURCE);
        int index = metadata.get(ChunkMetadata.CHUNK_INDEX) instanceof Number n ? n.intValue() : 0;
        return new RagChunk(
                key(source, index),
                document.getText() == null ? "" : document.getText(),
                source,
                text(metadata, ChunkMetadata.TITLE),
                text(metadata, ChunkMetadata.HEADING_PATH),
                index);
    }

    /**
     * 正文：块文字去掉第一行的标题路径。
     *
     * @return 正文；块文字没有换行时就是整段文字
     */
    public String body() {
        int newline = content.indexOf('\n');
        return newline < 0 ? content : content.substring(newline + 1);
    }

    /** 块的唯一键。 */
    static String key(String source, int chunkIndex) {
        return source + "#" + chunkIndex;
    }

    private static String text(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? "" : value.toString();
    }
}
