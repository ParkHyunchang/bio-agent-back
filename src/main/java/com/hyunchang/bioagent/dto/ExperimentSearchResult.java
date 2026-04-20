package com.hyunchang.bioagent.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ExperimentSearchResult {

    /** 전체 훈련 데이터 수 */
    private int totalRecords;

    /** 유사 범위(±range Ct) 내 실험 수 */
    private int similarCount;

    /** 유사 실험 목록 */
    private List<ExperimentItem> similarExperiments;

    /** 전체 데이터 통계 */
    private Statistics allStats;

    /** 유사 실험 통계 (similarCount > 0일 때만) */
    private Statistics similarStats;

    /** 현재 예측값이 과거 실험의 일반적 범위(평균±2σ) 내에 있는지 */
    private boolean inTypicalRange;

    @Data
    @Builder
    public static class ExperimentItem {
        private Long id;
        private String fileName;
        private Double ctValue;
        private Double bandIntensity;
        private String date;
    }

    @Data
    @Builder
    public static class Statistics {
        private double avgCt;
        private double minCt;
        private double maxCt;
        private double stdCt;
    }
}
