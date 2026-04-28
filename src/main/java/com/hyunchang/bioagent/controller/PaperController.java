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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/papers")
@RequiredArgsConstructor
public class PaperController {

    private static final Pattern PMID_PATTERN = Pattern.compile("^[0-9]{1,10}$");
    private static final Pattern PMCID_PATTERN = Pattern.compile("^(?:PMC)?[0-9]{1,10}$");
    private static final int MAX_QUERY_LENGTH = 500;
    private static final int MAX_ABSTRACT_LENGTH = 50_000;
    private static final int MAX_TITLE_LENGTH = 1_000;

    private final PubMedService pubMedService;
    private final ClaudeService claudeService;
    private final SearchLogService searchLogService;
    private final PaperReviewRecordRepository reviewRecordRepository;

    @GetMapping("/search")
    public SearchResponse search(
            @RequestParam String query,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        validateQuery(query);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        long start = System.currentTimeMillis();
        SearchResponse result = pubMedService.search(query, safePage, safeSize);
        long duration = System.currentTimeMillis() - start;
        log.info("[SEARCH] query=\"{}\" page={} → {}건/총{}건 tooBroad={} ({}ms)",
                query, safePage, result.getPapers().size(), result.getTotal(), result.isTooBroad(), duration);
        searchLogService.logSearch(query, result.getTotal(), duration);
        return result;
    }

    @GetMapping("/{pmid}")
    public PaperDetail getDetail(@PathVariable String pmid) {
        validatePmid(pmid);
        long start = System.currentTimeMillis();
        PaperDetail detail = pubMedService.getDetail(pmid);
        if (detail == null) {
            log.warn("[DETAIL] pmid={} → 논문 없음", pmid);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "논문을 찾을 수 없습니다.");
        }
        long duration = System.currentTimeMillis() - start;
        log.info("[DETAIL] pmid={} → \"{}\" ({}ms)", pmid, truncate(detail.getTitle(), 60), duration);
        searchLogService.logDetail(pmid, detail.getTitle(), duration);
        return detail;
    }

    @PostMapping("/review")
    public Map<String, String> review(@RequestBody ReviewRequest request) {
        validateReviewRequest(request);

        String fullText = "";
        if (request.getPmcid() != null && !request.getPmcid().isBlank()) {
            fullText = pubMedService.fetchPmcFullText(request.getPmcid());
        }

        PaperDetail detail = PaperDetail.builder()
                .pmid(request.getPmid())
                .pmcid(request.getPmcid())
                .title(request.getPaperTitle())
                .abstractText(request.getAbstractText())
                .fullText(fullText)
                .authors(request.getAuthors())
                .journal(request.getJournal())
                .pubDate(request.getPubDate())
                .build();

        boolean usingFullText = fullText != null && !fullText.isBlank();
        log.info("[REVIEW] pmid={} pmcid={} source={} \"{}\" — Claude 호출 시작",
                request.getPmid(), request.getPmcid(),
                usingFullText ? "fulltext(" + fullText.length() + "자)" : "abstract",
                truncate(detail.getTitle(), 60));
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
    public ResponseEntity<List<PaperReviewRecordDto>> getHistory(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "100") int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        Pageable pageable = PageRequest.of(safePage, safeSize);
        List<PaperReviewRecordDto> list = reviewRecordRepository.findAllByOrderByCreatedAtDesc(pageable)
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
        if (id == null || !reviewRecordRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 기록을 찾을 수 없습니다.");
        }
        reviewRecordRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── 검증 헬퍼 ─────────────────────────────────────────────────

    private void validateQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검색어가 비어있습니다.");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "검색어가 너무 깁니다 (최대 " + MAX_QUERY_LENGTH + "자).");
        }
    }

    private void validatePmid(String pmid) {
        if (pmid == null || !PMID_PATTERN.matcher(pmid).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 PMID 형식입니다.");
        }
    }

    private void validateReviewRequest(ReviewRequest r) {
        if (r == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "요청 바디가 비어있습니다.");
        }
        validatePmid(r.getPmid());
        if (r.getPmcid() != null && !r.getPmcid().isBlank()
                && !PMCID_PATTERN.matcher(r.getPmcid()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 PMCID 형식입니다.");
        }
        if (r.getPaperTitle() == null || r.getPaperTitle().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "논문 제목이 필요합니다.");
        }
        if (r.getPaperTitle().length() > MAX_TITLE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "논문 제목이 너무 깁니다.");
        }
        if (r.getAbstractText() != null && r.getAbstractText().length() > MAX_ABSTRACT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "초록이 너무 깁니다.");
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
