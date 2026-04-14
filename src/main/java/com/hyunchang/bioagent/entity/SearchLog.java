package com.hyunchang.bioagent.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "search_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SEARCH / DETAIL / REVIEW */
    @Column(nullable = false, length = 20)
    private String type;

    /** 검색어 (SEARCH) */
    @Column(name = "query_text", length = 500)
    private String queryText;

    /** PubMed ID (DETAIL, REVIEW) */
    @Column(length = 20)
    private String pmid;

    /** 검색 결과 수 (SEARCH) */
    @Column(name = "result_count")
    private Integer resultCount;

    /** 논문 제목 (DETAIL, REVIEW) */
    @Column(name = "paper_title", length = 1000)
    private String paperTitle;

    /** API 소요 시간 (ms) */
    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
