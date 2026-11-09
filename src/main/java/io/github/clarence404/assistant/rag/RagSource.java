package io.github.clarence404.assistant.rag;

import java.util.List;

/**
 * 一条引用来源：交给模型的一块编了号的资料，也原样返回给前端（第 5 章引入）。
 *
 * <p>所在层：接口层的数据。由 {@link ContextPacker} 生成；阻塞接口放在响应的 {@code sources} 字段，流式接口作为 {@code source} 事件
 * 在文字之前推送；页面把回答里的 [n] 和这里的 {@code index} 对上，悬停显示片段。
 *
 * @param index 编号，从 1 开始，就是提示词里和回答里的 [1]、[2]
 * @param source 来源文件名，如 {@code returns.md}
 * @param title 文档标题
 * @param headingPath 标题路径，如「退换货规则 > 质量问题退换货 > 申请时效」
 * @param chunkIndexes 这条来源由哪几块组成（同一节相邻的块会合并成一条），按序号排列
 * @param score 向量检索的余弦相似度（4 位小数）；只被关键词检索找到时为 null
 * @param keywordScore 关键词检索的 BM25 分数（4 位小数）；只被向量检索找到时为 null
 * @param fusedScore 排序用的分数：混合检索时是 RRF 总分，只用向量检索时等于 score
 * @param foundBy 被哪些检索器找到：{@code vector}、{@code keyword}
 * @param snippet 片段：正文的前 {@code assistant.rag.snippet-chars} 个字，页面悬停提示用
 * @param content 交给模型的完整文字（标题路径 + 正文，可能被上下文预算截断）
 */
public record RagSource(
        int index,
        String source,
        String title,
        String headingPath,
        List<Integer> chunkIndexes,
        Double score,
        Double keywordScore,
        double fusedScore,
        List<String> foundBy,
        String snippet,
        String content) {}
