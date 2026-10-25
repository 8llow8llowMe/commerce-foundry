package com.commercefoundry.domainlayer.job.application.port.in;

import com.commercefoundry.domainlayer.job.application.info.ExpiredJobLeaseInfo;
import com.commercefoundry.domainlayer.job.application.model.JobLeaseCursor;
import java.util.List;

/**
 * lease 만료 판정 (스케줄러 진입점, {@code architecture-guide.md} §10). 워커가 죽어 heartbeat 가 끊긴 RUNNING 을 건마다 회수한다.
 */
public interface JobLeaseSweepUseCase {

    /**
     * lease 가 끝난 RUNNING 후보.
     *
     * @param after 앞 배치의 마지막 건 위치. 처음이면 null
     */
    List<ExpiredJobLeaseInfo> findExpiredLeases(JobLeaseCursor after, int limit);

    /**
     * 한 건을 회수한다 — 시도가 남았으면 QUEUED 로 돌리고 실행 신호를 다시 내고, 시도 상한이면 FAILED, 취소 요청을 받았으면 CANCELLED 다.
     *
     * @return 회수했으면 true, 그 사이 lease 가 연장됐거나 끝났으면 false
     * @throws com.commercefoundry.domainlayer.job.application.exception.JobException {@code JOB_CONFLICT} 읽은 뒤 다른 쪽이 먼저 바꿨다
     */
    boolean expireLease(long jobId);
}
