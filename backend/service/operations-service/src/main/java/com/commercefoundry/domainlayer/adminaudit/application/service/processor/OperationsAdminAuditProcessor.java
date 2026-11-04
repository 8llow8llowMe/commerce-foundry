package com.commercefoundry.domainlayer.adminaudit.application.service.processor;

import com.commercefoundry.domainlayer.adminaudit.application.model.OperationsAdminAuditAction;
import com.commercefoundry.security.audit.AdminAuditLog;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Protects synchronous profile/legal HTTP audits, not deferred callbacks in an external transaction. */
@Component
public class OperationsAdminAuditProcessor {
    private final Logger diagnostic;

    public OperationsAdminAuditProcessor() {
        this(LoggerFactory.getLogger(OperationsAdminAuditProcessor.class));
    }

    OperationsAdminAuditProcessor(Logger diagnostic) {
        this.diagnostic = diagnostic;
    }

    public void profileSaved(Long actorMemberId, boolean succeeded) {
        write(OperationsAdminAuditAction.STORE_PROFILE_UPDATE, actorMemberId, "store-profile:1", succeeded);
    }

    public void legalDraftSaved(long actorMemberId, String type, String locale, boolean succeeded) {
        legalDocumentFinished(OperationsAdminAuditAction.LEGAL_DOCUMENT_DRAFT_SAVE, actorMemberId, type, locale, succeeded);
    }

    public void legalPublished(long actorMemberId, String type, String locale, boolean succeeded) {
        legalDocumentFinished(OperationsAdminAuditAction.LEGAL_DOCUMENT_PUBLISH, actorMemberId, type, locale, succeeded);
    }

    private void legalDocumentFinished(OperationsAdminAuditAction action, long actorMemberId, String type, String locale, boolean succeeded) {
        // Only fixed codes may reach audit metadata; never pass path input or document text.
        String target = ("TERMS".equals(type) || "PRIVACY".equals(type)) && ("ko".equals(locale) || "en".equals(locale))
            ? AdminAuditLog.target("legal-document", type.toLowerCase(Locale.ROOT) + "-" + locale) : "-";
        write(action, actorMemberId, target, succeeded);
    }

    private void write(OperationsAdminAuditAction action, Long actorMemberId, String target, boolean succeeded) {
        try {
            AdminAuditLog.write(action.name(), actorMemberId, target, succeeded);
        } catch (RuntimeException exception) {
            try {
                // Diagnose type/action/actor only; never message, cause, stack, target or body.
                diagnostic.warn("Admin audit unavailable action={} actorMemberId={} exceptionType={}",
                    action.name(), actorMemberId, exception.getClass().getName());
            } catch (RuntimeException ignored) {
                // A diagnostic failure must not change the completed business result or original exception.
            }
        }
    }
}
