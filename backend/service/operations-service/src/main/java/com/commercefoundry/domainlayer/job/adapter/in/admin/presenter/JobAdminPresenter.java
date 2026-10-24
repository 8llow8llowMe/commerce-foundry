package com.commercefoundry.domainlayer.job.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.job.adapter.in.admin.dto.item.JobAdminItem;
import com.commercefoundry.domainlayer.job.adapter.in.admin.dto.response.JobAdminResponse;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.persistence.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
public class JobAdminPresenter {

    /** 페이지 정보(전체 건수 포함)는 그대로 두고 항목만 바꾼다. */
    public PageResponse<JobAdminItem> toPageResponse(Page<Job> jobs) {
        return PageResponse.of(jobs).map(this::toItem);
    }

    public JobAdminResponse toResponse(Job job) {
        return JobAdminResponse.builder()
            .jobId(String.valueOf(job.id()))
            .type(job.type())
            .status(job.status().toMetadata())
            .priority(job.priority())
            .progressPercent(job.progressPercent())
            .progressMessage(job.progressMessage())
            .attempt(job.attempt())
            .maxAttempts(job.maxAttempts())
            .cancelRequested(job.cancelRequested())
            .cancelRequestedByMemberId(idOrNull(job.cancelRequestedByMemberId()))
            .requestedByMemberId(idOrNull(job.requestedByMemberId()))
            .dedupKey(job.dedupKey())
            .payloadJson(job.payloadJson())
            .resultJson(job.resultJson())
            .errorCode(job.errorCode())
            .errorMessage(job.errorMessage())
            .scheduledAt(job.scheduledAt())
            .startedAt(job.startedAt())
            .finishedAt(job.finishedAt())
            .build();
    }

    private JobAdminItem toItem(Job job) {
        return JobAdminItem.builder()
            .jobId(String.valueOf(job.id()))
            .type(job.type())
            .status(job.status().toMetadata())
            .progressPercent(job.progressPercent())
            .attempt(job.attempt())
            .maxAttempts(job.maxAttempts())
            .cancelRequested(job.cancelRequested())
            .errorCode(job.errorCode())
            .requestedByMemberId(idOrNull(job.requestedByMemberId()))
            .scheduledAt(job.scheduledAt())
            .startedAt(job.startedAt())
            .finishedAt(job.finishedAt())
            .build();
    }

    private static String idOrNull(Long id) {
        return id == null ? null : String.valueOf(id);
    }
}
