package com.commercefoundry.domainlayer.adminaudit.application.model;

public enum SkuAdminAuditAction {
    PRODUCT_SKUS_REPLACE,
    /** 옵션 값 · 새 조합 SKU 추가 (#9 PR-C1). 대상 {@code product:<id>} */
    PRODUCT_SKUS_ADD
}
