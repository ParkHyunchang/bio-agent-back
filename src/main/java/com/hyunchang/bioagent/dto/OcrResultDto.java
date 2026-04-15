package com.hyunchang.bioagent.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class OcrResultDto {
    private Long id;
    private String fileName;
    private String documentType;
    private String rawText;
    private LocalDateTime createdAt;
    private List<ExamItemDto> items;
}
