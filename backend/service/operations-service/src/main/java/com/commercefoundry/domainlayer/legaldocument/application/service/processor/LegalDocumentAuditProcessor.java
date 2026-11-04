package com.commercefoundry.domainlayer.legaldocument.application.service.processor;

import com.commercefoundry.security.audit.AdminAuditLog;
import org.springframework.stereotype.Component;

@Component
public class LegalDocumentAuditProcessor {
    public void finished(String action, long actorMemberId, String type, String locale, boolean succeeded) {
        // A fixed allow-list keeps document input and body out of audit metadata.
        String target = ("TERMS".equals(type) || "PRIVACY".equals(type)) && ("ko".equals(locale) || "en".equals(locale))
            ? AdminAuditLog.target("legal-document", type.toLowerCase(java.util.Locale.ROOT) + "-" + locale) : "-";
        AdminAuditLog.write(action, actorMemberId, target, succeeded);
    }
}
