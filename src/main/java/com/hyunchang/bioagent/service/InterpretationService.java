package com.hyunchang.bioagent.service;

import com.hyunchang.bioagent.dto.InterpretationResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Ct값, 모델 성능, 밴드 품질을 기반으로 구조화된 해석 결과를 생성합니다.
 * 이 로직은 Claude의 추론이 아닌 코드로 확정된 기준을 적용합니다.
 */
@Service
public class InterpretationService {

    public InterpretationResult interpret(
            double predictedCt,
            double modelR2,
            double modelRmse,
            double bandIntensity,
            int lanesDetected
    ) {
        String classification    = classifyCt(predictedCt);
        String ctRangeDesc       = ctRangeDescription(predictedCt);
        String modelReliability  = assessModelReliability(modelR2);
        String bandQuality       = assessBandQuality(bandIntensity);
        boolean retest           = shouldRetest(predictedCt, modelR2, modelRmse, lanesDetected);
        String recommendation    = buildRecommendation(predictedCt, modelR2, modelRmse, lanesDetected, retest);

        return InterpretationResult.builder()
                .classification(classification)
                .ctRangeDescription(ctRangeDesc)
                .modelReliability(modelReliability)
                .bandQuality(bandQuality)
                .retestRecommended(retest)
                .recommendation(recommendation)
                .build();
    }

    // ── Ct값 분류 ────────────────────────────────────────────────

    private String classifyCt(double ct) {
        if (ct < 20)       return "강양성";
        if (ct < 25)       return "양성";
        if (ct < 30)       return "중등도 양성";
        if (ct < 35)       return "약양성";
        if (ct < 40)       return "경계값";
        return "음성";
    }

    private String ctRangeDescription(double ct) {
        if (ct < 20)       return "Ct < 20: 고농도 타겟 검출 (강양성)";
        if (ct < 25)       return "Ct 20–25: 중~고농도 타겟 검출 (양성)";
        if (ct < 30)       return "Ct 25–30: 중등도 타겟 검출 (양성)";
        if (ct < 35)       return "Ct 30–35: 저농도 타겟 검출 (약양성)";
        if (ct < 40)       return "Ct 35–40: 극미량 검출 또는 비특이 증폭 가능성 (경계값)";
        return "Ct ≥ 40: 타겟 미검출 (음성)";
    }

    // ── 모델 신뢰도 ──────────────────────────────────────────────

    private String assessModelReliability(double r2) {
        if (r2 >= 0.9)  return "높음";
        if (r2 >= 0.7)  return "보통";
        if (r2 >= 0.5)  return "낮음";
        return "매우 낮음";
    }

    // ── 밴드 품질 ────────────────────────────────────────────────

    private String assessBandQuality(double bandIntensity) {
        if (bandIntensity > 150) return "양호";
        if (bandIntensity > 80)  return "보통";
        return "낮음";
    }

    // ── 재검 여부 ────────────────────────────────────────────────

    private boolean shouldRetest(double ct, double r2, double rmse, int lanes) {
        // 경계값 구간
        if (ct >= 35 && ct < 40) return true;
        // 모델 신뢰도 낮고 결과가 양성 범위인 경우
        if (r2 < 0.7 && ct < 40) return true;
        // RMSE가 크고 경계 근처인 경우
        if (rmse > 2.0 && ct >= 30) return true;
        // 래더 없이 밴드 1개만 검출된 경우
        if (lanes == 1) return true;
        return false;
    }

    // ── 권장 사항 ────────────────────────────────────────────────

    private String buildRecommendation(double ct, double r2, double rmse, int lanes, boolean retest) {
        List<String> items = new ArrayList<>();

        if (ct >= 35 && ct < 40) {
            items.add("경계값 범위입니다. qPCR 재검사를 권장합니다.");
        }
        if (r2 < 0.5) {
            items.add("모델 신뢰도가 매우 낮습니다. 훈련 데이터를 추가하여 모델을 재학습하세요.");
        } else if (r2 < 0.7) {
            items.add("모델 신뢰도가 낮습니다. 추가 훈련 데이터 등록을 권장합니다.");
        }
        if (rmse > 3.0) {
            items.add(String.format("예측 오차(RMSE ±%.2f Ct)가 큽니다. 결과 해석에 주의하세요.", rmse));
        }
        if (lanes == 1) {
            items.add("밴드 1개만 검출되었습니다. 래더 포함 여부를 확인하세요.");
        }
        if (lanes == 0) {
            items.add("밴드가 검출되지 않았습니다. 이미지 품질 또는 실험 조건을 확인하세요.");
        }
        if (!retest && ct < 35) {
            items.add("정상 범위의 결과입니다. 별도 조치가 필요하지 않습니다.");
        }

        return String.join(" | ", items);
    }
}
