package com.hyunchang.bioagent.dto;

import lombok.Data;
import java.util.Map;

/** Ct값 예측 결과 응답 DTO */
@Data
public class GelPredictResult {
    /** 예측된 Ct값 */
    private Double predictedCt;
    /** 현재 모델의 교차검증 R² */
    private Double modelR2;
    /** 현재 모델의 훈련 RMSE */
    private Double modelRmse;
    /** 이미지에서 추출된 특징값 (참고용) */
    private Map<String, Object> features;
    /** 경고 메시지 (밴드 검출 이상 시) */
    private String warning;
}
