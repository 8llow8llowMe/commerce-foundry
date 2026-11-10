package com.commercefoundry.domainlayer.adminaudit.application.model;

/**
 * product-service 어드민 쓰기 감사 행위 (#163). 상수 이름이 그대로 {@code admin.audit} 줄의 {@code action} 이다 — 로그 검색 키라 한번 정한 이름을 바꾸지 않는다.
 * 컨트롤러의 {@code @AdminAudited.action()} 집합과 이 enum 의 값 집합이 같아야 한다({@code ProductAdminAuditDeclarationTest}).
 * 형식 · 대상 규칙의 정본은 {@code security-guide.md} §11-4 다.
 */
public enum ProductAdminAuditAction {

    /** 상품 등록. 대상 {@code product:<id>} — 만들지 못했으면 {@code -} */
    PRODUCT_CREATE,

    /** 상품 이미지 업로드 자리 발급. 대상 {@code product-image-upload:<token>} — 발급하지 못했으면 {@code -} */
    PRODUCT_IMAGE_UPLOAD_ISSUE,

    /** 상품 기본 정보 수정. 대상 {@code product:<id>} */
    PRODUCT_UPDATE,

    PRODUCT_OPTIONS_REPLACE,

    /** 맞춤 제작 설정 전체 교체 (#9 PR-B). 대상 {@code product:<id>} */
    PRODUCT_CUSTOM_ORDER_REPLACE,

    PRODUCT_IMAGES_REPLACE,

    /** 상품 기본 판매가 · 정가 전체 교체. 대상 {@code product:<id>} */
    PRODUCT_PRICING_REPLACE,

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
    /** 초안 생성 요청. 대상 product:<productId> */
    SIZE_CHART_DRAFT_CREATE,
    /** 초안 결과 새로고침. 대상 size-chart-draft:<draftId> */
    SIZE_CHART_DRAFT_REFRESH,
    /** 검토한 초안 적용. 대상 size-chart-draft:<draftId> */
    SIZE_CHART_DRAFT_APPLY,
    MEDIA_IMAGE_UPLOAD_ISSUE,
    MEDIA_IMAGE_UPLOAD_CONFIRM,

    /** 메인 전시 섹션 생성 (#163 PR-C). 대상 {@code display-section:<id>} — 만들지 못했으면 {@code -} */
    DISPLAY_SECTION_CREATE,
    /** 메인 전시 섹션 전체 교체. 대상 {@code display-section:<id>} */
    DISPLAY_SECTION_UPDATE,
    /** 메인 전시 섹션 삭제. 대상 {@code display-section:<id>} */
    DISPLAY_SECTION_DELETE,
    /** 메인 전시 섹션 순서 변경. 고정 대상 {@code display-section:order} — 아이디 목록은 싣지 않는다 */
    DISPLAY_SECTION_REORDER,
    /** 룩 생성 (#163 PR-C). 대상 {@code look:<id>} — 만들지 못했으면 {@code -} */
    LOOK_CREATE,
    /** 룩 전체 교체. 대상 {@code look:<id>} */
    LOOK_UPDATE,
    /** 룩 삭제. 대상 {@code look:<id>} */
    LOOK_DELETE,
    /** 룩 순서 변경. 고정 대상 {@code look:order} — 아이디 목록은 싣지 않는다 */
    LOOK_REORDER,
    /** 재고 조정 (#163 PR-C′). 대상 {@code sku:<skuId>} — 수량 · 관리 여부 · 사유 · 메모는 싣지 않는다(정본은 조정 이력 표) */
    STOCK_ADJUST,
    /** SKU 원가 이력 추가 (#163 PR-C′). 대상 {@code sku:<skuId>} — 금액 · 통화 · 유형 · 적용 시각 · 사유는 싣지 않는다(정본은 원가 이력 표) */
    SKU_COST_CREATE,
    /** AI 상품 초안 생성 (#163 PR-C′). 대상 {@code product-draft:<id>} — 만들지 못했으면 {@code -} */
    PRODUCT_DRAFT_CREATE,
    /** AI 상품 초안 거절 (#163 PR-C′). 대상 {@code product-draft:<id>} — 이미 거절한 초안의 재전송(그대로 200)도 {@code SUCCESS} */
    PRODUCT_DRAFT_REJECT
}
