package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.model.ClaimAdminFilter;
import com.commercefoundry.security.audit.AdminAuditLog;
import org.springframework.stereotype.Component;

/**
 * 어드민 클레임 조회 · 수습 감사 (#17, {@code security-guide.md} §11-4). 수습(재시도 · 거절 · 수동 환불 확정 · 복원 생략 종결)은 실패도 {@code FAILURE} 로 남긴다.
 * 처리 사유는 감사 줄에 싣지 않는다 — 정본은 클레임의 결정 칸과 주문 상태 이력이다.
 */
@Component
public class ClaimAdminAuditProcessor {
    public void searched(long adminId, ClaimAdminFilter filter) {
        String target = "claims?orderId=" + (filter.orderId() == null ? "ALL" : filter.orderId())
            + "&type=" + (filter.type() == null ? "ALL" : filter.type().name())
            + "&status=" + (filter.status() == null ? "ALL" : filter.status().name())
            + "&page=" + filter.page() + "&size=" + filter.size();
        AdminAuditLog.write("CLAIM_SEARCH", adminId, target, true);
    }

    public void viewed(long adminId, long claimId) {
        AdminAuditLog.write("CLAIM_VIEW", adminId, AdminAuditLog.target("claim", claimId), true);
    }

    public void retried(long adminId, long claimId, boolean succeeded) {
        AdminAuditLog.write("CLAIM_RETRY", adminId, AdminAuditLog.target("claim", claimId), succeeded);
    }

    public void rejected(long adminId, long claimId, boolean succeeded) {
        AdminAuditLog.write("CLAIM_REJECT", adminId, AdminAuditLog.target("claim", claimId), succeeded);
    }

    public void refundConfirmed(long adminId, long claimId, boolean succeeded) {
        AdminAuditLog.write("CLAIM_REFUND_CONFIRM", adminId, AdminAuditLog.target("claim", claimId), succeeded);
    }

    public void completed(long adminId, long claimId, boolean succeeded) {
        AdminAuditLog.write("CLAIM_COMPLETE", adminId, AdminAuditLog.target("claim", claimId), succeeded);
    }
}
