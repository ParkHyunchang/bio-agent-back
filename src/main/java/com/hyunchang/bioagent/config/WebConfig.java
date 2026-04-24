package com.hyunchang.bioagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
public class WebConfig {

    /**
     * 배포 환경의 오리진(로컬 개발·서버 IP·Synology NAS 도메인)을 모두 포괄.
     * 운영에서 더 좁히려면 env CORS_ALLOWED_ORIGINS 로 명시적 리스트 지정.
     */
    private static final String DEFAULT_ORIGIN_PATTERNS = String.join(",",
            "http://localhost:*",
            "http://127.0.0.1:*",
            "http://125.141.20.218",
            "http://125.141.20.218:*",
            "http://*.synology.me",
            "http://*.synology.me:*",
            "https://*.synology.me",
            "https://*.synology.me:*"
    );

    @Value("${app.cors.allowed-origins:#{null}}")
    private String allowedOriginsOverride;

    @Bean
    public CorsFilter corsFilter() {
        String raw = (allowedOriginsOverride != null && !allowedOriginsOverride.isBlank())
                ? allowedOriginsOverride
                : DEFAULT_ORIGIN_PATTERNS;

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.setAllowedOriginPatterns(Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList()));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Request-ID"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}
