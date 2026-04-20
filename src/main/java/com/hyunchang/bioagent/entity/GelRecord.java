package com.hyunchang.bioagent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "gel_record")
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

    /** 파일 내용의 SHA-256 해시 (중복 등록 방지) */
    @Column(name = "file_hash", length = 64, unique = true)
    private String fileHash;

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
