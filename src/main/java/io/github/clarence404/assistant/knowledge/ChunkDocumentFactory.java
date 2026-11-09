package io.github.clarence404.assistant.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.ai.document.Document;

/**
 * 把一篇文档变成要写进向量库的若干个 Spring AI {@link Document}：切块、拼块文字、加元数据、定 ID（第 4 章引入）。
 *
 * <p>所在层：入库流水线的第三步。上游是 {@link MarkdownChunker}；下游是 {@link KnowledgeStore} 里的 {@code VectorStore#add}，
 * 它会调向量模型把每个 Document 的文字变成向量，连同文字和元数据一起存下来。
 *
 * <p>Spring AI 的 {@code Document} = ID + 文字（{@code text}）+ 元数据（{@code metadata}，一个 Map）。向量库存的就是这三样加上向量。
 */
public class ChunkDocumentFactory {

    private final MarkdownChunker chunker;
    private final String embeddingModel;
    private final String documentPrefix;

    /**
     * 创建工厂。
     *
     * @param chunker 切块器
     * @param embeddingModel 当前向量模型名，写进每块的元数据
     * @param documentPrefix 当前模型的文档前缀（只算进哈希，不写进块文字；前缀在调用模型时才加，见 {@link PrefixedEmbeddingModel}）
     */
    public ChunkDocumentFactory(MarkdownChunker chunker, String embeddingModel, String documentPrefix) {
        this.chunker = chunker;
        this.embeddingModel = embeddingModel;
        this.documentPrefix = documentPrefix;
    }

    /**
     * 文档的内容哈希：SHA-256（切块参数签名 + 文档前缀 + 文件原文）。
     *
     * <p>增量入库靠它判断「这篇文档要不要重新入库」：库里这篇文档的块带的 docHash 和现在算出来的一样，说明文档没变、切法也没变，
     * 切出来的块和上次一模一样，可以跳过（省掉一次次调向量模型）。为什么不只哈希原文：改了切块参数或换了前缀，原文没变但块变了，
     * 也必须重新入库。换模型不在这里处理：不同模型的向量各存一张表 / 一个文件（见 {@link KnowledgeStore}）。
     *
     * @param document 文档
     * @return 64 位十六进制字符串
     */
    public String docHash(KnowledgeDocument document) {
        return sha256(chunker.signature() + "\n" + documentPrefix + "\n" + document.raw());
    }

    /**
     * 切块并转成 Document。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>每块一个 Document，顺序与 {@link MarkdownChunker#chunk} 一致
     *   <li><b>文字</b> = 标题路径 + 换行 + 正文。为什么把标题路径写进文字：切开后的段落常常丢了主语（「需同时满足以下条件」），
     *       带上「退换货规则 > 七天无理由退货 > 适用条件」，向量里才有「退货」「七天无理由」的意思，检索更准
     *   <li><b>ID</b> = {@code UUID.nameUUIDFromBytes((source + "#" + 序号 + "#" + docHash) 的 UTF-8 字节)}：同一篇文档、同样的内容，
     *       每次算出来的 ID 都一样（确定性 ID），重复写入只会覆盖、不会多出一份；pgvector 的 id 列是 uuid 类型，必须是 UUID 格式
     *   <li><b>元数据</b>（键名见 {@link ChunkMetadata}）：source、title、headingPath、chunkIndex（int）、chunkCount（int）、docHash
     *       （{@link #docHash}）、chunkHash（块文字的 SHA-256）、docVersion、docUpdated、embeddingModel
     * </ul>
     *
     * @param document 文档
     * @return 这篇文档的全部块；正文为空时是空列表
     */
    public List<Document> toDocuments(KnowledgeDocument document) {
        // TODO ch04-2 切块，每块一个 Document：文字 = 标题路径 + 换行 + 正文，确定性 UUID，元数据见上面的列表
        return List.of();
    }

    /**
     * SHA-256 摘要，十六进制小写。
     *
     * @param text 任意文字，按 UTF-8 编码
     * @return 64 位十六进制字符串
     */
    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // 每个 JDK 都必须支持 SHA-256，走不到这里
            throw new IllegalStateException(e);
        }
    }
}
