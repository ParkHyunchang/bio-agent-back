package com.hyunchang.bioagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GelService {

    @Value("${ml.service.url:http://localhost:3212}")
    private String mlServiceUrl;

    private final GelRecordRepository gelRecordRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient = RestClient.create();

    // ── 학습 데이터 업로드 ─────────────────────────────────────────

    /**
     * PCR 젤 이미지와 실측 Ct값을 받아 특징을 추출하고 DB에 저장합니다.
     *
     * @param file    PCR 젤 이미지 파일
     * @param ctValue 외부 기관 qPCR Ct값
     * @return 저장된 레코드 DTO
     */
    public GelRecordDto uploadTrainingData(MultipartFile file, Double ctValue) throws Exception {
        log.info("학습 데이터 업로드: {} (Ct={})", file.getOriginalFilename(), ctValue);

        byte[] fileBytes = file.getBytes();
        String fileHash = computeSha256(fileBytes);
        if (gelRecordRepository.existsByFileHash(fileHash)
                || gelRecordRepository.existsByFileName(file.getOriginalFilename())) {
            throw new IllegalArgumentException("DUPLICATE");
        }

        // Python /extract 호출
        JsonNode features = callExtract(file);

        GelRecord record = GelRecord.builder()
                .fileName(file.getOriginalFilename())
                .fileHash(fileHash)
                .ctValue(ctValue)
                .bandIntensity(features.path("band_intensity").asDouble(0))
                .bandArea(features.path("band_area").asDouble(0))
                .relativeIntensity(features.path("relative_intensity").asDouble(0))
                .bandWidth(features.path("band_width").asDouble(0))
                .warning(features.path("warning").asText(null))
                .build();

        GelRecord saved = gelRecordRepository.save(record);
        log.info("저장 완료: id={}", saved.getId());
        return toDto(saved);
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

    /**
     * DB에 저장된 전체 학습 데이터를 Python 서비스로 전송하여 모델을 학습시킵니다.
     *
     * @return 학습 메트릭 (R², RMSE, 샘플 수 등)
     */
    public Map<String, Object> trainModel() {
        List<GelRecord> records = gelRecordRepository.findAll();
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
                    return f;
                }).toList();

        List<Double> ctValues = records.stream().map(GelRecord::getCtValue).toList();

        Map<String, Object> trainRequest = Map.of(
                "features", features,
                "ct_values", ctValues
        );

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

    // ── Ct값 예측 ─────────────────────────────────────────────────

    /**
     * 새 PCR 젤 이미지를 Python 서비스로 전송하여 Ct값을 예측합니다.
     *
     * @param file 예측할 이미지
     * @return 예측 결과 DTO
     */
    public GelPredictResult predict(MultipartFile file) throws Exception {
        log.info("Ct 예측 요청: {}", file.getOriginalFilename());

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename();
            }
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

        JsonNode featuresNode = root.path("features");
        if (featuresNode.isObject()) {
            result.setFeatures(objectMapper.convertValue(featuresNode, new TypeReference<Map<String, Object>>() {}));
        }

        return result;
    }

    // ── 모델 상태 조회 ────────────────────────────────────────────

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

    // ── 에이전트용: 바이트 배열로 직접 예측 ─────────────────────────

    public GelPredictResult predictFromBytes(byte[] imageBytes, String filename) throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(imageBytes) {
            @Override public String getFilename() { return filename; }
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
            result.setFeatures(objectMapper.convertValue(root.path("features"), new TypeReference<Map<String, Object>>() {}));
        }
        if (root.path("features").path("warning").isTextual()) {
            result.setWarning(root.path("features").path("warning").asText());
        }
        return result;
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────

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

    private JsonNode callExtract(MultipartFile file) throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename();
            }
        };
        body.add("file", resource);

        String response = restClient.post()
                .uri(mlServiceUrl + "/extract")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(String.class);

        return objectMapper.readTree(response);
    }

    private GelRecordDto toDto(GelRecord r) {
        GelRecordDto dto = new GelRecordDto();
        dto.setId(r.getId());
        dto.setFileName(r.getFileName());
        dto.setCtValue(r.getCtValue());
        dto.setBandIntensity(r.getBandIntensity());
        dto.setBandArea(r.getBandArea());
        dto.setRelativeIntensity(r.getRelativeIntensity());
        dto.setBandWidth(r.getBandWidth());
        dto.setWarning(r.getWarning());
        dto.setCreatedAt(r.getCreatedAt());
        return dto;
    }
}
