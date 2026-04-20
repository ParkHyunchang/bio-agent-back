package com.hyunchang.bioagent.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class InterpretationResult {

    /** 양성/음성 분류: 강양성, 양성, 약양성, 경계값, 음성 */
    private String classification;

    /** Ct값 범위 설명 (예: "Ct < 25: 고농도 양성") */
    private String ctRangeDescription;

    /** 모델 신뢰도: 높음 / 보통 / 낮음 / 매우 낮음 */
    private String modelReliability;

    /** 밴드 품질: 양호 / 보통 / 낮음 */
    private String bandQuality;

    /** 재검 권장 여부 */
    private boolean retestRecommended;

    /** 권장 사항 메시지 */
    private String recommendation;
}
