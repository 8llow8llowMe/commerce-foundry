package com.commercefoundry.domainlayer.job.application.service;

import com.commercefoundry.domainlayer.job.adapter.in.admin.dto.response.JobResultDownloadResponse;
import com.commercefoundry.domainlayer.job.adapter.in.admin.presenter.JobResultDownloadPresenter;
import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.port.in.JobResultDownloadUseCase;
import com.commercefoundry.domainlayer.job.application.service.processor.JobQueryProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobResultAuditProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobResultDownloadProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobResultFileProcessor;
import com.commercefoundry.security.principal.AdminPermission;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 외부 I/O를 DB 트랜잭션에 포함하지 않는다. 서명 후 다시 읽어 현재 상태·만료를 확인한다. */
@Service
@RequiredArgsConstructor
public class JobResultDownloadFacade implements JobResultDownloadUseCase {
    private final JobQueryProcessor jobs;
    private final JobResultFileProcessor files;
    private final JobResultDownloadProcessor downloads;
    private final JobResultAuditProcessor audit;
    private final JobResultDownloadPresenter presenter;

    @Override
    public JobResultDownloadResponse download(long jobId, long adminMemberId, Set<AdminPermission> permissions) {
        try {
            jobs.get(jobId, permissions);
            var file = files.downloadable(jobId);
            var signed = downloads.sign(file);
            var current = files.downloadable(jobId);
            if (current.id() != file.id() || !current.version().equals(file.version())) {
                throw new JobException(JobErrorCode.JOB_CONFLICT);
            }
            audit.downloaded(adminMemberId, jobId, true);
            return presenter.present(signed);
        } catch (RuntimeException exception) {
            audit.downloaded(adminMemberId, jobId, false);
            throw exception;
        }
    }
}
