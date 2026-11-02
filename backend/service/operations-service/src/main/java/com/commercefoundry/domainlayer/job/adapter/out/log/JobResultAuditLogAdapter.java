package com.commercefoundry.domainlayer.job.adapter.out.log;

import com.commercefoundry.domainlayer.job.application.port.out.JobResultAuditPort;
import com.commercefoundry.security.audit.AdminAuditLog;
import org.springframework.stereotype.Component;

@Component
public class JobResultAuditLogAdapter implements JobResultAuditPort {
    @Override
    public void downloaded(long adminMemberId, long jobId, boolean success) {
        AdminAuditLog.write("JOB_RESULT_DOWNLOAD", adminMemberId, AdminAuditLog.target("job", jobId), success);
    }
}
