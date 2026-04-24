package com.hyunchang.bioagent.controller;

import com.hyunchang.bioagent.dto.OcrResultDto;
import com.hyunchang.bioagent.service.OcrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/exam")
@RequiredArgsConstructor
public class ExamController {

    private static final int MAX_FILES_PER_REQUEST = 20;

    private final OcrService ocrService;

    /** 이미지 OCR 추출 + 저장 (한 번에 여러 파일, 병렬 처리) */
    @PostMapping("/upload")
    public ResponseEntity<List<OcrResultDto>> upload(
            @RequestParam("files") List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "업로드할 파일이 없습니다.");
        }
        if (files.size() > MAX_FILES_PER_REQUEST) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "한 번에 업로드할 수 있는 최대 파일 수를 초과했습니다 (최대 " + MAX_FILES_PER_REQUEST + "개).");
        }
        return ResponseEntity.ok(ocrService.extractAndSaveAll(files));
    }

    /** 저장된 기록 목록 (페이지네이션) */
    @GetMapping("/records")
    public ResponseEntity<List<OcrResultDto>> getRecords(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "100") int size) {
        return ResponseEntity.ok(ocrService.findAll(page, size));
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
