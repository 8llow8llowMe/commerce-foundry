package com.commercefoundry.domainlayer.job.application.port.out.query;

import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import java.time.Instant;

/**
 * 어드민 작업 목록 · 상세의 읽기 모델 (#305). 운영자 화면에 필요한 칸과, 안내 문구를 고르는 데만 쓰는 칸({@code cancelRequested} · {@code errorCode})이다 —
 * 시도 · 입력 · 결과 원문 · 워커 정보는 읽지 않는다. 요청 시각은 행의 생성 시각이다(재시도하면 바뀌는 {@code scheduledAt} 이 아니다).
 */
public record JobSummaryQueryResult(
    long jobId,
    String type,
    JobStatus status,
    Integer progressPercent,
    String progressMessage,
    boolean cancelRequested,
    String errorCode,
    Instant requestedAt,
    Instant finishedAt
) {
}
