package com.commercefoundry.domainlayer.job.adapter.in.scheduler;

import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.info.ExpiredJobLeaseInfo;
import com.commercefoundry.domainlayer.job.application.model.JobLeaseCursor;
import com.commercefoundry.domainlayer.job.application.port.in.JobLeaseSweepUseCase;
import com.commercefoundry.global.properties.JobProperties;
import com.commercefoundry.global.support.WarnThrottle;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * lease 만료 판정 트리거 ({@code architecture-guide.md} §10). Kafka 는 지연 전달이 없어 "lease 가 끝났다" 는 시간 기준 판정을 주기 실행으로 둔다.
 * 주기 · 배치 크기는 {@code operations.jobs.lease-sweep.*} 다. 워커를 끈 인스턴스도 돌린다 — 실행 인스턴스가 모두 죽어도 회수된다.
 *
 * <p>건마다 유스케이스를 따로 불러 트랜잭션을 나눈다 — 한 건이 실패하거나 워커 · 다른 인스턴스와 겹쳐 409 가 나도 로그만 남기고 다음 건으로 간다.
 * 한 실행 안에서는 배치의 마지막 건을 커서로 넘겨 다음 건부터 읽는다(keyset) — 실패한 건을 같은 실행이 다시 읽지 않는다. 실패한 건은 다음 실행이 다시 읽는다.
 *
 * <p>인스턴스가 여럿이어도 분산 락(ShedLock)을 두지 않는다. 같은 건을 둘이 회수하면 {@code @Version} 이 하나만 통과시킨다 — 실행 신호도 한 번만 쓰인다.
 */
@Slf4j
@Component
public class JobLeaseExpiryScheduler {

    private final JobLeaseSweepUseCase jobLeaseSweepUseCase;
    private final JobProperties.LeaseSweep sweep;
    private final WarnThrottle warnings;

    public JobLeaseExpiryScheduler(JobLeaseSweepUseCase jobLeaseSweepUseCase, JobProperties jobProperties) {
        this.jobLeaseSweepUseCase = jobLeaseSweepUseCase;
        this.sweep = jobProperties.leaseSweep();
        this.warnings = new WarnThrottle(sweep.fixedDelay());
    }

    @Scheduled(
        initialDelayString = "${operations.jobs.lease-sweep.initial-delay}",
        fixedDelayString = "${operations.jobs.lease-sweep.fixed-delay}"
    )
    public void expireLeases() {
        int expired = 0;
        JobLeaseCursor cursor = null;
        try {
            for (int batch = 0; batch < sweep.maxBatchesPerRun(); batch++) {
                List<ExpiredJobLeaseInfo> leases = jobLeaseSweepUseCase.findExpiredLeases(cursor, sweep.batchSize());
                for (ExpiredJobLeaseInfo lease : leases) {
                    expired += expireOne(lease.jobId()) ? 1 : 0;
                }
                if (leases.size() < sweep.batchSize()) {
                    break;
                }
                ExpiredJobLeaseInfo last = leases.getLast();
                cursor = new JobLeaseCursor(last.leaseExpiresAt(), last.jobId());
            }
        } catch (RuntimeException exception) {
            // 후보 조회 실패(DB 장애 등) — 주기마다 쌓지 않고 간격당 한 번만 warn 한다
            long suppressed = warnings.tryAcquire(exception.getClass().getName());
            if (suppressed >= 0) {
                log.warn("job lease sweep failed, will retry. error={}, suppressedSinceLastWarn={}",
                    exception.getClass().getSimpleName(), suppressed, exception);
            }
        }
        if (expired > 0) {
            log.info("job leases expired. count={}", expired);
        }
    }

    /**
     * 실패는 이 건만 롤백된다. 아이디 · 오류 코드만 남긴다. 워커 · 다른 인스턴스와 겹친 409({@code JOB_CONFLICT})는 정상 경합이라 debug 다 —
     * 모든 인스턴스가 판정하므로 만료된 건마다 진 인스턴스 수만큼 난다. 그 밖의 업무 오류 · 예상하지 못한 예외는 error 다.
     */
    private boolean expireOne(long jobId) {
        try {
            return jobLeaseSweepUseCase.expireLease(jobId);
        } catch (JobException exception) {
            String code = exception.getErrorCode().getCode();
            if (exception.getErrorCode() == JobErrorCode.JOB_CONFLICT) {
                log.debug("job lease expire conflicted. jobId={}, errorCode={}", jobId, code);
            } else {
                log.error("job lease expire failed. jobId={}, errorCode={}", jobId, code);
            }
            return false;
        } catch (RuntimeException exception) {
            log.error("job lease expire failed. jobId={}, error={}", jobId, exception.getClass().getSimpleName(), exception);
            return false;
        }
    }
}
