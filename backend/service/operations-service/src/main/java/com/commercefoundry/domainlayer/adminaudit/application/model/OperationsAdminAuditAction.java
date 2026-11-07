package com.commercefoundry.domainlayer.adminaudit.application.model;

/** Profile/legal/footer subset only; JOB/CAMPAIGN actions are not included. */
public enum OperationsAdminAuditAction {
    STORE_PROFILE_UPDATE,
    LEGAL_DOCUMENT_DRAFT_SAVE,
    LEGAL_DOCUMENT_PUBLISH,
    STORE_FOOTER_DRAFT_SAVE,
    STORE_FOOTER_PUBLISH
}
