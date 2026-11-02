package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.model.StockAlertAuditAction;
import com.commercefoundry.security.audit.AdminAuditLog;
import org.springframework.stereotype.Component;

/** 정책 쓰기의 값·재고 수량은 기록하지 않고, 커밋 결과와 JWT 운영자·SKU 식별자만 남긴다. */
@Component
public class StockAlertAuditProcessor {

    public void policySet(long actorMemberId, long skuId, boolean succeeded) {
        AdminAuditLog.write(StockAlertAuditAction.STOCK_ALERT_POLICY_SET.name(), actorMemberId, AdminAuditLog.target("sku", skuId), succeeded);
    }

    public void policyCleared(long actorMemberId, long skuId, boolean succeeded) {
        AdminAuditLog.write(StockAlertAuditAction.STOCK_ALERT_POLICY_CLEAR.name(), actorMemberId, AdminAuditLog.target("sku", skuId), succeeded);
    }
}
