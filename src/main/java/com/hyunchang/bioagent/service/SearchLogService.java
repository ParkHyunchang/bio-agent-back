package com.hyunchang.bioagent.service;

import com.hyunchang.bioagent.config.AsyncConfig;
import com.hyunchang.bioagent.entity.SearchLog;
import com.hyunchang.bioagent.repository.SearchLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 검색/상세/리뷰 로그를 DB에 적재.
 * 본 요청 응답 지연을 일으키지 않도록 @Async로 별도 executor에서 실행한다.
 * MDC(요청 ID)는 AsyncConfig의 TaskDecorator가 워커 스레드로 전파한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchLogService {

    private final SearchLogRepository repository;

    @Async(AsyncConfig.SEARCH_LOG_EXECUTOR)
    public void logSearch(String query, int resultCount, long durationMs) {
        save(SearchLog.builder()
                .type("SEARCH")
                .queryText(query)
                .resultCount(resultCount)
                .durationMs(durationMs)
                .build());
    }

    @Async(AsyncConfig.SEARCH_LOG_EXECUTOR)
    public void logDetail(String pmid, String paperTitle, long durationMs) {
        save(SearchLog.builder()
                .type("DETAIL")
                .pmid(pmid)
                .paperTitle(truncate(paperTitle, 1000))
                .durationMs(durationMs)
                .build());
    }

    @Async(AsyncConfig.SEARCH_LOG_EXECUTOR)
    public void logReview(String pmid, String paperTitle, long durationMs) {
        save(SearchLog.builder()
                .type("REVIEW")
                .pmid(pmid)
                .paperTitle(truncate(paperTitle, 1000))
                .durationMs(durationMs)
                .build());
    }

    private void save(SearchLog searchLog) {
        try {
            repository.save(searchLog);
        } catch (Exception e) {
            log.warn("검색 로그 DB 저장 실패: {}", e.getMessage());
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }
}
