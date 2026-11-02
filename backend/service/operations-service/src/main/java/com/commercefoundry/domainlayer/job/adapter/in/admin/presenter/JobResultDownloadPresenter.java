package com.commercefoundry.domainlayer.job.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.job.adapter.in.admin.dto.response.JobResultDownloadResponse;
import com.commercefoundry.domainlayer.job.application.info.JobResultDownloadInfo;
import org.springframework.stereotype.Component;

@Component
public class JobResultDownloadPresenter {
    public JobResultDownloadResponse present(JobResultDownloadInfo info) {
        return new JobResultDownloadResponse(info.url(), info.expiresAt(), info.fileName(), info.contentType(), info.sizeBytes());
    }
}
