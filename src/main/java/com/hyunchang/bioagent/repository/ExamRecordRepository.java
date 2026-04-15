package com.hyunchang.bioagent.repository;

import com.hyunchang.bioagent.entity.ExamRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExamRecordRepository extends JpaRepository<ExamRecord, Long> {
    List<ExamRecord> findAllByOrderByCreatedAtDesc();
}
