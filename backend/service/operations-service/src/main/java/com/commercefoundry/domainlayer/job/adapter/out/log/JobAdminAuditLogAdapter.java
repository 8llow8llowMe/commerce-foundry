package com.commercefoundry.domainlayer.job.adapter.out.log;

import com.commercefoundry.domainlayer.job.application.port.out.JobAdminAuditPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 어드민 작업 감사 로그 — 전용 로거 {@value #LOGGER_NAME} 에 INFO 한 줄 {@code key=value} 로 남긴다. 형식의 정본은 {@code security-guide.md} §11-4 다.
 *
 * <pre>
 * action=JOB_CANCEL actorMemberId=4611686018427387904 target=job:4611686018427387905 result=SUCCESS
 * </pre>
 */
@Component
public class JobAdminAuditLogAdapter implements JobAdminAuditPort {

    public static final String LOGGER_NAME = "admin.audit";

    private static final Logger AUDIT = LoggerFactory.getLogger(LOGGER_NAME);
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILURE = "FAILURE";

    @Override
    public void jobCancelSucceeded(long adminMemberId, long jobId) {
        write(JobAdminAuditAction.JOB_CANCEL, adminMemberId, job(jobId), SUCCESS);
    }

    @Override
    public void jobCancelFailed(long adminMemberId, long jobId) {
        write(JobAdminAuditAction.JOB_CANCEL, adminMemberId, job(jobId), FAILURE);
    }

    private static String job(long jobId) {
        return "job:" + jobId;
    }

    private static void write(JobAdminAuditAction action, long actorMemberId, String target, String result) {
        AUDIT.info("action={} actorMemberId={} target={} result={}", action, actorMemberId, target, result);
    }

    /** 감사 행위 이름. 로그 검색 키라 한번 정한 이름을 바꾸지 않는다. */
    enum JobAdminAuditAction {
        JOB_CANCEL
    }
}
