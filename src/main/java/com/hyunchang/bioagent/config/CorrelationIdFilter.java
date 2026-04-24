package com.hyunchang.bioagent.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 요청마다 고유한 correlation ID를 MDC 및 응답 헤더에 주입합니다.
 * 들어오는 X-Request-ID가 있으면 재사용, 없으면 새로 생성.
 * 다운스트림(ML 서비스) 호출 시 RestClient 인터셉터가 동일 ID를 전파합니다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements Filter {

    public static final String HEADER_NAME = "X-Request-ID";
    public static final String MDC_KEY = "requestId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        String incoming = req.getHeader(HEADER_NAME);
        String requestId = (incoming != null && !incoming.isBlank() && incoming.length() <= 64)
                ? sanitize(incoming)
                : UUID.randomUUID().toString();

        MDC.put(MDC_KEY, requestId);
        resp.setHeader(HEADER_NAME, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** 외부에서 온 값의 안전성 확보: 영숫자, 하이픈, 언더바만 허용. */
    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_') {
                sb.append(c);
            }
        }
        return sb.length() > 0 ? sb.toString() : UUID.randomUUID().toString();
    }
}
