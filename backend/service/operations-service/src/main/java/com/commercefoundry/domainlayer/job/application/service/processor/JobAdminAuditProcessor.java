package com.commercefoundry.domainlayer.job.application.service.processor;

import com.commercefoundry.security.audit.AdminAuditLog;
import org.springframework.stereotype.Component;

/**
 * 어드민 작업 행위 감사 ({@code security-guide.md} §11-4). DB 싱크가 없어 공통 로그 유틸을 직접 쓴다.
 * Facade 가 취소의 최종 결과가 정해진 뒤(시도별 트랜잭션 밖) 한 번 부른다. 실패 이유 · 작업 본문은 받지 않는다.
 */
@Component
public class JobAdminAuditProcessor {

    public void cancelSucceeded(long adminMemberId, long jobId) {
        AdminAuditLog.write("JOB_CANCEL", adminMemberId, AdminAuditLog.target("job", jobId), true);
    }

    public void cancelFailed(long adminMemberId, long jobId) {
        AdminAuditLog.write("JOB_CANCEL", adminMemberId, AdminAuditLog.target("job", jobId), false);
    }
}
