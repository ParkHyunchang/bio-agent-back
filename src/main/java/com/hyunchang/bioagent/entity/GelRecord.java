package com.hyunchang.bioagent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "gel_record",
       uniqueConstraints = @UniqueConstraint(
           name = "uq_gel_file_hash_lane",
           columnNames = {"file_hash", "lane_index"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GelRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    /** 파일 내용의 SHA-256 해시. lane_index와 복합 유니크 제약으로 관리 */
    @Column(name = "file_hash", length = 64)
    private String fileHash;

    /** 레인 인덱스 (0=M, 1=10^8, ..., 8=10^1, 9=NTC). 단일 이미지 업로드 시 기본 0 */
    @Column(name = "lane_index", nullable = false, columnDefinition = "INT DEFAULT 0")
    @Builder.Default
    private int laneIndex = 0;

    /** 레인 농도 레이블 (M, 10^8, 10^7, ..., 10^1, NTC) */
    @Column(name = "concentration_label", length = 20)
    private String concentrationLabel;

    /** log10 환산 농도 (10^8→8.0, 10^1→1.0, M/NTC→null) */
    @Column(name = "log10_concentration")
    private Double log10Concentration;

    /** 외부 기관에서 측정한 실제 qPCR Ct값 */
    @Column(name = "ct_value", nullable = false)
    private Double ctValue;

    // ── OpenCV 추출 특징값 ─────────────────────────────────────────

    /** 밴드 영역 평균 픽셀 밝기 (0–255) */
    @Column(name = "band_intensity")
    private Double bandIntensity;

    /** 밴드 픽셀 면적 (px²) */
    @Column(name = "band_area")
    private Double bandArea;

    /** 이미지 최대 밝기 대비 상대값 (0–1) */
    @Column(name = "relative_intensity")
    private Double relativeIntensity;

    /** 밴드 수평 너비 (px) */
    @Column(name = "band_width")
    private Double bandWidth;

    /** 밴드 수직 높이 (px) */
    @Column(name = "band_height")
    private Double bandHeight;

    /** 포화 밴드 여부 (픽셀값 > 240) */
    @Column(name = "is_saturated", columnDefinition = "TINYINT(1) DEFAULT 0")
    @Builder.Default
    private Boolean isSaturated = false;

    /** 밴드 미검출(음성) 여부 */
    @Column(name = "is_negative", columnDefinition = "TINYINT(1) DEFAULT 0")
    @Builder.Default
    private Boolean isNegative = false;

    /** Python 서비스가 반환한 경고 메시지 (밴드 검출 이슈 등) */
    @Column(name = "warning", length = 500)
    private String warning;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
