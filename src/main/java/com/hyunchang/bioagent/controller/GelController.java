package com.hyunchang.bioagent.controller;

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

    /** 학습 데이터 등록: PCR 젤 이미지 + 실측 Ct값 */
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

    /** 회귀 모델 학습 (DB 전체 데이터 사용) */
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

    /** 새 이미지로 Ct값 예측 */
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
