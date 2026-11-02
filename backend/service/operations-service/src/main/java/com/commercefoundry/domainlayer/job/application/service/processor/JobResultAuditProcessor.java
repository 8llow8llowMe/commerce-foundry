package com.commercefoundry.domainlayer.job.application.service.processor;

import com.commercefoundry.domainlayer.job.application.port.out.JobResultAuditPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JobResultAuditProcessor {
    private final JobResultAuditPort audit;

    public void downloaded(long adminMemberId, long jobId, boolean success) {
        audit.downloaded(adminMemberId, jobId, success);
    }
}
