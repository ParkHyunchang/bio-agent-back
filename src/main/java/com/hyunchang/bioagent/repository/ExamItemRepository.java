package com.hyunchang.bioagent.repository;

import com.hyunchang.bioagent.entity.ExamItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExamItemRepository extends JpaRepository<ExamItem, Long> {
    List<ExamItem> findByExamRecordId(Long examRecordId);
}
