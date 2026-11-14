package io.github.clarence404.assistant.knowledge;

import io.github.clarence404.assistant.guard.CallerResolver;
import io.github.clarence404.assistant.guard.ChatGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>这些接口没有登录鉴权，只适合本机学习调试：真实项目里入库（会删数据、花钱调模型）必须只给管理员用。
 *
 * <p>审查修复——原来三个接口谁都能调、想调多少次调多少次，其中 {@code ingest?rebuild=true} 等于任何人都能把知识库删掉重建：
 *
 * <ul>
 *   <li>入库（{@code POST /ingest}）：默认只接受<b>本机回环地址</b>（127.0.0.1 / ::1）发来的请求，别的来源 403
 *       {@code KNOWLEDGE_INGEST_FORBIDDEN}。在本机浏览器打开页面点「入库」、本机 curl、测试都不受影响；开关是
 *       {@code assistant.knowledge.ingest-local-only}（默认 true，确实要从别的机器入库时改成 false）。启动时的自动入库不走这个接口
 *   <li>检索调试（{@code GET /search}）：每次要调一次向量模型，过 {@link ChatGuard#throttle} 的每分钟次数限流（按来源 IP，和对话共用一个上限）
 *   <li>概况（{@code GET /stats}）：只读本机状态，不调模型，不限
 * </ul>
 *
 * <p>「只认本机」为什么够用、什么时候不够：它不需要保管任何口令，对「一台机器上学习」正合适。但它认的是 TCP 连接的来源地址——应用前面有
 * 反向代理（Nginx、网关）时，所有请求看起来都来自代理所在的机器；代理和应用同机的话就都成了「本机」，这层保护等于没有。那种部署要在代理上
 * 拦掉 {@code /api/knowledge/ingest}，或者换成真正的管理员鉴权。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    private final KnowledgeIngestionService ingestionService;
    private final KnowledgeSearchService searchService;
    private final ChatGuard guard;
    private final CallerResolver callers;
    private final boolean ingestLocalOnly;

    /**
     * 创建接口。
     *
     * @param ingestionService 入库
     * @param searchService 检索与概况
     * @param guard 限流（审查修复）
     * @param callers 这次请求算谁的
     * @param ingestLocalOnly {@code assistant.knowledge.ingest-local-only}：入库接口是否只接受本机回环地址，默认 true
     */
    public KnowledgeController(
            KnowledgeIngestionService ingestionService,
            KnowledgeSearchService searchService,
            ChatGuard guard,
            CallerResolver callers,
            @Value("${assistant.knowledge.ingest-local-only:true}") boolean ingestLocalOnly) {
        this.ingestionService = ingestionService;
        this.searchService = searchService;
        this.guard = guard;
        this.callers = callers;
        this.ingestLocalOnly = ingestLocalOnly;
    }

    /**
     * 触发一次入库：{@code POST /api/knowledge/ingest}，加 {@code ?rebuild=true} 先清空再全部重新入库。
     *
     * <p>默认只接受本机回环地址的调用（见类说明）。
     *
     * @param rebuild 是否清空重建
     * @param http 取来源地址
     * @return 入库报告
     * @throws KnowledgeException 向量模型 / 向量库不可用（503）、需要重建（409）、不是从本机调的（403）
     */
    @PostMapping("/ingest")
    public IngestReport ingest(@RequestParam(defaultValue = "false") boolean rebuild, HttpServletRequest http) {
        if (ingestLocalOnly && !isLoopback(http.getRemoteAddr())) {
            log.warn("拒绝来自 {} 的入库请求（rebuild={}）：入库接口只接受本机调用", http.getRemoteAddr(), rebuild);
            throw new KnowledgeException(
                    KnowledgeFailure.KNOWLEDGE_INGEST_FORBIDDEN,
                    "入库只能在运行智能客服的这台机器上触发（用本机浏览器打开页面，或在本机 curl）。"
                            + "确实要从别的机器入库，把 assistant.knowledge.ingest-local-only 设为 false。",
                    null);
        }
        return ingestionService.ingest(rebuild);
    }

    /**
     * 来源地址是不是本机回环（127.0.0.0/8、::1）。{@code getRemoteAddr()} 给的是 IP 字面量，{@link InetAddress#getByName} 对字面量只做解析、
     * 不查 DNS；解析不了的一律当作不是本机。
     *
     * @param remoteAddr 请求的来源地址
     * @return 是否回环地址
     */
    public static boolean isLoopback(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(remoteAddr).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * 相似度检索：{@code GET /api/knowledge/search?q=七天无理由退货要什么条件&topK=4}。只检索、不回答。
     *
     * @param q 问题，1～500 个字符
     * @param topK 返回几块，不传用默认值（4），超出范围收紧到 1～上限（10）
     * @param http 取来源地址（按 IP 限流）
     * @return 问题、实际的 topK、向量模型、耗时与命中块（按相似度从高到低）
     * @throws KnowledgeException 向量模型 / 向量库不可用（503）、需要重建（409）
     */
    @GetMapping("/search")
    public SearchReply search(
            @RequestParam @NotBlank @Size(max = 500) String q,
            @RequestParam(required = false) Integer topK,
            HttpServletRequest http) {
        // 审查修复：每次检索要调一次向量模型，过每分钟次数限流
        guard.throttle(callers.resolve(null, http.getRemoteAddr()));
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
