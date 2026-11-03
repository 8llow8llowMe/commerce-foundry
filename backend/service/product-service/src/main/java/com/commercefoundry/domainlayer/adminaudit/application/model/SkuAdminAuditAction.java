package com.commercefoundry.domainlayer.adminaudit.application.model;

public enum SkuAdminAuditAction {
    PRODUCT_SKUS_REPLACE,
    /** 옵션 값 · 새 조합 SKU 추가 (#9 PR-C1). 대상 {@code product:<id>} */
    PRODUCT_SKUS_ADD,
    /** 옵션 그룹 · 값 은퇴와 그 값을 쓰는 SKU 판매 종료 (#9 PR-C2). 대상 {@code product:<id>} */
    PRODUCT_OPTIONS_RETIRE
}
