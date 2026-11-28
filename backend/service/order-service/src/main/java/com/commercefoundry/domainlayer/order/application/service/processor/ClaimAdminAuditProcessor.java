package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.model.ClaimAdminFilter;
import com.commercefoundry.security.audit.AdminAuditLog;
import org.springframework.stereotype.Component;

/** 어드민 클레임 조회 (#17). */
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

    /** 다시 시도 (#17 A2b) — 거절(409 · 503 · 404)도 false 로 남긴다. 성공은 선점이 커밋됐다는 뜻이고 환불 · 복원 결과는 상세 상태로 본다. */
    public void retried(long adminId, long claimId, boolean success) {
        AdminAuditLog.write("CLAIM_RETRY", adminId, AdminAuditLog.target("claim", claimId), success);
    }

    /**
     * 수동 환불 확정 (#17 A3) — 실패도 false. 사유 원문은 감사 줄에 넣지 않는다. 사유는 클레임 결정 칸에 <b>마지막 결정만</b> 남고(뒤의 종결이 덮는다),
     * 확정이 주문 상태를 바꾼 경우(취소 완료 · 부분 취소 정리의 결제 완료 · 반품 정리)에만 주문 이력에도 남는다. 결정 누적 원장은 후속 #694 다.
     */
    public void refundConfirmed(long adminId, long claimId, boolean success) {
        AdminAuditLog.write("CLAIM_REFUND_CONFIRM", adminId, AdminAuditLog.target("claim", claimId), success);
    }

    /** 복원 생략 종결 (#17 A3) — 실패도 false. */
    public void completed(long adminId, long claimId, boolean success) {
        AdminAuditLog.write("CLAIM_COMPLETE", adminId, AdminAuditLog.target("claim", claimId), success);
    }

    /**
     * 반품 승인 (#17 B2c-2) — 실패(409 · 503 · 404)도 false. 성공은 승인이 커밋됐다는 뜻이고(그 직후 남긴다) 환불 · 복원 결과는 상세 상태로 본다.
     * 귀책 · 차감액 · 금액은 감사 줄에 넣지 않는다 — 클레임 승인 칸(귀책 · 재고 되돌림 · 차감액 · 승인자 · 시각)에 남는다.
     */
    public void returnApproved(long adminId, long claimId, boolean success) {
        AdminAuditLog.write("CLAIM_RETURN_APPROVE", adminId, AdminAuditLog.target("claim", claimId), success);
    }

    /** 반품 거절 (#17 B2c-2) — 실패도 false. 사유 원문은 감사 줄에 넣지 않는다(클레임 결정 칸과, 주문이 바뀌었으면 주문 이력에 남는다). */
    public void returnRejected(long adminId, long claimId, boolean success) {
        AdminAuditLog.write("CLAIM_RETURN_REJECT", adminId, AdminAuditLog.target("claim", claimId), success);
    }
}
