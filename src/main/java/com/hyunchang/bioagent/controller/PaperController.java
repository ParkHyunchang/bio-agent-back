package com.hyunchang.bioagent.controller;

import com.hyunchang.bioagent.dto.PaperDetail;
import com.hyunchang.bioagent.dto.PaperSummary;
import com.hyunchang.bioagent.dto.ReviewRequest;
import com.hyunchang.bioagent.service.ClaudeService;
import com.hyunchang.bioagent.service.PubMedService;
import com.hyunchang.bioagent.service.SearchLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/papers")
@RequiredArgsConstructor
public class PaperController {

    private final PubMedService pubMedService;
    private final ClaudeService claudeService;
    private final SearchLogService searchLogService;

    @GetMapping("/search")
    public List<PaperSummary> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int maxResults) {
        long start = System.currentTimeMillis();
        List<PaperSummary> results = pubMedService.search(query, maxResults);
        long duration = System.currentTimeMillis() - start;
        log.info("[SEARCH] query=\"{}\" → {}건 ({}ms)", query, results.size(), duration);
        searchLogService.logSearch(query, results.size(), duration);
        return results;
    }

    @GetMapping("/{pmid}")
    public PaperDetail getDetail(@PathVariable String pmid) {
        long start = System.currentTimeMillis();
        PaperDetail detail = pubMedService.getDetail(pmid);
        if (detail == null) {
            log.warn("[DETAIL] pmid={} → 논문 없음", pmid);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "논문을 찾을 수 없습니다: " + pmid);
        }
        long duration = System.currentTimeMillis() - start;
        log.info("[DETAIL] pmid={} → \"{}\" ({}ms)", pmid, truncate(detail.getTitle(), 60), duration);
        searchLogService.logDetail(pmid, detail.getTitle(), duration);
        return detail;
    }

    @PostMapping("/review")
    public Map<String, String> review(@RequestBody ReviewRequest request) {
        PaperDetail detail = pubMedService.getDetail(request.getPmid());
        if (detail == null) {
            log.warn("[REVIEW] pmid={} → 논문 없음", request.getPmid());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "논문을 찾을 수 없습니다: " + request.getPmid());
        }
        log.info("[REVIEW] pmid={} \"{}\" — Claude 호출 시작", request.getPmid(), truncate(detail.getTitle(), 60));
        long start = System.currentTimeMillis();
        String reviewText = claudeService.reviewPaper(detail);
        long duration = System.currentTimeMillis() - start;
        log.info("[REVIEW] pmid={} → 완료 ({}ms)", request.getPmid(), duration);
        searchLogService.logReview(request.getPmid(), detail.getTitle(), duration);
        return Map.of("review", reviewText);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
