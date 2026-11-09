package io.github.clarence404.assistant.rag;

import io.github.clarence404.assistant.intent.Intent;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * RAG（检索增强生成）的配置，前缀 {@code assistant.rag}（第 5 章引入）。每一项在 {@code application.yml} 里都有注释。
 *
 * <p>所在层：业务层的配置。由 {@code @ConfigurationPropertiesScan} 绑定，注入 {@link RagService} 与 {@link RagRetriever}。
 *
 * <p>RAG 一次回答分三步，配置也按这三步分：
 *
 * <ol>
 *   <li><b>检索</b>（Retrieval）：在知识库里找和问题相关的块——向量检索取前 {@code candidateK} 块，关键词检索也取前 {@code candidateK} 块，
 *       用 RRF 融合成一个排名；分数够不上门槛（{@code minScore} / {@code keywordMinCoverage}）的扔掉，再取前 {@code topK} 块
 *   <li><b>增强</b>（Augmentation）：把这几块编上号 [1][2]…，在 {@code contextBudgetChars} 个字以内拼进提示词
 *   <li><b>生成</b>（Generation）：模型只依据这些资料回答并标出引用编号；一块都没留下时<b>不调模型</b>，直接回「没找到相关规定」
 * </ol>
 *
 * @param enabled 总开关；关掉后规则类问题和第 4 章之前一样，按普通问答回答（模型只能凭常识，容易编）
 * @param intents 哪些意图走 RAG。默认售后（AFTER_SALES）与规则政策（POLICY）：答案在规则文档里。UNKNOWN 不在里面：识别不出意图的消息
 *     多半是闲聊或别的，走 RAG 检索不到时会回「没找到相关规定」，反而答非所问
 * @param topK 最终交给模型几块（编号 [1]～[topK]）。太少容易漏掉答案，太多提示词变长、小模型更容易被无关内容带偏
 * @param candidateK 向量检索、关键词检索各取几块候选，再融合；比 topK 大，融合才有东西可挑（受 {@code assistant.knowledge.search.max-top-k} 限制）
 * @param minScore 向量检索的最低余弦相似度：低于它的块不算「相关」。只能在同一个向量模型内部比较，换模型要重新定（见 docs/ch05.md 实测）；
 *     第 6 章起这是「兜底值」，{@code modelProfiles} 里按模型配了的以那里为准
 * @param keywordMinCoverage 只被关键词检索找到的块，问题里的词（去掉「的、了、吗」等）至少有这么大比例出现在块里才算相关，0～1
 * @param contextBudgetChars 拼进提示词的资料最多多少个字（上下文窗口预算）。超出时后面的块不再放进去，第一块太长时截断
 * @param hybrid 是否加上关键词检索，与向量检索用 RRF 融合（混合检索）。关掉 = 只用向量检索，用来对比效果
 * @param rrfK RRF 的平滑常数 k：每个检索器里排第 r 名的块得 1 / (k + r) 分。k 越大，名次之间的分差越小；60 是原论文的经验值
 * @param rewriteQuery 检索前是否先让对话模型把口语化的问题改写成检索用的问法（Spring AI 的 {@code RewriteQueryTransformer}）。
 *     多一次模型调用：本地小模型要 1～3 秒，命令行模型要好几秒，默认关闭
 * @param snippetChars 引用来源里的片段显示多少个字（页面悬停提示、调试面板用）
 * @param prompt RAG 回答的系统提示词版本，对应 {@code prompts/<prompt>.st}
 * @param modelProfiles 按向量模型分别配置的相似度门槛（第 6 章补充，和第 4 章 {@code assistant.knowledge.model-profiles} 一样按模型名前缀匹配）：
 *     不同模型的相似度分布完全不同（bge-m3 正确答案在 0.67～0.81，nomic-embed-text 在 0.59～0.77、而且和不相关的问题重叠），一个数用不了两个模型
 */
