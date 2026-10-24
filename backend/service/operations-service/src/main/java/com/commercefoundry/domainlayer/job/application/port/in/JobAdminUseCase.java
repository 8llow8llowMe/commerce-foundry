package com.commercefoundry.domainlayer.job.application.port.in;

import com.commercefoundry.domainlayer.job.adapter.in.admin.dto.item.JobAdminItem;
import com.commercefoundry.domainlayer.job.adapter.in.admin.dto.response.JobAdminResponse;
import com.commercefoundry.domainlayer.job.application.command.JobAdminSearchCommand;
import com.commercefoundry.persistence.dto.PageResponse;

public interface JobAdminUseCase {

    /** 등록 최신순 한 페이지. 조건에 맞는 작업이 없으면 빈 페이지다. */
    PageResponse<JobAdminItem> getJobs(JobAdminSearchCommand command);

    /** @throws com.commercefoundry.domainlayer.job.application.exception.JobException {@code NOT_FOUND_JOB} */
    JobAdminResponse getJob(long jobId);

    /**
     * 취소한다. QUEUED 는 바로 CANCELLED, RUNNING 은 취소 요청 플래그만 세운다(이미 세웠으면 그대로 — 멱등). 성공 · 실패 모두 감사 로그를 남긴다.
     *
     * @throws com.commercefoundry.domainlayer.job.application.exception.JobException {@code NOT_FOUND_JOB}, {@code JOB_ALREADY_FINISHED},
     *     {@code JOB_CONFLICT}(다시 시도해도 계속 겹쳤다)
     */
    JobAdminResponse cancel(long jobId, long adminMemberId);
}
