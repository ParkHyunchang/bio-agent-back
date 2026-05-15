package com.hyunchang.bioagent.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 학습 데이터 레코드 응답 DTO */
@Data
public class GelRecordDto {
    private Long id;
    private String fileName;
    private int laneIndex;
    private String concentrationLabel;
    private Double log10Concentration;
    private Double ctValue;
    private Double bandIntensity;
    private Double bandArea;
    private Double relativeIntensity;
    private Double bandWidth;
    private Double bandHeight;
    private Boolean isSaturated;
    private Boolean isNegative;
    private Boolean isFaint;
    private String warning;
    private LocalDateTime createdAt;
}
