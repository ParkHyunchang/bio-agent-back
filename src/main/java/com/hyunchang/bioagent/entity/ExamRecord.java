package com.hyunchang.bioagent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "exam_record")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    /** Claude Vision이 인식한 전체 원시 텍스트 */
    @Column(name = "raw_text", columnDefinition = "TEXT")
    private String rawText;

    /** 문서 유형 (예: 혈액검사, Western Blot 등) */
    @Column(name = "document_type", length = 100)
    private String documentType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "examRecord", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ExamItem> items = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
