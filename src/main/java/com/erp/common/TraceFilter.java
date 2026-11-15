package com.erp.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求链路追踪 + 耗时日志。
 *
 * <p>这是「上微服务之前」最值得先补的一块基础设施：给每个请求分配 traceId，
 * 写进 MDC（日志 pattern 里的 {@code %X{traceId}} 会打印它），同时回写响应头
 * {@code X-Trace-Id}。以后拆成多个服务时，只要把请求头里的 traceId 透传下去，
 * 就能把一次业务操作在各服务里的日志串起来 —— 等价于手写一个极简版的链路追踪。
 *
 * <p>上游已经带 X-Trace-Id 时直接沿用，这样 Feign 调用链不会断。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);
    private static final String HEADER = "X-Trace-Id";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 静态资源不记录，避免打开一个页面刷出十几条无关日志
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/") && !uri.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String traceId = StringUtils.hasText(incoming)
                ? incoming
                : UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        MDC.put("traceId", traceId);
        response.setHeader(HEADER, traceId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.info("{} {} -> {} ({} ms)", request.getMethod(),
                    request.getRequestURI(), response.getStatus(), ms);
            MDC.remove("traceId");
        }
    }
}
