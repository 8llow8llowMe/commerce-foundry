package com.commercefoundry.domainlayer.adminaudit.application.service.processor;

import com.commercefoundry.domainlayer.adminaudit.application.model.OperationsAdminAuditAction;
import com.commercefoundry.security.audit.AdminAuditLog;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Protects synchronous profile/legal/footer/navigation HTTP audits, not deferred callbacks in an external transaction. */
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

    public void footerDraftSaved(long actorMemberId, boolean succeeded) {
        write(OperationsAdminAuditAction.STORE_FOOTER_DRAFT_SAVE, actorMemberId, "store-footer:1", succeeded);
    }

    public void footerPublished(long actorMemberId, boolean succeeded) {
        write(OperationsAdminAuditAction.STORE_FOOTER_PUBLISH, actorMemberId, "store-footer:1", succeeded);
    }

    public void navigationDraftSaved(long actorMemberId, boolean succeeded) {
        write(OperationsAdminAuditAction.STORE_NAVIGATION_DRAFT_SAVE, actorMemberId, "store-navigation:1", succeeded);
    }

    public void navigationPublished(long actorMemberId, boolean succeeded) {
        write(OperationsAdminAuditAction.STORE_NAVIGATION_PUBLISH, actorMemberId, "store-navigation:1", succeeded);
    }

    public void brandDraftSaved(long actorMemberId, boolean succeeded) {
        write(OperationsAdminAuditAction.STORE_BRAND_DRAFT_SAVE, actorMemberId, "store-brand:1", succeeded);
    }

    public void brandPublished(long actorMemberId, boolean succeeded) {
        write(OperationsAdminAuditAction.STORE_BRAND_PUBLISH, actorMemberId, "store-brand:1", succeeded);
    }

    public void brandUploaded(long actorMemberId, boolean succeeded) {
        write(OperationsAdminAuditAction.STORE_BRAND_UPLOAD, actorMemberId, "store-brand:1", succeeded);
    }

    /** 법률 문서 AI 초안 생성. 행위 · 행위자 · 작업 식별자 · 성공 여부만 남기고 요청 · 본문은 남기지 않는다. 작업 식별자를 모르면 대상은 비운다. */
    public void legalDraftGenerationRegistered(long actorMemberId, Long jobId, boolean succeeded) {
        write(OperationsAdminAuditAction.LEGAL_DRAFT_GENERATION_REGISTER, actorMemberId, legalDraftGenerationTarget(jobId), succeeded);
    }

    public void legalDraftGenerationReviewed(long actorMemberId, long jobId, boolean succeeded) {
        write(OperationsAdminAuditAction.LEGAL_DRAFT_GENERATION_REVIEW, actorMemberId, legalDraftGenerationTarget(jobId), succeeded);
    }

    public void legalDraftGenerationCancelled(long actorMemberId, long jobId, boolean succeeded) {
        write(OperationsAdminAuditAction.LEGAL_DRAFT_GENERATION_CANCEL, actorMemberId, legalDraftGenerationTarget(jobId), succeeded);
    }

    private static String legalDraftGenerationTarget(Long jobId) {
        return jobId == null ? null : AdminAuditLog.target("legal-draft-generation", jobId);
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
