package com.hyunchang.bioagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.bioagent.dto.GelLanePredictionDto;
import com.hyunchang.bioagent.dto.GelPredictResult;
import com.hyunchang.bioagent.dto.GelRecordDto;
import com.hyunchang.bioagent.entity.GelRecord;
import com.hyunchang.bioagent.repository.GelRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GelService {

    @Value("${ml.service.url:http://localhost:3212}")
    private String mlServiceUrl;

    @Value("${anthropic.api.key:}")
    private String anthropicApiKey;

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";

    private final GelRecordRepository gelRecordRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient = RestClient.create();

    // ── 단일 이미지 학습 데이터 업로드 (하위 호환) ──────────────────

    public GelRecordDto uploadTrainingData(MultipartFile file, Double ctValue) throws Exception {
        log.info("학습 데이터 업로드: {} (Ct={})", file.getOriginalFilename(), ctValue);

        byte[] fileBytes = file.getBytes();
        String fileHash = computeSha256(fileBytes);
        if (gelRecordRepository.existsByFileHashAndLaneIndex(fileHash, 0)
                || gelRecordRepository.existsByFileName(file.getOriginalFilename())) {
            throw new IllegalArgumentException("DUPLICATE");
        }

        JsonNode features = callExtract(file);

        GelRecord record = GelRecord.builder()
                .fileName(file.getOriginalFilename())
                .fileHash(fileHash)
                .laneIndex(0)
                .ctValue(ctValue)
                .bandIntensity(features.path("band_intensity").asDouble(0))
                .bandArea(features.path("band_area").asDouble(0))
                .relativeIntensity(features.path("relative_intensity").asDouble(0))
                .bandWidth(features.path("band_width").asDouble(0))
                .bandHeight(features.path("band_height").asDouble(0))
                .warning(features.path("warning").asText(null))
                .build();

        GelRecord saved = gelRecordRepository.save(record);
        log.info("저장 완료: id={}", saved.getId());
        return toDto(saved);
    }

    // ── 멀티레인 학습 데이터 업로드 ────────────────────────────────

    /**
     * PCR 젤 이미지에서 전체 레인을 분석하고, ctValuesPerLane에 있는 레인만 DB에 저장합니다.
     *
     * @param file             PCR 젤 이미지 파일
     * @param ctValuesPerLane  레이블별 실측 Ct값 맵 (예: {"10^8": 18.5, "10^7": 21.3, ...})
     * @return 저장된 레인 레코드 DTO 리스트
     */
    public List<GelRecordDto> uploadMultiLaneGel(MultipartFile file,
                                                  Map<String, Double> ctValuesPerLane) throws Exception {
        log.info("멀티레인 업로드: {} ({} 레인 Ct값 입력)", file.getOriginalFilename(), ctValuesPerLane.size());

        byte[] fileBytes = file.getBytes();
        String fileHash = computeSha256(fileBytes);

        JsonNode extracted = callExtractGel(fileBytes, file.getOriginalFilename());
        JsonNode lanesNode = extracted.path("lanes");
        String gelWarning = extracted.path("warning").asText(null);

        List<GelRecordDto> saved = new ArrayList<>();
        for (JsonNode lane : lanesNode) {
            int laneIndex = lane.path("lane_index").asInt();
            String label = lane.path("label").asText();
            Double ctValue = ctValuesPerLane.get(label);
            if (ctValue == null) continue;

            if (gelRecordRepository.existsByFileHashAndLaneIndex(fileHash, laneIndex)) {
                log.info("중복 레인 스킵: {} laneIndex={}", file.getOriginalFilename(), laneIndex);
                continue;
            }

            GelRecord record = GelRecord.builder()
                    .fileName(file.getOriginalFilename())
                    .fileHash(fileHash)
                    .laneIndex(laneIndex)
                    .concentrationLabel(label)
                    .log10Concentration(computeLog10Concentration(label))
                    .ctValue(ctValue)
                    .bandIntensity(lane.path("band_intensity").asDouble(0))
                    .bandArea(lane.path("band_area").asDouble(0))
                    .relativeIntensity(lane.path("relative_intensity").asDouble(0))
                    .bandWidth(lane.path("band_width").asDouble(0))
                    .bandHeight(lane.path("band_height").asDouble(0))
                    .isSaturated(lane.path("is_saturated").asBoolean(false))
                    .isNegative(lane.path("is_negative").asBoolean(false))
                    .warning(gelWarning)
                    .build();

            saved.add(toDto(gelRecordRepository.save(record)));
        }

        log.info("멀티레인 저장 완료: {}개 레인", saved.size());
        return saved;
    }

    // ── 목록 / 삭제 ────────────────────────────────────────────────

    public List<GelRecordDto> findAll() {
        return gelRecordRepository.findAllByOrderByCreatedAtDesc()
                .stream().map(this::toDto).toList();
    }

    public void deleteById(Long id) {
        gelRecordRepository.deleteById(id);
    }

    // ── 모델 학습 ─────────────────────────────────────────────────

    public Map<String, Object> trainModel() {
        List<GelRecord> records = gelRecordRepository.findAllWithCtValues();
        if (records.isEmpty()) {
            // 하위 호환: concentrationLabel이 null인 레거시 레코드도 포함
            records = gelRecordRepository.findAll().stream()
                    .filter(r -> r.getCtValue() != null)
                    .toList();
        }
        if (records.size() < 3) {
            throw new IllegalStateException(
                    "최소 3개 이상의 학습 데이터가 필요합니다. 현재: " + records.size() + "개");
        }

        List<Map<String, Object>> features = records.stream()
                .map(r -> {
                    Map<String, Object> f = new HashMap<>();
                    f.put("band_intensity", r.getBandIntensity());
                    f.put("band_area", r.getBandArea());
                    f.put("relative_intensity", r.getRelativeIntensity());
                    f.put("band_width", r.getBandWidth());
                    f.put("band_height", r.getBandHeight() != null ? r.getBandHeight() : 0.0);
                    return f;
                }).toList();

        List<Double> ctValues = records.stream().map(GelRecord::getCtValue).toList();

        Map<String, Object> trainRequest = Map.of("features", features, "ct_values", ctValues);

        log.info("모델 학습 요청: {}개 샘플", records.size());
        String response = restClient.post()
                .uri(mlServiceUrl + "/train")
                .contentType(MediaType.APPLICATION_JSON)
                .body(trainRequest)
                .retrieve()
                .body(String.class);

        try {
            return objectMapper.readValue(response, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("학습 응답 파싱 실패: " + e.getMessage(), e);
        }
    }

    // ── 단일 이미지 Ct값 예측 (하위 호환) ─────────────────────────

    public GelPredictResult predict(MultipartFile file) throws Exception {
        log.info("Ct 예측 요청: {}", file.getOriginalFilename());

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
            @Override public String getFilename() { return file.getOriginalFilename(); }
        };
        body.add("file", resource);

        String response = restClient.post()
                .uri(mlServiceUrl + "/predict")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(String.class);

        JsonNode root = objectMapper.readTree(response);
        GelPredictResult result = new GelPredictResult();
        result.setPredictedCt(root.path("predicted_ct").asDouble());
        result.setModelR2(root.path("model_r2").asDouble());
        result.setModelRmse(root.path("model_rmse").asDouble());
        if (root.path("features").isObject()) {
            result.setFeatures(objectMapper.convertValue(root.path("features"),
                    new TypeReference<Map<String, Object>>() {}));
        }
        return result;
    }

    // ── 멀티레인 Ct값 예측 ────────────────────────────────────────

    /**
     * PCR 젤 이미지 전체 레인의 Ct값을 예측합니다.
     */
    public List<GelLanePredictionDto> predictGelLanes(MultipartFile file) throws Exception {
        log.info("멀티레인 Ct 예측: {}", file.getOriginalFilename());
        return parseGelLanePredictions(
                callPredictGel(file.getBytes(), file.getOriginalFilename()));
    }

    /** 에이전트용: 바이트 배열로 멀티레인 예측 */
    public List<GelLanePredictionDto> predictMultiLaneFromBytes(byte[] imageBytes,
                                                                 String filename) throws Exception {
        return parseGelLanePredictions(callPredictGel(imageBytes, filename));
    }

    /** 학습 데이터 등록용: 예측 없이 레인 피처만 추출 */
    public List<GelLanePredictionDto> extractGelLanesOnly(MultipartFile file) throws Exception {
        log.info("멀티레인 피처 추출 (예측 없음): {}", file.getOriginalFilename());
        return parseGelLanePredictions(callExtractGel(file.getBytes(), file.getOriginalFilename()));
    }

    // ── 모델 상태 조회 ────────────────────────────────────────────

    // ── Claude Vision으로 이미지에서 Ct값 자동 추출 ──────────────────

    public Map<String, Double> extractCtValuesFromImage(byte[] imageBytes, String contentType) throws Exception {
        if (anthropicApiKey == null || anthropicApiKey.isBlank()) {
            throw new IllegalStateException("Anthropic API 키가 설정되지 않았습니다.");
        }

        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String mediaType = (contentType != null && contentType.startsWith("image/")) ? contentType : "image/png";

        String prompt = """
                이 이미지는 mecA PCR 실험 결과입니다. 이미지에서 qPCR 증폭 곡선 옆에 표시된 Ct값들을 찾아주세요.
                "Ct = 숫자" 또는 "Ct: 숫자" 형태의 텍스트를 모두 찾아, 해당하는 희석 농도(10^8~10^1)와 매핑해주세요.

                반드시 다음 JSON 형식만 반환하고 다른 텍스트는 포함하지 마세요:
                {"10^8": 23.03, "10^7": 29.6, "10^6": 23.39, "10^5": 25.62, "10^4": 29.48, "10^3": 34.30, "10^2": 38.52, "10^1": 35.25}

                이미지에서 찾지 못한 농도는 포함하지 마세요. JSON만 반환하세요.
                """;

        List<Object> content = List.of(
                Map.of("type", "image",
                        "source", Map.of("type", "base64", "media_type", mediaType, "data", base64)),
                Map.of("type", "text", "text", prompt)
        );

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "claude-sonnet-4-6");
        requestBody.put("max_tokens", 300);
        requestBody.put("messages", List.of(Map.of("role", "user", "content", content)));

        log.info("Claude Vision Ct값 추출 요청 (imageSize={}bytes)", imageBytes.length);
        String response = restClient.post()
                .uri(ANTHROPIC_URL)
                .header("x-api-key", anthropicApiKey)
                .header("anthropic-version", "2023-06-01")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        JsonNode root = objectMapper.readTree(response);
        String text = root.path("content").get(0).path("text").asText().trim();
        log.info("Claude Vision 응답: {}", text);

        // JSON 부분만 추출
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start == -1 || end == -1) {
            throw new RuntimeException("응답에서 JSON을 찾을 수 없습니다: " + text);
        }
        String json = text.substring(start, end + 1);
        return objectMapper.readValue(json, new TypeReference<Map<String, Double>>() {});
    }

    public void resetModel() {
        restClient.delete()
                .uri(mlServiceUrl + "/model")
                .retrieve()
                .toBodilessEntity();
        log.info("ML 모델 초기화 완료");
    }

    public Map<String, Object> getModelStatus() {
        String response = restClient.get()
                .uri(mlServiceUrl + "/model/status")
                .retrieve()
                .body(String.class);
        try {
            return objectMapper.readValue(response, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("상태 조회 응답 파싱 실패: " + e.getMessage(), e);
        }
    }

    // ── 에이전트용: 단일 바이트 배열 예측 (하위 호환) ───────────────

    public GelPredictResult predictFromBytes(byte[] imageBytes, String filename) throws Exception {
        MultiValueMap<String, Object> body = buildFileBody(imageBytes, filename);

        String response = restClient.post()
                .uri(mlServiceUrl + "/predict")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(String.class);

        JsonNode root = objectMapper.readTree(response);
        GelPredictResult result = new GelPredictResult();
        result.setPredictedCt(root.path("predicted_ct").asDouble());
        result.setModelR2(root.path("model_r2").asDouble());
        result.setModelRmse(root.path("model_rmse").asDouble());
        if (root.path("features").isObject()) {
            result.setFeatures(objectMapper.convertValue(root.path("features"),
                    new TypeReference<Map<String, Object>>() {}));
        }
        if (root.path("features").path("warning").isTextual()) {
            result.setWarning(root.path("features").path("warning").asText());
        }
        return result;
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────

    private JsonNode callExtract(MultipartFile file) throws Exception {
        String response = restClient.post()
                .uri(mlServiceUrl + "/extract")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(buildFileBody(file.getBytes(), file.getOriginalFilename()))
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(response);
    }

    private JsonNode callExtractGel(byte[] imageBytes, String filename) throws Exception {
        MultiValueMap<String, Object> body = buildFileBody(imageBytes, filename);
        body.add("n_lanes", "10");
        String response = restClient.post()
                .uri(mlServiceUrl + "/extract-gel")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(response);
    }

    private JsonNode callPredictGel(byte[] imageBytes, String filename) throws Exception {
        MultiValueMap<String, Object> body = buildFileBody(imageBytes, filename);
        body.add("n_lanes", "10");
        String response = restClient.post()
                .uri(mlServiceUrl + "/predict-gel")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(response);
    }

    private List<GelLanePredictionDto> parseGelLanePredictions(JsonNode root) {
        List<GelLanePredictionDto> result = new ArrayList<>();
        for (JsonNode lane : root.path("lanes")) {
            GelLanePredictionDto dto = new GelLanePredictionDto();
            dto.setLaneIndex(lane.path("lane_index").asInt());
            dto.setConcentrationLabel(lane.path("label").asText());
            dto.setPredictedCt(lane.has("predicted_ct") && !lane.path("predicted_ct").isNull()
                    ? lane.path("predicted_ct").asDouble() : null);
            dto.setBandIntensity(lane.path("band_intensity").asDouble());
            dto.setBandArea(lane.path("band_area").asDouble());
            dto.setRelativeIntensity(lane.path("relative_intensity").asDouble());
            dto.setBandWidth(lane.path("band_width").asDouble());
            dto.setBandHeight(lane.path("band_height").asDouble());
            dto.setIsSaturated(lane.path("is_saturated").asBoolean(false));
            dto.setIsNegative(lane.path("is_negative").asBoolean(false));
            dto.setIsPrimerDimer(lane.path("is_primer_dimer").asBoolean(false));
            dto.setModelR2(lane.has("model_r2") && !lane.path("model_r2").isNull()
                    ? lane.path("model_r2").asDouble() : null);
            dto.setModelRmse(lane.has("model_rmse") && !lane.path("model_rmse").isNull()
                    ? lane.path("model_rmse").asDouble() : null);
            result.add(dto);
        }
        return result;
    }

    private MultiValueMap<String, Object> buildFileBody(byte[] bytes, String filename) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        };
        body.add("file", resource);
        return body;
    }

    private String computeSha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("해시 계산 실패", e);
        }
    }

    private Double computeLog10Concentration(String label) {
        if (label == null || label.equals("M") || label.equals("NTC")) return null;
        if (label.startsWith("10^")) {
            try {
                return Double.parseDouble(label.substring(3));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private GelRecordDto toDto(GelRecord r) {
        GelRecordDto dto = new GelRecordDto();
        dto.setId(r.getId());
        dto.setFileName(r.getFileName());
        dto.setLaneIndex(r.getLaneIndex());
        dto.setConcentrationLabel(r.getConcentrationLabel());
        dto.setLog10Concentration(r.getLog10Concentration());
        dto.setCtValue(r.getCtValue());
        dto.setBandIntensity(r.getBandIntensity());
        dto.setBandArea(r.getBandArea());
        dto.setRelativeIntensity(r.getRelativeIntensity());
        dto.setBandWidth(r.getBandWidth());
        dto.setBandHeight(r.getBandHeight());
        dto.setIsSaturated(r.getIsSaturated());
        dto.setIsNegative(r.getIsNegative());
        dto.setWarning(r.getWarning());
        dto.setCreatedAt(r.getCreatedAt());
        return dto;
    }
}
