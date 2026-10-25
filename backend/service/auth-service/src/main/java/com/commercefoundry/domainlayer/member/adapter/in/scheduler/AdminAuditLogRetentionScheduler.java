package com.commercefoundry.domainlayer.member.adapter.in.scheduler;

import com.commercefoundry.common.exception.RedactedThrowable;
import com.commercefoundry.domainlayer.member.application.port.in.AdminAuditLogRetentionSchedulerUseCase;
import com.commercefoundry.global.properties.AdminAuditRetentionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보관 기간이 지난 관리자 감사 로그를 지운다 ({@code security-guide.md} §11-4). 주기 · 배치 크기는 {@code auth.admin-audit.retention.*} 다.
 *
 * <p>한 실행에서 컷오프를 한 번 정하고, 배치가 가득 차면 다음 배치를 이어서 지운다. 덜 찬 배치에서 멈춘다.
 * 한 배치가 실패하면 그 실행은 멈추고 다음 주기가 남은 행을 다시 집는다 — 삭제는 멱등이라 같은 행을 다시 골라도 된다.
 *
 * <p>인스턴스가 여럿이어도 분산 락을 두지 않는다. 같은 행을 두 인스턴스가 지워도 한 번만 사라진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAuditLogRetentionScheduler {

    private final AdminAuditLogRetentionSchedulerUseCase adminAuditLogRetentionUseCase;
    private final AdminAuditRetentionProperties adminAuditRetentionProperties;

    @Scheduled(
        initialDelayString = "${auth.admin-audit.retention.initial-delay}",
        fixedDelayString = "${auth.admin-audit.retention.fixed-delay}"
    )
    public void purgeExpired() {
        long cutoffId = adminAuditLogRetentionUseCase.cutoffId();
        int deleted = 0;
        for (int batch = 0; batch < adminAuditRetentionProperties.maxBatchesPerRun(); batch++) {
            int selected;
            try {
                selected = adminAuditLogRetentionUseCase.deleteBatch(cutoffId, adminAuditRetentionProperties.batchSize());
            } catch (RuntimeException exception) {
                log.error("admin audit retention batch failed. error={}", exception.getClass().getSimpleName(), RedactedThrowable.of(exception));
                break;
            }
            deleted += selected;
            if (selected < adminAuditRetentionProperties.batchSize()) {
                break;
            }
        }
        if (deleted > 0) {
            log.info("admin audit logs purged. count={}", deleted);
        }
    }
}
