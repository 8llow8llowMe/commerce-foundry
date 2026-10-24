package com.commercefoundry.domainlayer.job.application.service.processor;

import com.commercefoundry.domainlayer.job.application.port.out.JobAdminAuditPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 어드민 작업 행위 감사. Facade 는 감사 포트를 직접 쓰지 않고 이 Processor 를 부른다 (ArchUnit H11, {@code security-guide.md} §11-4).
 * 업무가 끝난 뒤(트랜잭션 밖) 부른다.
 */
@Component
@RequiredArgsConstructor
public class JobAdminAuditProcessor {

    private final JobAdminAuditPort jobAdminAuditPort;

    public void cancelSucceeded(long adminMemberId, long jobId) {
        jobAdminAuditPort.jobCancelSucceeded(adminMemberId, jobId);
    }

    public void cancelFailed(long adminMemberId, long jobId) {
        jobAdminAuditPort.jobCancelFailed(adminMemberId, jobId);
    }
}
