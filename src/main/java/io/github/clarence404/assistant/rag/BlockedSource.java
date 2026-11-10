package io.github.clarence404.assistant.rag;

import java.util.List;

/**
 * 被提示注入过滤挡下的一块资料：检索到了，但没交给模型（第 9 章引入）。
 *
 * <p>所在层：接口层的数据。{@link RagService#screen} 生成，放在 {@link RagTrace#blocked()} 里；页面调试面板「引用来源」一栏用红色列出，
 * 告诉开发者「这块资料被挡了、为什么」——知识库被人改了，还是规则误杀了正常的文档。
 *
 * @param source 来源文件名
 * @param headingPath 标题路径
 * @param rules 命中的规则编号（{@code IGNORE_RULES}、{@code FAKE_ROLE_MARKER}……）
 * @param reason 第一条命中规则的原因（给人看）
 * @param excerpt 命中的片段（归一化之后的文字）
 */
public record BlockedSource(String source, String headingPath, List<String> rules, String reason, String excerpt) {}
