package com.commercefoundry.domainlayer.adminaudit.application.model;

/**
 * product-service 어드민 쓰기 감사 행위 (#163). 상수 이름이 그대로 {@code admin.audit} 줄의 {@code action} 이다 — 로그 검색 키라 한번 정한 이름을 바꾸지 않는다.
 * 컨트롤러의 {@code @AdminAudited.action()} 집합과 이 enum 의 값 집합이 같아야 한다({@code ProductAdminAuditDeclarationTest}).
 * 형식 · 대상 규칙의 정본은 {@code security-guide.md} §11-4 다.
 */
public enum ProductAdminAuditAction {

    /** 상품 기본 정보 수정. 대상 {@code product:<id>} */
    PRODUCT_UPDATE,

    /** 상품 대표·진열 카테고리 전체 교체. 대상 {@code product:<id>} */
    PRODUCT_CATEGORIES_REPLACE,

    /** 상품 soft delete. 대상 {@code product:<id>} */
    PRODUCT_DELETE,

    /** 대상 {@code category:<id>} — 만들지 못했으면 {@code -} */
    CATEGORY_CREATE,
    /** 대상 {@code category:<id>} */
    CATEGORY_UPDATE,
    /** 대상 {@code category:<id>} */
    CATEGORY_DELETE,
    /** 대상 {@code size-chart-template:<id>} — 만들지 못했으면 {@code -} */
    SIZE_CHART_TEMPLATE_CREATE,
    /** 대상 {@code size-chart-template:<id>} */
    SIZE_CHART_TEMPLATE_UPDATE,
    /** 대상 {@code size-chart-template:<id>} */
    SIZE_CHART_TEMPLATE_DELETE,
    /** 상품 사이즈표 전체 교체(없으면 생성). 대상 {@code product:<productId>} */
    PRODUCT_SIZE_CHART_REPLACE,
    MEDIA_IMAGE_UPLOAD_ISSUE,
    MEDIA_IMAGE_UPLOAD_CONFIRM
}
