package com.hyunchang.bioagent.repository;

import com.hyunchang.bioagent.entity.GelRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GelRecordRepository extends JpaRepository<GelRecord, Long> {

    List<GelRecord> findAllByOrderByCreatedAtDesc();

    boolean existsByFileHash(String fileHash);

    boolean existsByFileName(String fileName);

    @Query("SELECT r FROM GelRecord r WHERE r.ctValue BETWEEN :lower AND :upper ORDER BY ABS(r.ctValue - :target)")
    List<GelRecord> findSimilarByCt(@Param("target") double target,
                                    @Param("lower") double lower,
                                    @Param("upper") double upper);
}
