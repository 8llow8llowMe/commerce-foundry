package com.commercefoundry.domainlayer.job.application.info;

import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import java.time.Instant;

/**
 * 운영자에게 보이는 작업 한 건 (#305, {@code api-design-guide.md} §2-8) — 업무 언어만 담는다.
 *
 * @param typeName 작업 이름(핸들러가 정한 표시명, 없으면 종류 코드)
 * @param notice 운영자 안내 문구 — 실패 이유 · 재시도 대기 · 취소 요청됨. 알릴 것이 없으면 null
 * @param cancellable 지금 취소할 수 있다(대기 · 실행 중이고 아직 취소를 요청하지 않았다)
 * @param finishedAt 끝난 시각. 끝나지 않았으면 null
 */
public record JobSummaryInfo(
    long jobId,
    String type,
    String typeName,
    JobStatus status,
    Integer progressPercent,
    String progressMessage,
    String notice,
    boolean cancellable,
    Instant requestedAt,
    Instant finishedAt
) {
}
