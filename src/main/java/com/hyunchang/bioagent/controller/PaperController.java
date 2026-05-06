package com.hyunchang.bioagent.controller;

import com.hyunchang.bioagent.config.AsyncConfig;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final Set<String> ALLOWED_SORTS = Set.of("relevance", "pubDate", "epubDate");
    private static final Set<String> ALLOWED_PUB_TYPES = Set.of(
            "Review",
            "Systematic Review",
            "Meta-Analysis",
            "Clinical Trial",
            "Randomized Controlled Trial",
            "Case Reports"
    );
    private static final Set<String> ALLOWED_REVIEW_LENGTHS = Set.of("short", "normal", "detailed");
    private static final Set<String> ALLOWED_REVIEW_PERSPECTIVES =
            Set.of("default", "clinical", "mechanism", "statistics");
    private static final int MIN_YEAR = 1900;
    private static final int MAX_YEAR = 2100;

    private final PubMedService pubMedService;
    private final ClaudeService claudeService;
    private final SearchLogService searchLogService;
    private final PaperReviewRecordRepository reviewRecordRepository;

    @Qualifier(AsyncConfig.REVIEW_STREAM_EXECUTOR)
    private final ThreadPoolTaskExecutor reviewStreamExecutor;

    @GetMapping("/search")
    public SearchResponse search(
            @RequestParam String query,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String pubType,
            @RequestParam(defaultValue = "false") boolean onlyPmc,
            @RequestParam(required = false) Integer yearFrom,
            @RequestParam(required = false) Integer yearTo) {
        validateQuery(query);
        String safeSort = validateSort(sort);
        String safePubType = validatePubType(pubType);
        Integer safeYearFrom = validateYear(yearFrom);
        Integer safeYearTo = validateYear(yearTo);
        if (safeYearFrom != null && safeYearTo != null && safeYearFrom > safeYearTo) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "yearFrom이 yearTo보다 큽니다.");
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        long start = System.currentTimeMillis();
        SearchResponse result = pubMedService.search(query, safePage, safeSize,
                safeSort, safePubType, onlyPmc, safeYearFrom, safeYearTo);
        long duration = System.currentTimeMillis() - start;
        log.info("[SEARCH] query=\"{}\" page={} sort={} pubType={} onlyPmc={} year={}-{} → {}건/총{}건 tooBroad={} ({}ms)",
                query, safePage, safeSort, safePubType, onlyPmc, safeYearFrom, safeYearTo,
                result.getPapers().size(), result.getTotal(), result.isTooBroad(), duration);
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
    public Map<String, Object> review(@RequestBody ReviewRequest request) {
        validateReviewRequest(request);
        String safeLength = validateReviewLength(request.getLength());
        String safePerspective = validateReviewPerspective(request.getPerspective());

        PubMedService.PmcFullText pmc = PubMedService.PmcFullText.empty();
        if (request.getPmcid() != null && !request.getPmcid().isBlank()) {
            pmc = pubMedService.fetchPmcFullText(request.getPmcid());
        }

        PaperDetail detail = PaperDetail.builder()
                .pmid(request.getPmid())
                .pmcid(request.getPmcid())
                .title(request.getPaperTitle())
                .abstractText(request.getAbstractText())
                .fullText(pmc.text())
                .fullTextTruncated(pmc.truncated())
                .authors(request.getAuthors())
                .journal(request.getJournal())
                .pubDate(request.getPubDate())
                .build();

        boolean usingFullText = pmc.text() != null && !pmc.text().isBlank();
        log.info("[REVIEW] pmid={} pmcid={} source={} length={} perspective={} \"{}\" — Claude 호출 시작",
                request.getPmid(), request.getPmcid(),
                usingFullText ? "fulltext(" + pmc.text().length() + "자" + (pmc.truncated() ? ",truncated" : "") + ")" : "abstract",
                safeLength, safePerspective,
                truncate(detail.getTitle(), 60));
        long start = System.currentTimeMillis();
        String reviewText = claudeService.reviewPaper(detail, safeLength, safePerspective);
        long duration = System.currentTimeMillis() - start;
        log.info("[REVIEW] pmid={} → 완료 ({}ms)", request.getPmid(), duration);
        searchLogService.logReview(request.getPmid(), detail.getTitle(), duration);

        reviewRecordRepository.save(PaperReviewRecord.builder()
                .pmid(request.getPmid())
                .paperTitle(detail.getTitle())
                .queryText(request.getQueryText())
                .reviewText(reviewText)
                .build());

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("review", reviewText);
        body.put("fullTextTruncated", pmc.truncated());
        return body;
    }

    /** 프론트가 "이미 리뷰한 논문"을 결과 리스트에서 표시할 수 있도록 PMID 집합만 반환.
     *  Projection으로 pmid 컬럼만 select해서 paperTitle/reviewText 등 무거운 컬럼 로드 회피. */
    @GetMapping("/reviewed-pmids")
    public Set<String> getReviewedPmids() {
        return reviewRecordRepository.findAllPmids().stream()
                .filter(p -> p != null && !p.isBlank())
                .collect(Collectors.toSet());
    }

    /** Claude SSE 스트리밍 리뷰. 청크 이벤트(text)를 보낸 뒤 done 이벤트로 종료. */
    @PostMapping(value = "/review/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter reviewStream(@RequestBody ReviewRequest request) {
        validateReviewRequest(request);
        String safeLength = validateReviewLength(request.getLength());
        String safePerspective = validateReviewPerspective(request.getPerspective());

        SseEmitter emitter = new SseEmitter(180_000L);
        reviewStreamExecutor.submit(() -> {
            try {
                PubMedService.PmcFullText pmc = PubMedService.PmcFullText.empty();
                if (request.getPmcid() != null && !request.getPmcid().isBlank()) {
                    pmc = pubMedService.fetchPmcFullText(request.getPmcid());
                }

                PaperDetail detail = PaperDetail.builder()
                        .pmid(request.getPmid())
                        .pmcid(request.getPmcid())
                        .title(request.getPaperTitle())
                        .abstractText(request.getAbstractText())
                        .fullText(pmc.text())
                        .fullTextTruncated(pmc.truncated())
                        .authors(request.getAuthors())
                        .journal(request.getJournal())
                        .pubDate(request.getPubDate())
                        .build();

                emitter.send(SseEmitter.event().name("meta")
                        .data(Map.of("fullTextTruncated", pmc.truncated())));

                long start = System.currentTimeMillis();
                log.info("[REVIEW-STREAM] pmid={} length={} perspective={} 시작",
                        request.getPmid(), safeLength, safePerspective);

                String full = claudeService.streamReviewPaper(detail, safeLength, safePerspective, chunk -> {
                    try {
                        emitter.send(SseEmitter.event().name("chunk").data(chunk));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                long duration = System.currentTimeMillis() - start;
                log.info("[REVIEW-STREAM] pmid={} 완료 ({}ms, {}자)",
                        request.getPmid(), duration, full.length());
                searchLogService.logReview(request.getPmid(), detail.getTitle(), duration);

                reviewRecordRepository.save(PaperReviewRecord.builder()
                        .pmid(request.getPmid())
                        .paperTitle(detail.getTitle())
                        .queryText(request.getQueryText())
                        .reviewText(full)
                        .build());

                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            } catch (ClaudeService.ApiKeyMissingException e) {
                log.warn("[REVIEW-STREAM] pmid={} ANTHROPIC_API_KEY 미설정", request.getPmid());
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("type", "api_key_missing", "message", e.getMessage())));
                    emitter.complete();
                } catch (IOException ignored) {
                    emitter.completeWithError(e);
                }
            } catch (Exception e) {
                log.error("[REVIEW-STREAM] pmid={} 오류", request.getPmid(), e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @GetMapping("/history")
    public Map<String, Object> getHistory(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "100") int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        Pageable pageable = PageRequest.of(safePage, safeSize);
        List<PaperReviewRecordDto> items = reviewRecordRepository.findAllByOrderByCreatedAtDesc(pageable)
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
        long total = reviewRecordRepository.count();
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("items", items);
        body.put("total", total);
        body.put("page", safePage);
        body.put("size", safeSize);
        return body;
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

    private String validateSort(String sort) {
        if (sort == null || sort.isBlank()) return null;
        if (!ALLOWED_SORTS.contains(sort)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 정렬입니다.");
        }
        return sort;
    }

    private String validatePubType(String pubType) {
        if (pubType == null || pubType.isBlank()) return null;
        if (!ALLOWED_PUB_TYPES.contains(pubType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 논문 유형입니다.");
        }
        return pubType;
    }

    private Integer validateYear(Integer year) {
        if (year == null) return null;
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "발행연도는 " + MIN_YEAR + "-" + MAX_YEAR + " 범위여야 합니다.");
        }
        return year;
    }

    private String validateReviewLength(String length) {
        if (length == null || length.isBlank()) return "normal";
        if (!ALLOWED_REVIEW_LENGTHS.contains(length)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 length 값입니다.");
        }
        return length;
    }

    private String validateReviewPerspective(String perspective) {
        if (perspective == null || perspective.isBlank()) return "default";
        if (!ALLOWED_REVIEW_PERSPECTIVES.contains(perspective)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 perspective 값입니다.");
        }
        return perspective;
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
