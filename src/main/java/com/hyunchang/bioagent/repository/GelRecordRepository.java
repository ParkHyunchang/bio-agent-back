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

    boolean existsByFileHashAndLaneIndex(String fileHash, int laneIndex);

    List<GelRecord> findByFileHashOrderByLaneIndex(String fileHash);

    /** M, NTC 제외하고 Ct값이 있는 레인만 반환 (학습용) */
    @Query("SELECT r FROM GelRecord r WHERE r.ctValue IS NOT NULL " +
           "AND r.concentrationLabel IS NOT NULL " +
           "AND r.concentrationLabel NOT IN ('M', 'NTC')")
    List<GelRecord> findAllWithCtValues();

    @Query("SELECT r FROM GelRecord r WHERE r.ctValue BETWEEN :lower AND :upper ORDER BY ABS(r.ctValue - :target)")
    List<GelRecord> findSimilarByCt(@Param("target") double target,
                                    @Param("lower") double lower,
                                    @Param("upper") double upper);
}
