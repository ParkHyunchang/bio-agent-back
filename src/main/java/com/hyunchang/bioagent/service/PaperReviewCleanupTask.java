package com.hyunchang.bioagent.service;

import com.hyunchang.bioagent.repository.PaperReviewRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * paper_review_record 테이블의 무한 증가를 막기 위한 자동 정리 스케줄러.
 * 매일 새벽 3시(서버 로컬 타임)에 retention.days보다 오래된 기록을 일괄 삭제한다.
 *
 * 기본값은 365일. 운영 중에 보존 기간을 늘리거나 줄이고 싶으면 application.yml에서
 * {@code paper-review.retention.days}를 조정. {@code 0} 또는 음수로 설정하면 정리를 비활성화한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaperReviewCleanupTask {

    private final PaperReviewRecordRepository repository;

    @Value("${paper-review.retention.days:365}")
    private int retentionDays;

    /** 매일 03:00에 실행. 부하 시간대를 피해 서버가 한산할 때 수행. */
    @Scheduled(cron = "0 0 3 * * *")
    public void purgeOldRecords() {
        if (retentionDays <= 0) {
            log.debug("Paper review 자동 정리 비활성화 (retentionDays={})", retentionDays);
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        try {
            int deleted = repository.deleteOlderThan(cutoff);
            if (deleted > 0) {
                log.info("Paper review 자동 정리: {}일 이전 기록 {}건 삭제됨 (cutoff={})",
                        retentionDays, deleted, cutoff);
            } else {
                log.debug("Paper review 자동 정리: 삭제 대상 없음 (cutoff={})", cutoff);
            }
        } catch (Exception e) {
            log.error("Paper review 자동 정리 실패", e);
        }
    }
}
