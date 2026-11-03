package com.commercefoundry.domainlayer.adminaudit.application.service.processor;

import com.commercefoundry.domainlayer.adminaudit.application.model.SkuAdminAuditAction;
import com.commercefoundry.security.audit.AdminAuditLog;
import org.springframework.stereotype.Component;

@Component
public class SkuAdminAuditProcessor {
    public void replaced(long actorMemberId, long productId, boolean succeeded) {
        AdminAuditLog.write(SkuAdminAuditAction.PRODUCT_SKUS_REPLACE.name(), actorMemberId, AdminAuditLog.target("product", productId), succeeded);
    }

    public void added(long actorMemberId, long productId, boolean succeeded) {
        AdminAuditLog.write(SkuAdminAuditAction.PRODUCT_SKUS_ADD.name(), actorMemberId, AdminAuditLog.target("product", productId), succeeded);
    }

    public void optionsRetired(long actorMemberId, long productId, boolean succeeded) {
        AdminAuditLog.write(SkuAdminAuditAction.PRODUCT_OPTIONS_RETIRE.name(), actorMemberId, AdminAuditLog.target("product", productId), succeeded);
    }
}
