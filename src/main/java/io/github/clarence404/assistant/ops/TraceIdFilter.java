package io.github.clarence404.assistant.ops;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 把这个请求的 traceId 放进响应头 {@code X-Trace-Id}（第 9 章引入）。
 *
 * <p>所在层：接口层的过滤器。traceId 由 Micrometer Tracing（Brave）在请求进来时生成（Spring Boot 的 HTTP 观测过滤器开的 span），同时放进日志的 MDC：
 * 同一个请求里的每一行日志——意图识别、检索、调模型、调商城——都带着同一个 traceId（结构化日志里是 {@code traceId} 字段，普通日志在
 * {@code [mall-assistant,traceId,spanId]} 里）。用户报障时把页面调试面板上的 traceId 发过来，日志里一搜就是这一轮的全部经过。
 *
 * <p>只对 {@code /api/} 开头的请求加；没有 Tracer（tracing 关掉）或当前没有 span 时什么都不做。
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    /** 响应头名。 */
    public static final String HEADER = "X-Trace-Id";

    private final ObjectProvider<Tracer> tracer;

    /**
     * 创建。
     *
     * @param tracer Micrometer Tracing 的 Tracer（没有 tracing 时不存在）
     */
    public TraceIdFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Tracer current = tracer.getIfAvailable();
        Span span = current == null ? null : current.currentSpan();
        if (span != null && !span.isNoop()) {
            response.setHeader(HEADER, span.context().traceId());
        }
        chain.doFilter(request, response);
    }
}
