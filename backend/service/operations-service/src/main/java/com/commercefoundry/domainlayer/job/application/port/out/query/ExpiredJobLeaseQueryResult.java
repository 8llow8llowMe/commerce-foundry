package com.commercefoundry.domainlayer.job.application.port.out.query;

import java.time.Instant;

/** lease 가 끝난 RUNNING 한 건 — 아이디와 keyset 커서({@code lease_expires_at, id})만 읽는다. 회수는 건마다 다시 읽어 판정한다. */
public record ExpiredJobLeaseQueryResult(long jobId, Instant leaseExpiresAt) {
}
