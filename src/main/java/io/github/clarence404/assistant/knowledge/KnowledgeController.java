package io.github.clarence404.assistant.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库的调试接口（第 4 章引入）：入库、相似度检索、概况。页面「知识库」面板调的就是它们。
 *
 * <p>所在层：接口层。下游是 {@link KnowledgeIngestionService} 与 {@link KnowledgeSearchService}。失败时抛 {@link KnowledgeException}，
 * 由 {@code error.ApiExceptionHandler} 转成带 code 的 ProblemDetail；参数不合法（q 为空、太长）由 Spring MVC 自带的方法参数校验返回 400。
 *
 * <p>这些接口没有鉴权，只适合本机学习调试：真实项目里入库（会删数据、花钱调模型）必须只给管理员用。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeIngestionService ingestionService;
    private final KnowledgeSearchService searchService;

    /**
     * 创建接口。
     *
     * @param ingestionService 入库
     * @param searchService 检索与概况
     */
    public KnowledgeController(KnowledgeIngestionService ingestionService, KnowledgeSearchService searchService) {
        this.ingestionService = ingestionService;
        this.searchService = searchService;
    }

    /**
     * 触发一次入库：{@code POST /api/knowledge/ingest}，加 {@code ?rebuild=true} 先清空再全部重新入库。
     *
     * @param rebuild 是否清空重建
     * @return 入库报告
     * @throws KnowledgeException 向量模型 / 向量库不可用（503）、需要重建（409）
     */
    @PostMapping("/ingest")
    public IngestReport ingest(@RequestParam(defaultValue = "false") boolean rebuild) {
        return ingestionService.ingest(rebuild);
    }

    /**
     * 相似度检索：{@code GET /api/knowledge/search?q=七天无理由退货要什么条件&topK=4}。只检索、不回答。
     *
     * @param q 问题，1～500 个字符
     * @param topK 返回几块，不传用默认值（4），超出范围收紧到 1～上限（10）
     * @return 问题、实际的 topK、向量模型、耗时与命中块（按相似度从高到低）
     * @throws KnowledgeException 向量模型 / 向量库不可用（503）、需要重建（409）
     */
    @GetMapping("/search")
    public SearchReply search(
            @RequestParam @NotBlank @Size(max = 500) String q, @RequestParam(required = false) Integer topK) {
        long start = System.nanoTime();
        List<KnowledgeHit> hits = searchService.search(q.strip(), topK);
        return new SearchReply(q.strip(), hits.size(), (System.nanoTime() - start) / 1_000_000, hits);
    }

    /**
     * 知识库概况：{@code GET /api/knowledge/stats}。
     *
     * @return 块数、文档数、当前向量模型与维度、向量库类型与位置
     * @throws KnowledgeException 向量库连不上（503）
     */
    @GetMapping("/stats")
    public KnowledgeStats stats() {
        return searchService.stats();
    }

    /**
     * 检索接口的响应。
     *
     * @param query 去掉首尾空白后的问题
     * @param count 命中几块
     * @param durationMs 耗时，毫秒（含把问题向量化的一次模型调用）
     * @param hits 命中块
     */
    public record SearchReply(String query, int count, long durationMs, List<KnowledgeHit> hits) {}
}
