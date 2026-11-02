package com.commercefoundry.domainlayer.adminaudit.application.service.processor;

import com.commercefoundry.domainlayer.adminaudit.application.model.SupportAdminAuditAction;
import com.commercefoundry.security.audit.AdminAuditLog;
import org.springframework.stereotype.Component;

/** 값·원문·예외 메시지는 기록하지 않는다. AdminAuditLog는 트랜잭션 완료 결과도 반영한다. */
@Component
public class SupportAdminAuditProcessor {

    public void created(long actor, Long documentId, boolean succeeded) {
        write(SupportAdminAuditAction.KNOWLEDGE_CREATE, actor, documentId, succeeded);
    }

    public void edited(long actor, long documentId, boolean succeeded) {
        write(SupportAdminAuditAction.KNOWLEDGE_EDIT, actor, documentId, succeeded);
    }

    public void draftCreated(long actor, long documentId, boolean succeeded) {
        write(SupportAdminAuditAction.KNOWLEDGE_DRAFT_CREATE, actor, documentId, succeeded);
    }

    public void approved(long actor, long documentId, boolean succeeded) {
        write(SupportAdminAuditAction.KNOWLEDGE_APPROVE, actor, documentId, succeeded);
    }

    public void published(long actor, long documentId, boolean succeeded) {
        write(SupportAdminAuditAction.KNOWLEDGE_PUBLISH, actor, documentId, succeeded);
    }

    public void archived(long actor, long documentId, boolean succeeded) {
        write(SupportAdminAuditAction.KNOWLEDGE_ARCHIVE, actor, documentId, succeeded);
    }

    private void write(SupportAdminAuditAction action, long actor, Long documentId, boolean succeeded) {
        AdminAuditLog.write(action.name(), actor, documentId == null ? null : AdminAuditLog.target("knowledge", documentId), succeeded);
    }
}
