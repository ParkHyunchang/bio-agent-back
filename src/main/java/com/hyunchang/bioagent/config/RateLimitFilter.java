package com.hyunchang.bioagent.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /api/agent/chat 계열 엔드포인트에 대해 원격 IP별 슬라이딩 윈도우 rate limit을 적용합니다.
 * 자체 호스팅 단일 사용자 앱 기준 — 폭주 요청이나 오작동 클라이언트를 완만히 차단하는 수준.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter implements Filter {

    @Value("${app.rate-limit.chat-per-minute:30}")
    private int chatPerMinute;

    @Value("${app.rate-limit.window-ms:60000}")
    private long windowMs;

    /**
     * 고비용(외부 API 과금) 엔드포인트 prefix 목록.
     * /api/agent/chat — Anthropic Claude
     * /api/exam/upload — Claude Vision
     * /api/papers/review — Claude
     * /api/papers/search — PubMed NCBI (무료지만 rate limit 있음)
     */
    private static final String[] LIMITED_PREFIXES = {
            "/api/agent/chat",
            "/api/exam/upload",
            "/api/papers/review",
            "/api/papers/search",
    };

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    private volatile long lastSweep = 0L;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest req) || !(response instanceof HttpServletResponse resp)) {
            chain.doFilter(request, response);
            return;
        }

        String path = req.getRequestURI();
        if (path != null && matchesLimited(path)) {
            String key = clientKey(req);
            if (!allow(key)) {
                log.warn("Rate limit 초과 — key={}, path={}", key, path);
                resp.setStatus(429);
                resp.setHeader("Retry-After", String.valueOf(windowMs / 1000));
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write("{\"error\":\"요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean matchesLimited(String path) {
        for (String prefix : LIMITED_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    private String clientKey(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return req.getRemoteAddr();
    }

    private boolean allow(String key) {
        long now = System.currentTimeMillis();
        long cutoff = now - windowMs;

        sweepIfDue(now);

        Deque<Long> dq = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (dq) {
            while (!dq.isEmpty() && dq.peekFirst() < cutoff) dq.pollFirst();
            if (dq.size() >= chatPerMinute) return false;
            dq.addLast(now);
            return true;
        }
    }

    private void sweepIfDue(long now) {
        if (now - lastSweep < windowMs) return;
        lastSweep = now;
        long cutoff = now - windowMs * 2;
        Iterator<Map.Entry<String, Deque<Long>>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Deque<Long> dq = it.next().getValue();
            synchronized (dq) {
                while (!dq.isEmpty() && dq.peekFirst() < cutoff) dq.pollFirst();
                if (dq.isEmpty()) it.remove();
            }
        }
    }
}
