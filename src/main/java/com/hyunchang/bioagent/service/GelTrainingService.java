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
    private final RestClient mlRestClient;
    private final RestClient anthropicRestClient;
    private final ObjectMapper objectMapper;

    // ── 대량 업로드 (ZIP + labels.csv) ───────────────────────────────

    /**
     * ZIP 파일을 해제해 이미지 + labels.csv로 일괄 학습 데이터 등록.
     * labels.csv 형식: 첫 줄 헤더 "filename,ct_value". 이후 각 줄은 매칭 이미지와 Ct값.
     */
    public Map<String, Object> bulkUploadZip(MultipartFile zipFile) throws Exception {
        final int MAX_ENTRIES = 500;
        final long MAX_TOTAL_BYTES = 200L * 1024 * 1024; // 200MB 압축 해제 총량
        final long MAX_ENTRY_BYTES = 20L * 1024 * 1024;

        Map<String, byte[]> imageBytes = new LinkedHashMap<>();
        String csvText = null;

        try (java.util.zip.ZipInputStream zis =
                     new java.util.zip.ZipInputStream(zipFile.getInputStream())) {
            java.util.zip.ZipEntry entry;
            long totalBytes = 0;
            int entries = 0;
            while ((entry = zis.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IllegalArgumentException("ZIP 엔트리가 너무 많습니다 (최대 " + MAX_ENTRIES + "개).");
                }
                String name = entry.getName();
                // 경로 traversal 방지
                if (name.contains("..") || name.startsWith("/") || name.contains("\\..\\")) {
                    throw new IllegalArgumentException("허용되지 않은 경로: " + name);
                }
                if (entry.isDirectory()) continue;
                String baseName = java.nio.file.Paths.get(name).getFileName().toString();
                if (baseName.startsWith(".") || baseName.startsWith("__MACOSX")) continue;

                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                long entryBytes = 0;
                while ((read = zis.read(chunk)) > 0) {
                    entryBytes += read;
                    totalBytes += read;
                    if (entryBytes > MAX_ENTRY_BYTES) {
                        throw new IllegalArgumentException("파일이 너무 큽니다: " + baseName);
                    }
                    if (totalBytes > MAX_TOTAL_BYTES) {
                        throw new IllegalArgumentException("총 크기가 제한을 초과했습니다.");
                    }
                    buf.write(chunk, 0, read);
                }
                byte[] bytes = buf.toByteArray();

                String lower = baseName.toLowerCase();
                if (lower.endsWith(".csv") || lower.equals("labels.csv")) {
                    csvText = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                } else if (lower.endsWith(".png") || lower.endsWith(".jpg")
                        || lower.endsWith(".jpeg") || lower.endsWith(".webp")) {
                    imageBytes.put(baseName, bytes);
                }
            }
        }

        if (csvText == null) {
            throw new IllegalArgumentException("ZIP 안에 labels.csv가 필요합니다 (filename,ct_value).");
        }
        if (imageBytes.isEmpty()) {
            throw new IllegalArgumentException("ZIP 안에 이미지 파일이 없습니다.");
        }

        List<Map<String, Object>> errors = new ArrayList<>();
        int succeeded = 0, duplicates = 0, failed = 0, processed = 0;

        String[] lines = csvText.split("\\r?\\n");
        boolean headerSeen = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (!headerSeen) {
                headerSeen = true;
                // 첫 줄이 헤더 형태면 스킵
                if (line.toLowerCase().contains("filename") && line.toLowerCase().contains("ct")) continue;
            }
            processed++;
            String[] parts = line.split(",", 2);
            if (parts.length < 2) {
                failed++;
                errors.add(Map.of("line", line, "error", "형식 오류 (filename,ct_value 필요)"));
                continue;
            }
            String filename = parts[0].trim();
            Double ct;
            try {
                ct = Double.parseDouble(parts[1].trim());
            } catch (NumberFormatException e) {
                failed++;
                errors.add(Map.of("filename", filename, "error", "ct_value 숫자 형식 오류"));
                continue;
            }
            byte[] bytes = imageBytes.get(filename);
            if (bytes == null) {
                failed++;
                errors.add(Map.of("filename", filename, "error", "ZIP에 이미지가 없습니다"));
                continue;
            }
            try {
                uploadTrainingData(new InMemoryMultipartFile(filename, guessMime(filename), bytes), ct);
                succeeded++;
            } catch (IllegalArgumentException e) {
                if ("DUPLICATE".equals(e.getMessage())) {
                    duplicates++;
                } else {
                    failed++;
                    errors.add(Map.of("filename", filename, "error", e.getMessage()));
                }
            } catch (Exception e) {
                log.error("bulkUpload 실패 {}: {}", filename, e.getMessage());
                failed++;
                errors.add(Map.of("filename", filename, "error", "처리 실패"));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processed", processed);
        result.put("succeeded", succeeded);
        result.put("duplicates", duplicates);
        result.put("failed", failed);
        result.put("errors", errors);
        log.info("bulk 업로드 완료: 처리={}, 성공={}, 중복={}, 실패={}", processed, succeeded, duplicates, failed);
        return result;
    }

    private static String guessMime(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

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
        log.info("모델 학습 시작 - 샘플 {}개, 특징 차원: band_intensity/band_area/relative_intensity/band_width/band_height",
                records.size());
        log.info("ML 서비스 호출: POST {}/train", mlServiceUrl);
        String response = mlRestClient.post()
                .uri(mlServiceUrl + "/train")
                .contentType(MediaType.APPLICATION_JSON)
                .body(trainRequest)
                .retrieve()
                .body(String.class);
        try {
            Map<String, Object> result = objectMapper.readValue(response, new TypeReference<Map<String, Object>>() {});
            log.info("모델 학습 완료 - 모델: {}, 샘플: {}, train_R²: {}, CV_R²: {}, RMSE: {} Ct",
                    result.get("model_type"), result.get("sample_count"),
                    result.get("train_r2"), result.get("cv_r2_mean"), result.get("train_rmse"));
            invalidateModelStatusCache();
            return result;
        } catch (Exception e) {
            throw new RuntimeException("학습 응답 파싱 실패: " + e.getMessage(), e);
        }
    }

    // ── 예측 ───────────────────────────────────────────────────────

    public GelPredictResult predict(MultipartFile file) throws Exception {
        log.info("Ct 예측 요청: {}", file.getOriginalFilename());
        String response = mlRestClient.post()
                .uri(mlServiceUrl + "/predict")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(buildFileBody(file.getBytes(), file.getOriginalFilename()))
                .retrieve()
                .body(String.class);
        GelPredictResult result = parseGelPredictResult(objectMapper.readTree(response));
        log.info("Ct 예측 완료: file={}, predictedCt={}, modelR2={}, modelRmse={}",
                file.getOriginalFilename(), result.getPredictedCt(), result.getModelR2(), result.getModelRmse());
        return result;
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
        String response = mlRestClient.post()
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
        String response = anthropicRestClient.post()
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
        mlRestClient.delete()
                .uri(mlServiceUrl + "/model")
                .retrieve()
                .toBodilessEntity();
        log.info("ML 모델 초기화 완료");
        invalidateModelStatusCache();
    }

    // ── /model/status 단기 캐시 (에이전트 다중 도구 호출 시 중복 라운드트립 억제) ──
    private static final long STATUS_CACHE_TTL_MS = 5_000L;
    private volatile Map<String, Object> modelStatusCache;
    private volatile long modelStatusCacheExpiry;

    public Map<String, Object> getModelStatus() {
        long now = System.currentTimeMillis();
        Map<String, Object> cached = modelStatusCache;
        if (cached != null && now < modelStatusCacheExpiry) {
            return cached;
        }
        String response = mlRestClient.get()
                .uri(mlServiceUrl + "/model/status")
                .retrieve()
                .body(String.class);
        try {
            Map<String, Object> parsed = objectMapper.readValue(response, new TypeReference<Map<String, Object>>() {});
            modelStatusCache = parsed;
            modelStatusCacheExpiry = now + STATUS_CACHE_TTL_MS;
            return parsed;
        } catch (Exception e) {
            throw new RuntimeException("상태 조회 응답 파싱 실패: " + e.getMessage(), e);
        }
    }

    private void invalidateModelStatusCache() {
        modelStatusCache = null;
        modelStatusCacheExpiry = 0L;
    }

    // ── 모델 버전 관리 ─────────────────────────────────────────────

    public Map<String, Object> listModelVersions() {
        String response = mlRestClient.get()
                .uri(mlServiceUrl + "/model/versions")
                .retrieve()
                .body(String.class);
        try {
            return objectMapper.readValue(response, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("버전 목록 응답 파싱 실패: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> rollbackModel(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("version_id가 필요합니다.");
        }
        Map<String, String> body = Map.of("version_id", versionId);
        String response = mlRestClient.post()
                .uri(mlServiceUrl + "/model/rollback")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        invalidateModelStatusCache();
        try {
            return objectMapper.readValue(response, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("롤백 응답 파싱 실패: " + e.getMessage(), e);
        }
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────

    private JsonNode callExtract(MultipartFile file) throws Exception {
        String response = mlRestClient.post()
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
        String response = mlRestClient.post()
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
        String response = mlRestClient.post()
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
