package com.commercefoundry.domainlayer.campaign.application.service.processor;

import com.commercefoundry.security.audit.AdminAuditLog;
import org.springframework.stereotype.Component;

@Component
public class CampaignAdminAuditProcessor {
    public void previewFinished(Long actorMemberId, boolean succeeded) {
        // 수신자 ID 목록이나 원격 응답은 감사 로그에도 남기지 않는다.
        AdminAuditLog.write("CAMPAIGN_RECIPIENT_PREVIEW", actorMemberId, AdminAuditLog.target("campaign-preview", "recipients"), succeeded);
    }
}
