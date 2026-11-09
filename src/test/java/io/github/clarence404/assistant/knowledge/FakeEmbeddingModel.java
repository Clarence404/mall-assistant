package io.github.clarence404.assistant.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 测试用的假向量模型：不联网、结果确定，「字面越像的文字向量越近」（第 4 章引入）。
 *
 * <p>做法（字符 n-gram 哈希，一种最朴素的向量化）：把文字里的每个字（单字）和每两个相邻的字（双字）当成特征，用哈希值决定它落在
 * 向量的哪一维，出现过就记上权重（单字 1、双字 2，出现多次也只记一次，免得某个词反复出现的块总排前面）；最后一维固定加一点偏置，保证向量不全为 0（全 0 的向量算不了余弦相似度，SimpleVectorStore 会抛异常）；
 * 再归一化成长度为 1。于是「七天无理由退货要什么条件」和含有「七天无理由退货」「适用条件」的块共享很多双字特征，余弦相似度高。
 *
 * <p>和真实模型的区别：它只认字面，不懂意思（「东西坏了」和「质量问题」毫无关系）；真实的 bge-m3 两者都能对上。测试里用它是为了
 * 结果可预测、不依赖 Ollama：验证的是流水线（切块、元数据、增量、写库、检索排序、前缀）对不对，不是模型准不准。
 *
 * <p>还能：记录每次收到的文字（检查前缀、检查没变的文档是不是真的没调模型）、设置成「调用就抛异常」（模拟模型服务挂了）。
 */
public class FakeEmbeddingModel implements EmbeddingModel {

    /** 默认维度。 */
    public static final int DEFAULT_DIMENSIONS = 256;

    private final int dimensions;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile RuntimeException failure;

    /** 默认 256 维。 */
    public FakeEmbeddingModel() {
        this(DEFAULT_DIMENSIONS);
    }

    /**
     * 指定维度（测试「换了维度不同的模型」时用）。
     *
     * @param dimensions 维度
     */
    public FakeEmbeddingModel(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        RuntimeException error = failure;
        if (error != null) {
            throw error;
        }
        List<Embedding> embeddings = new ArrayList<>();
        List<String> texts = request.getInstructions();
        for (int i = 0; i < texts.size(); i++) {
            calls.add(texts.get(i));
            embeddings.add(new Embedding(vector(texts.get(i), dimensions), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    /** 不调 {@link #call}，免得「探测维度」也被记成一次调用。 */
    @Override
    public int dimensions() {
        return dimensions;
    }

    /** 收到过的全部文字（含前缀），按调用顺序。 */
    public List<String> calls() {
        return List.copyOf(calls);
    }

    /** 清空记录、取消故障。 */
    public void reset() {
        calls.clear();
        failure = null;
    }

    /**
     * 之后每次调用都抛这个异常（模拟模型服务连不上）；传 null 恢复正常。
     *
     * @param error 要抛的异常
     */
    public void failWith(RuntimeException error) {
        this.failure = error;
    }

    /**
     * 字符 n-gram 哈希向量。
     *
     * @param text 文字
     * @param dimensions 维度
     * @return 长度为 1 的向量
     */
    public static float[] vector(String text, int dimensions) {
        float[] v = new float[dimensions];
        String t = text == null ? "" : text.replaceAll("\\s+", "");
        for (int i = 0; i < t.length(); i++) {
            int uni = bucket(t.substring(i, i + 1), dimensions);
            v[uni] = Math.max(v[uni], 1f);
            if (i + 1 < t.length()) {
                // 双字特征权重高一些：「七天」「理由」比单个「天」「由」更能区分内容
                int bi = bucket(t.substring(i, i + 2), dimensions);
                v[bi] = Math.max(v[bi], 2f);
            }
        }
        v[dimensions - 1] += 0.5f;
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        float scale = (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < v.length; i++) {
            v[i] *= scale;
        }
        return v;
    }

    /** 特征落在哪一维（最后一维留给偏置）。 */
    private static int bucket(String feature, int dimensions) {
        return Math.floorMod(feature.hashCode() * 31 + 7, dimensions - 1);
    }
}
