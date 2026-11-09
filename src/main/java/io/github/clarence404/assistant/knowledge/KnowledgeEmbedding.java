package io.github.clarence404.assistant.knowledge;

import java.util.Locale;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * 当前用的向量模型：名字、前缀、维度，以及包好前缀的 {@link EmbeddingModel}（第 4 章引入）。
 *
 * <p>所在层：模型接入。由 {@link KnowledgeConfig} 创建；向量库（{@link MemoryKnowledgeStore} / {@link PgVectorKnowledgeStore}）、
 * {@link KnowledgeIngestionService}、{@link KnowledgeSearchService} 都从这里拿模型信息。
 *
 * <p><b>维度不写死</b>：不同模型维度不同（bge-m3 1024、nomic-embed-text 768、OpenAI text-embedding-3-small 1536），写死就没法换模型。
 * 默认在第一次用到时调一次模型，看返回的向量有多长；也可以用 {@code assistant.knowledge.dimensions} 指定。pgvector 建表时要把维度写进
 * 列类型 {@code vector(1024)}，所以必须在建表前知道。
 *
 * <p>为什么不在启动时探测：探测要真的调一次模型，模型服务没启动时会拖慢甚至卡住启动；知识库只是辅助功能，对话不该因为它起不来。
 */
public class KnowledgeEmbedding {

    private final EmbeddingModel raw;
    private final String modelName;
    private final KnowledgeProperties.ModelProfile profile;
    private final int configuredDimensions;
    private volatile EmbeddingModel prefixed;
    private volatile int dimensions;

    /**
     * 创建。
     *
     * @param raw Spring AI 自动配置的向量模型；没配置向量模型（{@code spring.ai.model.embedding=none}）时为 null，知识库接口返回 503
     * @param properties 知识库配置：模型名、前缀、维度
     */
    public KnowledgeEmbedding(EmbeddingModel raw, KnowledgeProperties properties) {
        this.raw = raw;
        String name = properties.embeddingModel();
        this.modelName = name == null || name.isBlank() ? "unknown" : name.strip();
        this.profile = properties.profileFor(this.modelName);
        this.configuredDimensions = properties.dimensions();
    }

    /** 模型名，如 {@code bge-m3}。 */
    public String modelName() {
        return modelName;
    }

    /** 这个模型的前缀配置。 */
    public KnowledgeProperties.ModelProfile profile() {
        return profile;
    }

    /** 是否配置了向量模型（不代表模型服务连得上）。 */
    public boolean configured() {
        return raw != null;
    }

    /**
     * 包好前缀的向量模型，交给向量库用。
     *
     * @return 装饰过的模型
     * @throws KnowledgeException 没有配置向量模型
     */
    public EmbeddingModel model() {
        if (raw == null) {
            throw new KnowledgeException(
                    KnowledgeFailure.EMBEDDING_UNAVAILABLE, "没有配置向量模型（spring.ai.model.embedding=none），知识库不可用。", null);
        }
        EmbeddingModel current = prefixed;
        if (current == null) {
            current = new PrefixedEmbeddingModel(raw, profile.queryPrefix(), profile.documentPrefix());
            prefixed = current;
        }
        return current;
    }

    /**
     * 向量维度：配置了就用配置，否则调一次模型探测，结果缓存（模型不变，维度就不变）。
     *
     * @return 维度，大于 0
     * @throws KnowledgeException 没有配置向量模型，或探测时模型不可用
     */
    public int dimensions() {
        if (configuredDimensions > 0) {
            return configuredDimensions;
        }
        int known = dimensions;
        if (known > 0) {
            return known;
        }
        // 用原始模型的 dimensions()：Spring AI 的模型类先查自带的「已知模型维度表」，查不到就嵌入一段文字看长度，并缓存结果
        model();
        try {
            known = raw.dimensions();
        } catch (RuntimeException e) {
            throw KnowledgeException.embeddingUnavailable(modelName, e);
        }
        dimensions = known;
        return known;
    }

    /**
     * 已经知道的维度，不触发探测（stats 接口在模型不可用时也能返回）。
     *
     * @return 配置的或探测过的维度；还没探测过时为 null
     */
    public Integer dimensionsIfKnown() {
        if (configuredDimensions > 0) {
            return configuredDimensions;
        }
        return dimensions > 0 ? dimensions : null;
    }

    /**
     * 模型名变成能放进表名 / 文件名的后缀：小写，去掉 Ollama 的 {@code :latest} 标签，其余非字母数字一律换成下划线。
     * 如 {@code bge-m3} → {@code bge_m3}，{@code nomic-embed-text:v1.5} → {@code nomic_embed_text_v1_5}。
     *
     * @return 只含小写字母、数字和下划线的后缀，最长 40 个字符
     */
    public String storeSuffix() {
        String name = modelName.toLowerCase(Locale.ROOT);
        if (name.endsWith(":latest")) {
            name = name.substring(0, name.length() - ":latest".length());
        }
        String suffix = name.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (suffix.isEmpty()) {
            suffix = "unknown";
        }
        return suffix.length() > 40 ? suffix.substring(0, 40) : suffix;
    }
}
