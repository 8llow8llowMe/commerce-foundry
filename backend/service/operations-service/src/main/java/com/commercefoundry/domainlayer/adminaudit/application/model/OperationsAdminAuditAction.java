package com.commercefoundry.domainlayer.adminaudit.application.model;

/** Profile/legal/footer/navigation subset only; JOB/CAMPAIGN actions are not included. */
public enum OperationsAdminAuditAction {
    STORE_PROFILE_UPDATE,
    LEGAL_DOCUMENT_DRAFT_SAVE,
    LEGAL_DOCUMENT_PUBLISH,
    STORE_FOOTER_DRAFT_SAVE,
    STORE_FOOTER_PUBLISH,
    STORE_NAVIGATION_DRAFT_SAVE,
    STORE_NAVIGATION_PUBLISH,
    STORE_BRAND_DRAFT_SAVE,
    STORE_BRAND_PUBLISH,
    STORE_BRAND_UPLOAD,
    LEGAL_DRAFT_GENERATION_REGISTER,
    LEGAL_DRAFT_GENERATION_REVIEW,
    LEGAL_DRAFT_GENERATION_CANCEL
}
