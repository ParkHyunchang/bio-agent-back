package com.hyunchang.bioagent.repository;

import com.hyunchang.bioagent.entity.PaperReviewRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface PaperReviewRecordRepository extends JpaRepository<PaperReviewRecord, Long> {
    List<PaperReviewRecord> findAllByOrderByCreatedAtDesc();
    List<PaperReviewRecord> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countBy();

    /** PMID 컬럼만 select (paperTitle/reviewText 등 무거운 컬럼 회피). */
    @Query("SELECT DISTINCT r.pmid FROM PaperReviewRecord r WHERE r.pmid IS NOT NULL")
    List<String> findAllPmids();

    /** createdAt 기준 만료 기록 일괄 삭제. 반환값은 삭제된 row 수. */
    @Modifying
    @Transactional
    @Query("DELETE FROM PaperReviewRecord r WHERE r.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
