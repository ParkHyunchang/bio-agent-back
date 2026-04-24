package com.hyunchang.bioagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.bioagent.dto.ExamItemDto;
import com.hyunchang.bioagent.dto.OcrResultDto;
import com.hyunchang.bioagent.entity.ExamItem;
import com.hyunchang.bioagent.entity.ExamRecord;
import com.hyunchang.bioagent.repository.ExamRecordRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class OcrService {

    // ════════════════════════════════════════════════════════════════
    // ▼ Claude (Anthropic) 설정 (현재 사용 중)
    // ════════════════════════════════════════════════════════════════
    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-sonnet-4-6";
    private static final String GENERIC_ERROR_MESSAGE = "OCR 처리 중 오류가 발생했습니다.";
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024; // 10MB
    private static final Set<String> ALLOWED_MIME = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif"
    );

    @Value("${anthropic.api.key:}")
    private String apiKey;

    private final ExamRecordRepository examRecordRepository;
    private final RestClient anthropicRestClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 병렬 처리용 스레드 풀
    // Claude API 레이트 리밋(50 RPM) 여유 있게 유지하기 위해 5개로 제한
    private final ExecutorService executor = Executors.newFixedThreadPool(5);

    @PreDestroy
    public void shutdownExecutor() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── 공개 API ────────────────────────────────────────────────────

    /** 여러 파일을 병렬로 OCR 처리 후 저장 */
    public List<OcrResultDto> extractAndSaveAll(List<MultipartFile> files) {
        log.info("OCR 병렬 처리 시작: {}장", files.size());

        // 병렬 작업에 현재 요청의 correlation ID MDC 전파
        final java.util.Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();

        List<CompletableFuture<OcrResultDto>> futures = files.stream()
                .map(file -> CompletableFuture.supplyAsync(() -> {
                    if (mdcSnapshot != null) MDC.setContextMap(mdcSnapshot);
                    try {
                        log.info("OCR 처리 중: {}", file.getOriginalFilename());
                        validateFile(file);
                        return extractAndSave(file);
                    } catch (IllegalArgumentException e) {
                        log.warn("OCR 파일 거부: {} — {}", file.getOriginalFilename(), e.getMessage());
                        OcrResultDto err = new OcrResultDto();
                        err.setFileName(file.getOriginalFilename());
                        err.setDocumentType("거부");
                        err.setRawText(e.getMessage());
                        return err;
                    } catch (Exception e) {
                        log.error("OCR 처리 실패: {}", file.getOriginalFilename(), e);
                        OcrResultDto err = new OcrResultDto();
                        err.setFileName(file.getOriginalFilename());
                        err.setDocumentType("오류");
                        err.setRawText(GENERIC_ERROR_MESSAGE);
                        return err;
                    } finally {
                        MDC.clear();
                    }
                }, executor))
                .toList();

        List<OcrResultDto> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        log.info("OCR 완료: 전체 {}장 / 성공 {}장 / 오류 {}장",
                results.size(),
                results.stream().filter(r -> !"오류".equals(r.getDocumentType())).count(),
                results.stream().filter(r -> "오류".equals(r.getDocumentType())).count());

        return results;
    }

    /** 단일 파일 OCR 처리 후 저장 */
    public OcrResultDto extractAndSave(MultipartFile file) throws Exception {
        String base64Data = Base64.getEncoder().encodeToString(file.getBytes());
        String mediaType  = resolveMediaType(file.getContentType());

        // ── Claude 요청 형식 ──────────────────────────────────────────
        Map<String, Object> imageSource = Map.of(
                "type", "base64",
                "media_type", mediaType,
                "data", base64Data
        );
        Map<String, Object> imageContent = Map.of("type", "image", "source", imageSource);
        Map<String, Object> textContent  = Map.of("type", "text",  "text",   buildPrompt());
        Map<String, Object> message      = Map.of("role", "user",  "content", List.of(imageContent, textContent));
        Map<String, Object> requestBody  = Map.of(
                "model", MODEL,
                "max_tokens", 8096,
                "messages", List.of(message)
        );

        String response = anthropicRestClient.post()
                .uri(ANTHROPIC_URL)
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        JsonNode root  = objectMapper.readTree(response);
        String content = root.path("content").get(0).path("text").asText();
        String jsonText = extractJson(content);

        String rawText = "";
        String documentType = "기타";
        List<ExamItem> items = new ArrayList<>();

        try {
            JsonNode parsed = objectMapper.readTree(jsonText);
            rawText      = parsed.path("rawText").asText("");
            documentType = parsed.path("documentType").asText("기타");

            JsonNode itemsNode = parsed.path("items");
            if (itemsNode.isArray()) {
                for (JsonNode node : itemsNode) {
                    items.add(ExamItem.builder()
                            .itemName(node.path("itemName").asText(""))
                            .value(node.path("value").asText(""))
                            .unit(node.path("unit").asText(""))
                            .referenceRange(node.path("referenceRange").asText(""))
                            .isAbnormal(node.path("isAbnormal").asBoolean(false))
                            .build());
                }
            }
        } catch (Exception e) {
            log.warn("JSON 파싱 실패, rawText fallback 처리: {}", e.getMessage());
            rawText      = content.length() > 1000 ? content.substring(0, 1000) + "..." : content;
            documentType = "파싱오류";
        }

        ExamRecord record = ExamRecord.builder()
                .fileName(file.getOriginalFilename())
                .rawText(rawText)
                .documentType(documentType)
                .build();

        items.forEach(item -> item.setExamRecord(record));
        record.setItems(items);
        ExamRecord saved = examRecordRepository.save(record);

        return toDto(saved);
    }

    public List<OcrResultDto> findAll() {
        return examRecordRepository.findAllByOrderByCreatedAtDesc()
                .stream().map(this::toDto).toList();
    }

    public List<OcrResultDto> findAll(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        return examRecordRepository
                .findAllByOrderByCreatedAtDesc(org.springframework.data.domain.PageRequest.of(safePage, safeSize))
                .stream().map(this::toDto).toList();
    }

    public OcrResultDto findById(Long id) {
        return examRecordRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "해당 기록을 찾을 수 없습니다."));
    }

    public void deleteById(Long id) {
        if (id == null || !examRecordRepository.existsById(id)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "해당 기록을 찾을 수 없습니다.");
        }
        examRecordRepository.deleteById(id);
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────

    private String buildPrompt() {
        return """
                이 이미지를 분석하여 핵심 데이터를 JSON 형식으로 추출해주세요.

                규칙:
                1. items 배열은 최대 30개를 넘지 마세요.
                2. 같은 종류의 반복 데이터(예: 프라이머 쌍, 검체 목록 등)는 하나의 item으로 묶고 value에 핵심 값만 요약하세요.
                   예) itemName: "Primer pair 1", value: "F:TGGCAGAC.../R:TGAAGCAA... (Tm:60.1/60.0, 215bp)"
                3. rawText는 이미지 내용을 500자 이내로 요약하세요.
                4. 반드시 아래 JSON 형식만 반환하고 다른 설명은 절대 추가하지 마세요.

                {
                  "rawText": "이미지 내용 요약 (500자 이내)",
                  "documentType": "문서 유형 (예: 혈액검사, Western Blot, 소변검사, 프라이머설계, 실험데이터 등)",
                  "items": [
                    {
                      "itemName": "항목명",
                      "value": "수치 또는 요약값",
                      "unit": "단위 (없으면 빈 문자열)",
                      "referenceRange": "참고범위 (없으면 빈 문자열)",
                      "isAbnormal": false
                    }
                  ]
                }
                """;
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end   = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text.trim();
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("빈 파일입니다.");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException(
                    "이미지 크기가 제한을 초과했습니다 (최대 " + (MAX_IMAGE_BYTES / (1024 * 1024)) + "MB).");
        }
        String ct = file.getContentType();
        if (ct == null || !ALLOWED_MIME.contains(ct.toLowerCase())) {
            throw new IllegalArgumentException("허용되지 않은 이미지 형식입니다: " + ct);
        }
    }

    private String resolveMediaType(String contentType) {
        if (contentType == null) return "image/jpeg";
        return switch (contentType.toLowerCase()) {
            case "image/png"  -> "image/png";
            case "image/gif"  -> "image/gif";
            case "image/webp" -> "image/webp";
            default           -> "image/jpeg";
        };
    }

    private OcrResultDto toDto(ExamRecord record) {
        OcrResultDto dto = new OcrResultDto();
        dto.setId(record.getId());
        dto.setFileName(record.getFileName());
        dto.setDocumentType(record.getDocumentType());
        dto.setRawText(record.getRawText());
        dto.setCreatedAt(record.getCreatedAt());

        List<ExamItemDto> itemDtos = record.getItems().stream().map(item -> {
            ExamItemDto d = new ExamItemDto();
            d.setItemName(item.getItemName());
            d.setValue(item.getValue());
            d.setUnit(item.getUnit());
            d.setReferenceRange(item.getReferenceRange());
            d.setIsAbnormal(item.getIsAbnormal());
            return d;
        }).toList();
        dto.setItems(itemDtos);
        return dto;
    }
}
