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
}
