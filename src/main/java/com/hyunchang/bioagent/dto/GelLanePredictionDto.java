package com.hyunchang.bioagent.dto;

import lombok.Data;

/** 멀티레인 젤 이미지 레인별 Ct 예측 결과 DTO */
@Data
public class GelLanePredictionDto {
    private int laneIndex;
    private String concentrationLabel;
    private Double predictedCt;
    private Double bandIntensity;
    private Double bandArea;
    private Double relativeIntensity;
    private Double bandWidth;
    private Double bandHeight;
    private Boolean isSaturated;
    private Boolean isNegative;
    private Boolean isPrimerDimer;
    private Double modelR2;
    private Double modelRmse;
    private String warning;
}
