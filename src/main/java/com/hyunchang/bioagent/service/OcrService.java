package com.hyunchang.bioagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.bioagent.dto.ExamItemDto;
import com.hyunchang.bioagent.dto.OcrResultDto;
import com.hyunchang.bioagent.entity.ExamItem;
import com.hyunchang.bioagent.entity.ExamRecord;
import com.hyunchang.bioagent.repository.ExamRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OcrService {

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-sonnet-4-6";

    @Value("${anthropic.api.key:}")
    private String apiKey;

    private final ExamRecordRepository examRecordRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final org.springframework.web.client.RestClient restClient =
            org.springframework.web.client.RestClient.create();

    public OcrResultDto extractAndSave(MultipartFile file) throws Exception {
        String base64Data = Base64.getEncoder().encodeToString(file.getBytes());
        String mediaType = resolveMediaType(file.getContentType());

        Map<String, Object> imageSource = Map.of(
                "type", "base64",
                "media_type", mediaType,
                "data", base64Data
        );
        Map<String, Object> imageContent = Map.of("type", "image", "source", imageSource);
        Map<String, Object> textContent = Map.of("type", "text", "text", buildPrompt());
        Map<String, Object> message = Map.of("role", "user", "content", List.of(imageContent, textContent));
        Map<String, Object> requestBody = Map.of(
                "model", MODEL,
                "max_tokens", 8096,
                "messages", List.of(message)
        );

        String response = restClient.post()
                .uri(ANTHROPIC_URL)
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        JsonNode root = objectMapper.readTree(response);
        String content = root.path("content").get(0).path("text").asText();
        String jsonText = extractJson(content);

        String rawText = "";
        String documentType = "기타";
        List<ExamItem> items = new ArrayList<>();

        try {
            JsonNode parsed = objectMapper.readTree(jsonText);
            rawText = parsed.path("rawText").asText("");
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
            // JSON 파싱 실패 시 content 자체를 rawText로 저장하고 items는 빈 배열로 처리
            log.warn("JSON 파싱 실패, rawText fallback 처리: {}", e.getMessage());
            rawText = content.length() > 1000 ? content.substring(0, 1000) + "..." : content;
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

    public OcrResultDto findById(Long id) {
        return examRecordRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new IllegalArgumentException("Record not found: " + id));
    }

    public void deleteById(Long id) {
        examRecordRepository.deleteById(id);
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────

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

    /** Claude 응답에서 JSON 객체 부분만 추출 (코드블록 유무, 닫는 ``` 누락 등 모두 처리) */
    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text.trim();
    }

    private String resolveMediaType(String contentType) {
        if (contentType == null) return "image/jpeg";
        return switch (contentType.toLowerCase()) {
            case "image/png" -> "image/png";
            case "image/gif" -> "image/gif";
            case "image/webp" -> "image/webp";
            default -> "image/jpeg";
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
