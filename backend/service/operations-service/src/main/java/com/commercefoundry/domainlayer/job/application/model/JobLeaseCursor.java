package com.commercefoundry.domainlayer.job.application.model;

import java.time.Instant;
import java.util.Objects;

/**
 * lease 만료 후보를 이어 읽는 위치 — 앞 배치의 마지막 건 (lease 만료 시각, 작업 아이디). 정렬 순서와 같은 칸이다.
 */
public record JobLeaseCursor(Instant leaseExpiresAt, long jobId) {

    public JobLeaseCursor {
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
    }
}
