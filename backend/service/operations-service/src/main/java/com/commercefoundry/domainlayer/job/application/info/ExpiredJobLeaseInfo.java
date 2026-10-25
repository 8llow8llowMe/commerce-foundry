package com.commercefoundry.domainlayer.job.application.info;

import java.time.Instant;

/** lease 가 끝난 작업 후보. 만료 판정 트리거가 다음 배치의 커서로 돌려준다 — 회수는 건마다 다시 읽어 판정한다. */
public record ExpiredJobLeaseInfo(long jobId, Instant leaseExpiresAt) {
}
