package com.commercefoundry.domainlayer.job.application.model;

import com.commercefoundry.domainlayer.job.domain.model.JobStatus;

/**
 * 어드민 작업 목록의 상태 조건 ({@code @RequestParam}). 값은 {@link JobStatus} 와 같다 — 컨트롤러가 domain 타입을 쓰지 않으므로
 * (H1) 입력 타입을 application/model 에 두고 Processor 가 domain 으로 바꾼다.
 */
public enum JobStatusFilter {

    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public JobStatus toJobStatus() {
        return JobStatus.valueOf(name());
    }
}
