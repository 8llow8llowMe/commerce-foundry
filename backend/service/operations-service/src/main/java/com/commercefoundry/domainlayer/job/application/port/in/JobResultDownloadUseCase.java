package com.commercefoundry.domainlayer.job.application.port.in;

import com.commercefoundry.domainlayer.job.adapter.in.admin.dto.response.JobResultDownloadResponse;

public interface JobResultDownloadUseCase {
    JobResultDownloadResponse download(long jobId, long adminMemberId);
}
