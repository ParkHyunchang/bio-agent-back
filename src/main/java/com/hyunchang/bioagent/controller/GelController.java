package com.hyunchang.bioagent.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.bioagent.dto.GelLanePredictionDto;
import com.hyunchang.bioagent.dto.GelPredictResult;
import com.hyunchang.bioagent.dto.GelRecordDto;
import com.hyunchang.bioagent.service.GelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/gel")
@RequiredArgsConstructor
public class GelController {

    private final GelService gelService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 단일 이미지 학습 데이터 등록 (하위 호환) */
    @PostMapping("/upload")
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("ctValue") Double ctValue) {
        try {
            return ResponseEntity.ok(gelService.uploadTrainingData(file, ctValue));
        } catch (IllegalArgumentException e) {
            if ("DUPLICATE".equals(e.getMessage())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("duplicate", true));
            }
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("학습 데이터 업로드 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /** 멀티레인 학습 데이터 등록 */
    @PostMapping("/upload-gel")
    public ResponseEntity<?> uploadGel(
            @RequestParam("file") MultipartFile file,
            @RequestParam("ctValues") String ctValuesJson) {
        try {
            Map<String, Double> ctValues = objectMapper.readValue(
                    ctValuesJson, new TypeReference<Map<String, Double>>() {});
            List<GelRecordDto> saved = gelService.uploadMultiLaneGel(file, ctValues);
            if (saved.isEmpty()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("duplicate", true, "message", "모든 레인이 이미 등록되어 있습니다."));
            }
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("멀티레인 업로드 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** 학습 데이터 목록 조회 */
    @GetMapping("/records")
    public ResponseEntity<List<GelRecordDto>> getRecords() {
        return ResponseEntity.ok(gelService.findAll());
    }

    /** 학습 데이터 삭제 */
    @DeleteMapping("/records/{id}")
    public ResponseEntity<Void> deleteRecord(@PathVariable Long id) {
        gelService.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** 회귀 모델 학습 */
    @PostMapping("/train")
    public ResponseEntity<Map<String, Object>> train() {
        try {
            return ResponseEntity.ok(gelService.trainModel());
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("모델 학습 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** 단일 이미지 Ct값 예측 (하위 호환) */
    @PostMapping("/predict")
    public ResponseEntity<GelPredictResult> predict(
            @RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(gelService.predict(file));
        } catch (Exception e) {
            log.error("Ct 예측 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /** 멀티레인 Ct값 예측 */
    @PostMapping("/predict-gel")
    public ResponseEntity<?> predictGel(
            @RequestParam("file") MultipartFile file) {
        try {
            List<GelLanePredictionDto> lanes = gelService.predictGelLanes(file);
            return ResponseEntity.ok(lanes);
        } catch (Exception e) {
            log.error("멀티레인 예측 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** 멀티레인 피처 추출 (예측 없음 — 학습 데이터 등록용) */
    @PostMapping("/extract-gel")
    public ResponseEntity<?> extractGel(
            @RequestParam("file") MultipartFile file) {
        try {
            List<GelLanePredictionDto> lanes = gelService.extractGelLanesOnly(file);
            return ResponseEntity.ok(lanes);
        } catch (Exception e) {
            log.error("멀티레인 추출 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** 이미지에서 Ct값 자동 추출 (Claude Vision) */
    @PostMapping("/auto-ct")
    public ResponseEntity<?> autoExtractCt(@RequestParam("file") MultipartFile file) {
        try {
            Map<String, Double> ctValues = gelService.extractCtValuesFromImage(
                    file.getBytes(), file.getContentType());
            return ResponseEntity.ok(ctValues);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Ct값 자동 추출 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** 학습 모델 초기화 */
    @DeleteMapping("/model")
    public ResponseEntity<Void> resetModel() {
        try {
            gelService.resetModel();
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("모델 초기화 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /** 현재 모델 상태 조회 */
    @GetMapping("/model/status")
    public ResponseEntity<Map<String, Object>> modelStatus() {
        try {
            return ResponseEntity.ok(gelService.getModelStatus());
        } catch (Exception e) {
            log.error("모델 상태 조회 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
