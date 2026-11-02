package com.commercefoundry.domainlayer.job.application.port.out;

public interface JobResultAuditPort {
    void downloaded(long adminMemberId, long jobId, boolean success);
}
