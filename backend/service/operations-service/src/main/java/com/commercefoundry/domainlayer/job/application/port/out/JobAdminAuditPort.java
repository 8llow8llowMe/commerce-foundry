package com.commercefoundry.domainlayer.job.application.port.out;

/** 어드민 작업 행위 감사 ({@code security-guide.md} §11-4). 행위 · 결과마다 메서드 하나다. */
public interface JobAdminAuditPort {

    /** 작업 취소(QUEUED 즉시 취소 · RUNNING 취소 요청)가 반영됐다. */
    void jobCancelSucceeded(long adminMemberId, long jobId);

    /** 작업 취소가 실패했다(없는 작업 · 끝난 작업 · 충돌 · 저장소 장애) — 이유는 나누지 않는다. */
    void jobCancelFailed(long adminMemberId, long jobId);
}
