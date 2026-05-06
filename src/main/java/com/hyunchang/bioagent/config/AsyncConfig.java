package com.hyunchang.bioagent.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 공용 비동기 인프라.
 * - reviewStreamExecutor: Claude SSE 리뷰 처리(요청당 백그라운드 1개 thread)
 * - searchLogExecutor: 사용자 요청 스레드를 막지 않고 검색/상세/리뷰 로그를 DB에 비동기 저장
 *
 * 모든 executor는 MdcTaskDecorator로 호출자 스레드의 MDC(요청 ID 등)를 워커 스레드로 전파한다.
 * 그렇지 않으면 비동기 작업의 로그가 요청 ID 없이 출력되어 추적이 불가능해진다.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    public static final String REVIEW_STREAM_EXECUTOR = "reviewStreamExecutor";
    public static final String SEARCH_LOG_EXECUTOR = "searchLogExecutor";

    /** SSE 리뷰 워커. 동시 진행 가능한 리뷰 수를 maxPoolSize로 제한해 폭주 방지. */
    @Bean(REVIEW_STREAM_EXECUTOR)
    public ThreadPoolTaskExecutor reviewStreamExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(8);
        exec.setQueueCapacity(16);
        exec.setThreadNamePrefix("review-stream-");
        exec.setTaskDecorator(mdcTaskDecorator());
        exec.initialize();
        return exec;
    }

    /** 검색/상세/리뷰 로그 적재용 경량 executor. 로그 적재가 본 요청 응답 지연을 일으키지 않도록. */
    @Bean(SEARCH_LOG_EXECUTOR)
    public ThreadPoolTaskExecutor searchLogExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(500);
        exec.setThreadNamePrefix("search-log-");
        exec.setTaskDecorator(mdcTaskDecorator());
        exec.initialize();
        return exec;
    }

    /**
     * 임의의 {@code Supplier}를 호출자 스레드의 MDC가 워커에 전파되도록 래핑.
     * {@link java.util.concurrent.CompletableFuture#supplyAsync(Supplier)}처럼
     * 우리 executor 빈을 거치지 않는 곳(예: ForkJoinPool.commonPool)에서 사용.
     */
    public static <T> Supplier<T> withMdc(Supplier<T> supplier) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (captured != null) MDC.setContextMap(captured);
            else MDC.clear();
            try {
                return supplier.get();
            } finally {
                if (previous != null) MDC.setContextMap(previous);
                else MDC.clear();
            }
        };
    }

    /** 호출 시점의 MDC 컨텍스트를 캡처해 워커 스레드에서 복원. */
    public static TaskDecorator mdcTaskDecorator() {
        return runnable -> {
            Map<String, String> captured = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (captured != null) MDC.setContextMap(captured);
                else MDC.clear();
                try {
                    runnable.run();
                } finally {
                    if (previous != null) MDC.setContextMap(previous);
                    else MDC.clear();
                }
            };
        };
    }
}