@ConfigurationProperties("assistant.rag")
public record RagProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue({"AFTER_SALES", "POLICY"}) List<Intent> intents,
        @DefaultValue("4") int topK,
        @DefaultValue("10") int candidateK,
        @DefaultValue("0.6") double minScore,
        @DefaultValue("0.6") double keywordMinCoverage,
        @DefaultValue("1500") int contextBudgetChars,
        @DefaultValue("true") boolean hybrid,
        @DefaultValue("60") int rrfK,
        @DefaultValue("false") boolean rewriteQuery,
        @DefaultValue("80") int snippetChars,
        @DefaultValue("rag-v1") String prompt,
        List<ScoreProfile> modelProfiles) {

    /** 启动时就校验，配错了立刻报错，而不是第一个用户提问时才发现。 */
    @ConstructorBinding
    public RagProperties {
        intents = intents == null ? List.of() : List.copyOf(intents);
        modelProfiles = modelProfiles == null ? List.of() : List.copyOf(modelProfiles);
        if (topK < 1 || candidateK < topK) {
            throw new IllegalArgumentException(
                    "assistant.rag.top-k 至少为 1，且 candidate-k 不能小于 top-k，当前 " + topK + " / " + candidateK);
        }
        if (contextBudgetChars < 100) {
            throw new IllegalArgumentException("assistant.rag.context-budget-chars 不能小于 100，当前 " + contextBudgetChars);
        }
        if (rrfK < 1) {
            throw new IllegalArgumentException("assistant.rag.rrf-k 至少为 1，当前 " + rrfK);
        }
    }

    /**
     * 不按模型区分门槛的配置（第 5 章的写法，测试里用）。
     *
     * @param enabled 总开关
     * @param intents 走 RAG 的意图
     * @param topK 交给模型几块
     * @param candidateK 每路取几块候选
     * @param minScore 向量相似度门槛
     * @param keywordMinCoverage 关键词覆盖率门槛
     * @param contextBudgetChars 资料字数预算
     * @param hybrid 是否混合检索
     * @param rrfK RRF 常数
     * @param rewriteQuery 是否改写问题
     * @param snippetChars 片段长度
     * @param prompt 系统提示词版本
     */
    public RagProperties(
            boolean enabled,
            List<Intent> intents,
            int topK,
            int candidateK,
            double minScore,
            double keywordMinCoverage,
            int contextBudgetChars,
            boolean hybrid,
            int rrfK,
            boolean rewriteQuery,
            int snippetChars,
            String prompt) {
        this(
                enabled,
                intents,
                topK,
                candidateK,
                minScore,
                keywordMinCoverage,
                contextBudgetChars,
                hybrid,
                rrfK,
                rewriteQuery,
                snippetChars,
                prompt,
                List.of());
    }

    /**
     * 当前向量模型用的相似度门槛：模型名以某个 profile 的 {@code match} 开头就用它的 {@code minScore}（{@code nomic-embed-text:v1.5}
     * 也能命中 {@code nomic-embed-text}），都不命中时用 {@link #minScore()}。
     *
     * @param model 向量模型名，可以为 null
     * @return 门槛
     */
    public double minScoreFor(String model) {
        String name = model == null ? "" : model.toLowerCase(Locale.ROOT);
        return modelProfiles.stream()
                .filter(p -> p.match() != null && !p.match().isBlank())
                .filter(p -> name.startsWith(p.match().toLowerCase(Locale.ROOT)))
                .map(ScoreProfile::minScore)
                .findFirst()
                .orElse(minScore);
    }

    /**
     * 某个向量模型的相似度门槛（第 6 章补充）。
     *
     * @param match 模型名前缀，如 {@code bge-m3}
     * @param minScore 这个模型的最低余弦相似度；大于 1 表示这个模型的向量分数不作为「相关」的依据，只靠关键词覆盖率（见 docs/ch06.md 的实测）
     */
    public record ScoreProfile(String match, double minScore) {}

    /**
     * 复制一份，只改「是否混合检索」（评测时对比开 / 关混合检索用）。
     *
     * @param value 新的 hybrid
     * @return 新配置
     */
    public RagProperties withHybrid(boolean value) {
        return new RagProperties(
                enabled,
                intents,
                topK,
                candidateK,
                minScore,
                keywordMinCoverage,
                contextBudgetChars,
                value,
                rrfK,
                rewriteQuery,
                snippetChars,
                prompt,
                modelProfiles);
    }
}
