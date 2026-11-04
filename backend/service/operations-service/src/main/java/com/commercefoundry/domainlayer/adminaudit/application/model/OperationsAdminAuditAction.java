package com.commercefoundry.domainlayer.adminaudit.application.model;

/** Migrated profile/legal subset only; JOB/CAMPAIGN actions are not included. */
public enum OperationsAdminAuditAction {
    STORE_PROFILE_UPDATE,
    LEGAL_DOCUMENT_DRAFT_SAVE,
    LEGAL_DOCUMENT_PUBLISH
}
