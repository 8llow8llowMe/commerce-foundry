package com.commercefoundry.domainlayer.storeprofile.application.service.processor;

import com.commercefoundry.security.audit.AdminAuditLog;
import org.springframework.stereotype.Component;

@Component
public class StoreProfileAuditProcessor {

    public void saved(Long actorMemberId, boolean succeeded) {
        AdminAuditLog.write("STORE_PROFILE_UPDATE", actorMemberId, AdminAuditLog.target("store-profile", 1L), succeeded);
    }
}
