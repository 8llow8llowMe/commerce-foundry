package com.commercefoundry.domainlayer.job.application.service.processor;

import com.commercefoundry.domainlayer.job.application.command.JobAdminSearchCommand;
import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.port.out.JobRepositoryPort;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/** 어드민 작업 조회 — 목록 · 상세. 트랜잭션은 부른 Facade 가 건다. */
@Component
@RequiredArgsConstructor
public class JobQueryProcessor {

    private final JobRepositoryPort jobRepositoryPort;

    public Page<Job> search(JobAdminSearchCommand command) {
        JobStatus status = command.status() == null ? null : command.status().toJobStatus();
        return jobRepositoryPort.findPage(status, command.type(), command.page(), command.size());
    }

    /** @throws JobException {@code NOT_FOUND_JOB} */
    public Job get(long jobId) {
        return jobRepositoryPort.findById(jobId).orElseThrow(() -> new JobException(JobErrorCode.NOT_FOUND_JOB));
    }
}
