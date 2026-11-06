package com.commercefoundry.domainlayer.job.application.port.in;

import com.commercefoundry.domainlayer.job.adapter.in.admin.dto.response.JobResultDownloadResponse;
import com.commercefoundry.security.principal.AdminPermission;
import java.util.Set;

public interface JobResultDownloadUseCase {
    JobResultDownloadResponse download(long jobId, long adminMemberId, Set<AdminPermission> permissions);
}
