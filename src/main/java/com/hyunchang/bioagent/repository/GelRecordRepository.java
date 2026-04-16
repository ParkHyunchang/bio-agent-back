package com.hyunchang.bioagent.repository;

import com.hyunchang.bioagent.entity.GelRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GelRecordRepository extends JpaRepository<GelRecord, Long> {

    List<GelRecord> findAllByOrderByCreatedAtDesc();
}
