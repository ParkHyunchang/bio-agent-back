package com.hyunchang.bioagent.repository;

import com.hyunchang.bioagent.entity.PaperReviewRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaperReviewRecordRepository extends JpaRepository<PaperReviewRecord, Long> {
    List<PaperReviewRecord> findAllByOrderByCreatedAtDesc();
    List<PaperReviewRecord> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
