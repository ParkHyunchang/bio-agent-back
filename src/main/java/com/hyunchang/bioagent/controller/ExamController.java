package com.hyunchang.bioagent.controller;

import com.hyunchang.bioagent.dto.OcrResultDto;
import com.hyunchang.bioagent.service.OcrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/exam")
@RequiredArgsConstructor
public class ExamController {

    private final OcrService ocrService;

    /** 이미지 OCR 추출 + 저장 (한 번에 여러 파일, 병렬 처리) */
    @PostMapping("/upload")
    public ResponseEntity<List<OcrResultDto>> upload(
            @RequestParam("files") List<MultipartFile> files) {
        return ResponseEntity.ok(ocrService.extractAndSaveAll(files));
    }

    /** 저장된 기록 전체 목록 */
    @GetMapping("/records")
    public ResponseEntity<List<OcrResultDto>> getRecords() {
        return ResponseEntity.ok(ocrService.findAll());
    }

    /** 특정 기록 상세 조회 */
    @GetMapping("/records/{id}")
    public ResponseEntity<OcrResultDto> getRecord(@PathVariable Long id) {
        return ResponseEntity.ok(ocrService.findById(id));
    }

    /** 기록 삭제 */
    @DeleteMapping("/records/{id}")
    public ResponseEntity<Void> deleteRecord(@PathVariable Long id) {
        ocrService.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
