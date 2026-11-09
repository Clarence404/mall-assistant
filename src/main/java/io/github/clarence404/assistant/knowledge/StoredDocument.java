package io.github.clarence404.assistant.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量库里已有的一篇文档的汇总：由它的各块元数据按 source 归并而来（第 4 章引入）。
 *
 * <p>所在层：知识库的数据。{@link KnowledgeStore#documents()} 返回；{@link IngestPlanner} 拿它和磁盘上的文档比对，决定新增 / 更新 /
 * 跳过 / 删除；{@code /api/knowledge/stats} 原样展示。
 *
 * @param source 来源文件名
 * @param title 文档标题
 * @param docVersion 文档版本
 * @param docHash 入库时的内容哈希；同一篇文档的块正常情况下 docHash 都一样
 * @param embeddingModel 生成这些块向量的模型
 * @param chunkCount 库里有几块
 */
public record StoredDocument(
        String source, String title, String docVersion, String docHash, String embeddingModel, int chunkCount) {

    /**
     * 把若干块的元数据按 source 归并成文档汇总，按 source 排序。memory 与 pgvector 两种库都用它，统计口径一致。
     *
     * @param metadataList 每块一个元数据 Map
     * @return 每个 source 一条
     */
    public static List<StoredDocument> summarize(List<Map<String, Object>> metadataList) {
        Map<String, List<Map<String, Object>>> bySource = new LinkedHashMap<>();
        for (Map<String, Object> metadata : metadataList) {
            String source = text(metadata, ChunkMetadata.SOURCE);
            bySource.computeIfAbsent(source, k -> new ArrayList<>()).add(metadata);
        }
        return bySource.entrySet().stream()
                .map(e -> {
                    Map<String, Object> first = e.getValue().getFirst();
                    return new StoredDocument(
                            e.getKey(),
                            text(first, ChunkMetadata.TITLE),
                            text(first, ChunkMetadata.DOC_VERSION),
                            text(first, ChunkMetadata.DOC_HASH),
                            text(first, ChunkMetadata.EMBEDDING_MODEL),
                            e.getValue().size());
                })
                .sorted(Comparator.comparing(StoredDocument::source))
                .toList();
    }

    private static String text(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? "" : value.toString();
    }
}
