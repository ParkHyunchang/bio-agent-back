package com.hyunchang.bioagent.service;

import com.hyunchang.bioagent.entity.SearchLog;
import com.hyunchang.bioagent.repository.SearchLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchLogService {

    private final SearchLogRepository repository;

    public void logSearch(String query, int resultCount, long durationMs) {
        save(SearchLog.builder()
                .type("SEARCH")
                .queryText(query)
                .resultCount(resultCount)
                .durationMs(durationMs)
                .build());
    }

    public void logDetail(String pmid, String paperTitle, long durationMs) {
        save(SearchLog.builder()
                .type("DETAIL")
                .pmid(pmid)
                .paperTitle(truncate(paperTitle, 1000))
                .durationMs(durationMs)
                .build());
    }

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
