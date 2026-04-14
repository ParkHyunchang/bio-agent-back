package com.hyunchang.bioagent.repository;

import com.hyunchang.bioagent.entity.SearchLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SearchLogRepository extends JpaRepository<SearchLog, Long> {
}
