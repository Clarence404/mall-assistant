package io.github.clarence404.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/**
 * 第 4 章 ch04-2：块的文字、确定性 ID 与元数据（{@code ChunkDocumentFactory#toDocuments}）。
 *
 * <p>测试思路：用一篇小文档，逐项检查每个 Document：文字以标题路径开头、ID 是由「source#序号#docHash」算出的 UUID（同样输入两次结果
 * 一样）、元数据的每个键都在且类型对；再检查 docHash 的含义：原文、切块参数、文档前缀任一变化都会变。
 */
class ChunkDocumentFactoryTest {

    private static final KnowledgeDocument DOC = MarkdownKnowledgeReader.parse("returns.md", """
            ---
            title: 退换货规则
            version: 2026.10
            updated: 2026-10-15
            ---
            # 退换货规则

            ## 七天无理由退货

            签收后 7 天内可以申请。

            ## 退款时效

            1 个工作日内发起退款。
            """);

    private final ChunkDocumentFactory factory = new ChunkDocumentFactory(new MarkdownChunker(300, 50), "bge-m3", "");

    @Test
    void eachChunkBecomesADocumentWithHeadingPathInText() {
        List<Document> docs = factory.toDocuments(DOC);

        assertThat(docs).hasSize(2);
        assertThat(docs.get(0).getText()).isEqualTo("退换货规则 > 七天无理由退货\n签收后 7 天内可以申请。");
        assertThat(docs.get(1).getText()).startsWith("退换货规则 > 退款时效\n");
    }

    @Test
    void metadataCarriesSourceHeadingHashVersionAndModel() {
        Document first = factory.toDocuments(DOC).getFirst();

        assertThat(first.getMetadata())
                .containsEntry(ChunkMetadata.SOURCE, "returns.md")
                .containsEntry(ChunkMetadata.TITLE, "退换货规则")
                .containsEntry(ChunkMetadata.HEADING_PATH, "退换货规则 > 七天无理由退货")
                .containsEntry(ChunkMetadata.CHUNK_INDEX, 0)
                .containsEntry(ChunkMetadata.CHUNK_COUNT, 2)
                .containsEntry(ChunkMetadata.DOC_HASH, factory.docHash(DOC))
                .containsEntry(ChunkMetadata.CHUNK_HASH, ChunkDocumentFactory.sha256(first.getText()))
                .containsEntry(ChunkMetadata.DOC_VERSION, "2026.10")
                .containsEntry(ChunkMetadata.DOC_UPDATED, "2026-10-15")
                .containsEntry(ChunkMetadata.EMBEDDING_MODEL, "bge-m3");
    }

    /** 确定性 ID：重复入库只覆盖不重复；pgvector 的 id 列是 uuid，所以必须是 UUID 格式。 */
    @Test
    void idsAreDeterministicUuids() {
        List<Document> once = factory.toDocuments(DOC);
        List<Document> twice = factory.toDocuments(DOC);

        assertThat(once)
                .extracting(Document::getId)
                .isEqualTo(twice.stream().map(Document::getId).toList());
        String expected = UUID.nameUUIDFromBytes(
                        ("returns.md#1#" + factory.docHash(DOC)).getBytes(StandardCharsets.UTF_8))
                .toString();
        assertThat(once.get(1).getId()).isEqualTo(expected);
        assertThat(once).extracting(Document::getId).doesNotHaveDuplicates();
    }

    @Test
    void docHashChangesWithContentChunkingOrPrefix() {
        String hash = factory.docHash(DOC);
        KnowledgeDocument edited =
                MarkdownKnowledgeReader.parse("returns.md", DOC.raw().replace("7 天", "15 天"));

        assertThat(factory.docHash(DOC)).isEqualTo(hash).hasSize(64);
        assertThat(factory.docHash(edited)).isNotEqualTo(hash);
        assertThat(new ChunkDocumentFactory(new MarkdownChunker(200, 50), "bge-m3", "").docHash(DOC))
                .isNotEqualTo(hash);
        assertThat(new ChunkDocumentFactory(new MarkdownChunker(300, 50), "nomic-embed-text", "search_document: ")
                        .docHash(DOC))
                .isNotEqualTo(hash);
    }
}
