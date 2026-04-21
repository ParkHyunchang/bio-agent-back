package com.hyunchang.bioagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.bioagent.dto.GelLanePredictionDto;
import com.hyunchang.bioagent.dto.GelPredictResult;
import com.hyunchang.bioagent.dto.GelRecordDto;
import com.hyunchang.bioagent.entity.GelRecord;
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

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class GelTrainingService {

    @Value("${ml.service.url:http://localhost:3212}")
    private String mlServiceUrl;

    @Value("${anthropic.api.key:}")
    private String anthropicApiKey;

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";

    private final GelDataService gelDataService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient = RestClient.create();

    // ── 단일 이미지 학습 데이터 업로드 ──────────────────────────────

    public GelRecordDto uploadTrainingData(MultipartFile file, Double ctValue) throws Exception {
        log.info("학습 데이터 업로드: {} (Ct={})", file.getOriginalFilename(), ctValue);
        byte[] fileBytes = file.getBytes();
        String fileHash = gelDataService.computeSha256(fileBytes);
        if (gelDataService.existsByFileHashAndLaneIndex(fileHash, 0)
                || gelDataService.existsByFileName(file.getOriginalFilename())) {
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
        GelRecordDto saved = gelDataService.save(record);
        log.info("저장 완료: id={}", saved.getId());
        return saved;
    }

    // ── 멀티레인 학습 데이터 업로드 ──────────────────────────────────

    public List<GelRecordDto> uploadMultiLaneGel(MultipartFile file,
                                                  Map<String, Double> ctValuesPerLane) throws Exception {
        log.info("멀티레인 업로드: {} ({} 레인 Ct값 입력)", file.getOriginalFilename(), ctValuesPerLane.size());
        byte[] fileBytes = file.getBytes();
        String fileHash = gelDataService.computeSha256(fileBytes);
        JsonNode extracted = callExtractGel(fileBytes, file.getOriginalFilename());
        JsonNode lanesNode = extracted.path("lanes");
        String gelWarning = extracted.path("warning").asText(null);

        List<GelRecordDto> saved = new ArrayList<>();
        for (JsonNode lane : lanesNode) {
            int laneIndex = lane.path("lane_index").asInt();
            String label = lane.path("label").asText();
            Double ctValue = ctValuesPerLane.get(label);
            if (ctValue == null) continue;
            if (gelDataService.existsByFileHashAndLaneIndex(fileHash, laneIndex)) {
                log.info("중복 레인 스킵: {} laneIndex={}", file.getOriginalFilename(), laneIndex);
                continue;
            }
            GelRecord record = GelRecord.builder()
                    .fileName(file.getOriginalFilename())
                    .fileHash(fileHash)
                    .laneIndex(laneIndex)
                    .concentrationLabel(label)
                    .log10Concentration(gelDataService.computeLog10Concentration(label))
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
            saved.add(gelDataService.save(record));
        }
        log.info("멀티레인 저장 완료: {}개 레인", saved.size());
        return saved;
    }

    // ── 모델 학습 ──────────────────────────────────────────────────

    public Map<String, Object> trainModel() {
        List<GelRecord> records = gelDataService.findAllWithCtValues();
        if (records.isEmpty()) {
            records = gelDataService.findAllEntities().stream()
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

    // ── 예측 ───────────────────────────────────────────────────────

    public GelPredictResult predict(MultipartFile file) throws Exception {
        log.info("Ct 예측 요청: {}", file.getOriginalFilename());
        String response = restClient.post()
                .uri(mlServiceUrl + "/predict")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(buildFileBody(file.getBytes(), file.getOriginalFilename()))
                .retrieve()
                .body(String.class);
        return parseGelPredictResult(objectMapper.readTree(response));
    }

    public List<GelLanePredictionDto> predictGelLanes(MultipartFile file) throws Exception {
        log.info("멀티레인 Ct 예측: {}", file.getOriginalFilename());
        return parseGelLanePredictions(callPredictGel(file.getBytes(), file.getOriginalFilename()));
    }

    public List<GelLanePredictionDto> predictMultiLaneFromBytes(byte[] imageBytes, String filename) throws Exception {
        return parseGelLanePredictions(callPredictGel(imageBytes, filename));
    }

    public List<GelLanePredictionDto> extractGelLanesOnly(MultipartFile file) throws Exception {
        log.info("멀티레인 피처 추출 (예측 없음): {}", file.getOriginalFilename());
        return parseGelLanePredictions(callExtractGel(file.getBytes(), file.getOriginalFilename()));
    }

    public GelPredictResult predictFromBytes(byte[] imageBytes, String filename) throws Exception {
        String response = restClient.post()
                .uri(mlServiceUrl + "/predict")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(buildFileBody(imageBytes, filename))
                .retrieve()
                .body(String.class);
        JsonNode root = objectMapper.readTree(response);
        GelPredictResult result = parseGelPredictResult(root);
        if (root.path("features").path("warning").isTextual()) {
            result.setWarning(root.path("features").path("warning").asText());
        }
        return result;
    }

    // ── Claude Vision Ct값 자동 추출 ──────────────────────────────

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
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "claude-sonnet-4-6");
        requestBody.put("max_tokens", 300);
        requestBody.put("messages", List.of(Map.of("role", "user", "content", List.of(
                Map.of("type", "image",
                        "source", Map.of("type", "base64", "media_type", mediaType, "data", base64)),
                Map.of("type", "text", "text", prompt)
        ))));
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
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start == -1 || end == -1) {
            throw new RuntimeException("응답에서 JSON을 찾을 수 없습니다: " + text);
        }
        return objectMapper.readValue(text.substring(start, end + 1),
                new TypeReference<Map<String, Double>>() {});
    }

    // ── 모델 상태 / 초기화 ─────────────────────────────────────────

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

    // ── 내부 헬퍼 ──────────────────────────────────────────────────

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

    private GelPredictResult parseGelPredictResult(JsonNode root) {
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
}
