package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.utils.HeaderSanitizer;
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
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// Runs first in the filter chain (Ordered.HIGHEST_PRECEDENCE) so the request id is in MDC - and
// therefore in every structured log line, including ones from other filters/interceptors - for as
// much of the request's lifecycle as possible. A plain @Component is enough for Spring Boot to
// auto-register any jakarta.servlet.Filter bean; no separate FilterRegistrationBean needed.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String MDC_KEY = "requestId";

    // Prometheus scrapes /actuator/prometheus every ~15s and Docker/orchestrator health checks poll
    // /actuator/health continuously - neither is real application traffic, and logging them at INFO
    // would drown out the requests that actually matter. shouldNotFilter skips the whole filter (no
    // requestId, no log lines) for these rather than just suppressing the log statements, so actuator
    // traffic doesn't pay even the MDC/timing overhead.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        long startMillis = System.currentTimeMillis();
        log.info("request started: method={} path={} headers={}",
                request.getMethod(), request.getRequestURI(), HeaderSanitizer.sanitize(headersOf(request)));
        try {
            filterChain.doFilter(request, response);
        } finally {
            log.info("request completed: method={} path={} status={} durationMs={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(),
                    System.currentTimeMillis() - startMillis);
            // Threads are pooled and reused across requests - leaving this set would leak the
            // previous request's id into the next request handled by the same thread.
            MDC.remove(MDC_KEY);
        }
    }

    // Honors an id the caller already generated (e.g. an upstream gateway) so a single request's
    // logs stay correlated end-to-end across services, rather than getting a new id at each hop.
    private static String resolveRequestId(HttpServletRequest request) {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        return (incoming == null || incoming.isBlank()) ? UUID.randomUUID().toString() : incoming;
    }

    private static Map<String, String> headersOf(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        var names = request.getHeaderNames();
        if (names == null) {
            return headers;
        }
        Collections.list(names).forEach(name -> headers.put(name, request.getHeader(name)));
        return headers;
    }
}
