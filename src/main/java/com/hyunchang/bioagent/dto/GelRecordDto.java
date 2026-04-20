package com.hyunchang.bioagent.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 학습 데이터 레코드 응답 DTO */
@Data
public class GelRecordDto {
    private Long id;
    private String fileName;
    private Double ctValue;
    private Double bandIntensity;
    private Double bandArea;
    private Double relativeIntensity;
    private Double bandWidth;
    private String warning;
    private LocalDateTime createdAt;
}
