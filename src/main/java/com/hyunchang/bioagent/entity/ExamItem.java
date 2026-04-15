package com.hyunchang.bioagent.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "exam_item")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_record_id", nullable = false)
    @ToString.Exclude
    private ExamRecord examRecord;

    /** 항목명 (예: 혈당, Hemoglobin) */
    @Column(name = "item_name", nullable = false, length = 200)
    private String itemName;

    /** 측정값 */
    @Column(length = 100)
    private String value;

    /** 단위 (예: mg/dL, %) */
    @Column(length = 50)
    private String unit;

    /** 참고범위 (예: 70-100, <200) */
    @Column(name = "reference_range", length = 100)
    private String referenceRange;

    /** 정상 범위 이탈 여부 */
    @Column(name = "is_abnormal")
    private Boolean isAbnormal;
}
