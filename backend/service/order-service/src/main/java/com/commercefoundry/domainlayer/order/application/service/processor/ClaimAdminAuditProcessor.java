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
     * 확정이 주문을 취소 완료로 바꾼 경우에만 주문 이력에도 남는다. 결정 누적 원장은 후속 #694 다.
     */
    public void refundConfirmed(long adminId, long claimId, boolean success) {
        AdminAuditLog.write("CLAIM_REFUND_CONFIRM", adminId, AdminAuditLog.target("claim", claimId), success);
    }

    /** 복원 생략 종결 (#17 A3) — 실패도 false. */
    public void completed(long adminId, long claimId, boolean success) {
        AdminAuditLog.write("CLAIM_COMPLETE", adminId, AdminAuditLog.target("claim", claimId), success);
    }
}
