package com.hyunchang.bioagent.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Value("${app.ml.connect-timeout-ms:10000}")
    private long mlConnectTimeoutMs;

    @Value("${app.ml.read-timeout-ms:180000}")
    private long mlReadTimeoutMs;

    @Value("${app.anthropic.connect-timeout-ms:10000}")
    private long anthropicConnectTimeoutMs;

    @Value("${app.anthropic.read-timeout-ms:180000}")
    private long anthropicReadTimeoutMs;

    /**
     * 현재 요청의 correlation ID를 다운스트림 호출의 X-Request-ID로 전파.
     */
    private static final ClientHttpRequestInterceptor CORRELATION_ID_INTERCEPTOR = (req, body, exec) -> {
        String rid = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (rid != null && !rid.isBlank()) {
            req.getHeaders().set(CorrelationIdFilter.HEADER_NAME, rid);
        }
        return exec.execute(req, body);
    };

    @Bean
    public RestClient mlRestClient() {
        ClientHttpRequestFactory factory = ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofMillis(mlConnectTimeoutMs))
                        .withReadTimeout(Duration.ofMillis(mlReadTimeoutMs))
        );
        return RestClient.builder()
                .requestFactory(java.util.Objects.requireNonNull(factory))
                .requestInterceptor(java.util.Objects.requireNonNull(CORRELATION_ID_INTERCEPTOR))
                .build();
    }

    @Bean
    public RestClient anthropicRestClient() {
        ClientHttpRequestFactory factory = ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofMillis(anthropicConnectTimeoutMs))
                        .withReadTimeout(Duration.ofMillis(anthropicReadTimeoutMs))
        );
        return RestClient.builder()
                .requestFactory(java.util.Objects.requireNonNull(factory))
                .build();
    }

    /**
     * PubMed NCBI E-utilities 전용. 외부 API라 적당한 타임아웃 필요.
     */
    @Bean
    public RestClient pubmedRestClient(
            @Value("${app.pubmed.connect-timeout-ms:5000}") long connectMs,
            @Value("${app.pubmed.read-timeout-ms:15000}") long readMs) {
        ClientHttpRequestFactory factory = ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofMillis(connectMs))
                        .withReadTimeout(Duration.ofMillis(readMs))
        );
        return RestClient.builder()
                .requestFactory(java.util.Objects.requireNonNull(factory))
                .build();
    }
}
