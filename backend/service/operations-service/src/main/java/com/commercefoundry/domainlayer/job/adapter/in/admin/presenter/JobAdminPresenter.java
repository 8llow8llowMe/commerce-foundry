package com.commercefoundry.domainlayer.job.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.job.adapter.in.admin.dto.item.JobAdminItem;
import com.commercefoundry.domainlayer.job.adapter.in.admin.dto.response.JobAdminResponse;
import com.commercefoundry.domainlayer.job.application.info.JobSummaryInfo;
import com.commercefoundry.persistence.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
public class JobAdminPresenter {

    /** 페이지 정보(전체 건수 포함)는 그대로 두고 항목만 바꾼다. */
    public PageResponse<JobAdminItem> toPageResponse(Page<JobSummaryInfo> jobs) {
        return PageResponse.of(jobs).map(this::toItem);
    }

    public JobAdminResponse toResponse(JobSummaryInfo job) {
        return JobAdminResponse.builder()
            .jobId(String.valueOf(job.jobId()))
            .type(job.type())
            .typeName(job.typeName())
            .status(job.status().toMetadata())
            .progressPercent(job.progressPercent())
            .progressMessage(job.progressMessage())
            .notice(job.notice())
            .cancellable(job.cancellable())
            .requestedAt(job.requestedAt())
            .finishedAt(job.finishedAt())
            .build();
    }

    private JobAdminItem toItem(JobSummaryInfo job) {
        return JobAdminItem.builder()
            .jobId(String.valueOf(job.jobId()))
            .type(job.type())
            .typeName(job.typeName())
            .status(job.status().toMetadata())
            .progressPercent(job.progressPercent())
            .progressMessage(job.progressMessage())
            .notice(job.notice())
            .cancellable(job.cancellable())
            .requestedAt(job.requestedAt())
            .finishedAt(job.finishedAt())
            .build();
    }
}
