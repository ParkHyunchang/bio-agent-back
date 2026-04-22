package com.hyunchang.bioagent.controller;

import com.hyunchang.bioagent.dto.PaperDetail;
import com.hyunchang.bioagent.dto.PaperReviewRecordDto;
import com.hyunchang.bioagent.dto.ReviewRequest;
import com.hyunchang.bioagent.dto.SearchResponse;
import com.hyunchang.bioagent.entity.PaperReviewRecord;
import com.hyunchang.bioagent.repository.PaperReviewRecordRepository;
import com.hyunchang.bioagent.service.ClaudeService;
import com.hyunchang.bioagent.service.PubMedService;
import com.hyunchang.bioagent.service.SearchLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/papers")
@RequiredArgsConstructor
public class PaperController {

    private final PubMedService pubMedService;
    private final ClaudeService claudeService;
    private final SearchLogService searchLogService;
    private final PaperReviewRecordRepository reviewRecordRepository;

    @GetMapping("/search")
    public SearchResponse search(
            @RequestParam String query,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        long start = System.currentTimeMillis();
        SearchResponse result = pubMedService.search(query, page, size);
        long duration = System.currentTimeMillis() - start;
        log.info("[SEARCH] query=\"{}\" page={} → {}건/총{}건 tooBroad={} ({}ms)",
                query, page, result.getPapers().size(), result.getTotal(), result.isTooBroad(), duration);
        searchLogService.logSearch(query, result.getTotal(), duration);
        return result;
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
        PaperDetail detail = PaperDetail.builder()
                .pmid(request.getPmid())
                .title(request.getPaperTitle())
                .abstractText(request.getAbstractText())
                .authors(request.getAuthors())
                .journal(request.getJournal())
                .pubDate(request.getPubDate())
                .build();

        log.info("[REVIEW] pmid={} \"{}\" — Claude 호출 시작", request.getPmid(), truncate(detail.getTitle(), 60));
        long start = System.currentTimeMillis();
        String reviewText = claudeService.reviewPaper(detail);
        long duration = System.currentTimeMillis() - start;
        log.info("[REVIEW] pmid={} → 완료 ({}ms)", request.getPmid(), duration);
        searchLogService.logReview(request.getPmid(), detail.getTitle(), duration);

        reviewRecordRepository.save(PaperReviewRecord.builder()
                .pmid(request.getPmid())
                .paperTitle(detail.getTitle())
                .queryText(request.getQueryText())
                .reviewText(reviewText)
                .build());

        return Map.of("review", reviewText);
    }

    @GetMapping("/history")
    public ResponseEntity<List<PaperReviewRecordDto>> getHistory() {
        List<PaperReviewRecordDto> list = reviewRecordRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(r -> PaperReviewRecordDto.builder()
                        .id(r.getId())
                        .pmid(r.getPmid())
                        .paperTitle(r.getPaperTitle())
                        .queryText(r.getQueryText())
                        .reviewText(r.getReviewText())
                        .createdAt(r.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }

    @DeleteMapping("/history/{id}")
    public ResponseEntity<Void> deleteHistory(@PathVariable Long id) {
        reviewRecordRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
