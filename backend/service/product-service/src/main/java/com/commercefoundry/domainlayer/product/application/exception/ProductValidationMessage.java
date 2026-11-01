package com.commercefoundry.domainlayer.product.application.exception;

/**
 * 상품 요청 검증 메시지 — {@code "PRODUCT_1xx:사용자 메시지"} ({@code coding-conventions.md} §8-2). 컨트롤러 · DTO 는 이 상수만 참조한다.
 * 대역 끝 198 · 199 는 {@link ProductErrorCode} 의 타입 불일치 · 필수 파라미터 누락이다.
 */
public final class ProductValidationMessage {

    public static final String PAGE_MIN = "PRODUCT_101:page 는 0 이상이어야 합니다.";
    public static final String SIZE_RANGE = "PRODUCT_102:size 는 1 이상 100 이하여야 합니다.";
    public static final String IDS_REQUIRED = "PRODUCT_103:ids 는 필수입니다.";
    public static final String IDS_SIZE_MAX = "PRODUCT_104:ids 는 최대 100개입니다.";
    public static final String ID_POSITIVE = "PRODUCT_105:id 는 양수여야 합니다.";

    // 어드민 재고 조정 (PATCH /api/v1/admin/stocks/{skuId})
    public static final String STOCK_QUANTITY_MIN = "PRODUCT_106:quantity 는 0 이상이어야 합니다.";
    public static final String STOCK_EXPECTED_QUANTITY_MIN = "PRODUCT_107:expectedQuantity 는 0 이상이어야 합니다.";
    public static final String STOCK_ADJUSTMENT_REASON_REQUIRED = "PRODUCT_108:reason 은 필수입니다.";
    public static final String STOCK_ADJUSTMENT_MEMO_SIZE_MAX = "PRODUCT_109:memo 는 200자 이하여야 합니다.";
    public static final String STOCK_ADJUSTMENT_TARGET_REQUIRED = "PRODUCT_110:quantity · stockManaged 중 하나 이상을 보내야 합니다.";
    public static final String STOCK_EXPECTED_QUANTITY_REQUIRED = "PRODUCT_111:quantity 를 바꾸려면 expectedQuantity 가 필수입니다.";

    // 서비스 간 재고 선점 (/internal/v1/stock-reservations)
    public static final String STOCK_RESERVATION_ORDER_ID_REQUIRED = "PRODUCT_112:orderId 는 필수입니다.";
    public static final String STOCK_RESERVATION_ORDER_ID_POSITIVE = "PRODUCT_113:orderId 는 양수여야 합니다.";
    public static final String STOCK_RESERVATION_ITEMS_REQUIRED = "PRODUCT_114:items 는 필수입니다.";
    public static final String STOCK_RESERVATION_ITEMS_SIZE_MAX = "PRODUCT_115:items 는 최대 100개입니다.";
    public static final String STOCK_RESERVATION_ITEM_REQUIRED = "PRODUCT_116:items 의 원소는 비어 있을 수 없습니다.";
    public static final String STOCK_RESERVATION_SKU_ID_REQUIRED = "PRODUCT_117:skuId 는 필수입니다.";
    public static final String STOCK_RESERVATION_SKU_ID_POSITIVE = "PRODUCT_118:skuId 는 양수여야 합니다.";
    public static final String STOCK_RESERVATION_QUANTITY_REQUIRED = "PRODUCT_119:quantity 는 필수입니다.";
    public static final String STOCK_RESERVATION_QUANTITY_POSITIVE = "PRODUCT_120:quantity 는 1 이상이어야 합니다.";
    public static final String STOCK_RESERVATION_ID_POSITIVE = "PRODUCT_121:reservationId 는 양수여야 합니다.";
    public static final String STOCK_RESERVATION_QUANTITY_MAX = "PRODUCT_122:quantity 는 10000 이하여야 합니다.";

    // 어드민 재고 조정 경로 변수 (PATCH /api/v1/admin/stocks/{skuId})
    public static final String STOCK_SKU_ID_POSITIVE = "PRODUCT_123:skuId 는 양수여야 합니다.";

    // 어드민 재고 조회 (GET /api/v1/admin/stocks?skuIds=...). 조정 이력의 page · size 는 PAGE_MIN · SIZE_RANGE, 경로 skuId 는 STOCK_SKU_ID_POSITIVE 다
    public static final String STOCK_SKU_IDS_REQUIRED = "PRODUCT_124:skuIds 는 필수입니다.";
    public static final String STOCK_SKU_IDS_SIZE_MAX = "PRODUCT_125:skuIds 는 최대 100개입니다.";
    public static final String STOCK_SKU_IDS_POSITIVE = "PRODUCT_126:skuIds 는 양수여야 합니다.";

    // 서비스 간 재고 복원 (/internal/v1/stock-restorations). 이름 · 뜻이 같은 칸은 선점 코드를 그대로 쓴다 —
    // reservationId 양수는 STOCK_RESERVATION_ID_POSITIVE, items · skuId · quantity 는 STOCK_RESERVATION_ITEMS_* · _ITEM_REQUIRED · _SKU_ID_* · _QUANTITY_* 다
    public static final String STOCK_RESTORATION_CLAIM_ID_REQUIRED = "PRODUCT_127:claimId 는 필수입니다.";
    public static final String STOCK_RESTORATION_CLAIM_ID_POSITIVE = "PRODUCT_128:claimId 는 양수여야 합니다.";
    public static final String STOCK_RESTORATION_RESERVATION_ID_REQUIRED = "PRODUCT_129:reservationId 는 필수입니다.";
    public static final String STOCK_RESTORATION_REASON_REQUIRED = "PRODUCT_130:reason 은 필수입니다.";

    // 어드민 카테고리 생성 · 수정 (POST /api/v1/admin/categories, PUT · DELETE /api/v1/admin/categories/{categoryId})
    public static final String CATEGORY_PARENT_ID_POSITIVE = "PRODUCT_131:parentId 는 양수여야 합니다.";
    public static final String CATEGORY_NAME_REQUIRED = "PRODUCT_132:name 은 필수입니다.";
    public static final String CATEGORY_NAME_SIZE_MAX = "PRODUCT_133:name 은 앞뒤 공백을 뺀 뒤 50자 이하여야 합니다.";
    public static final String CATEGORY_DISPLAY_ORDER_REQUIRED = "PRODUCT_134:displayOrder 는 필수입니다.";
    public static final String CATEGORY_DISPLAY_ORDER_MIN = "PRODUCT_135:displayOrder 는 0 이상이어야 합니다.";
    public static final String CATEGORY_VISIBLE_REQUIRED = "PRODUCT_136:visible 은 필수입니다.";
    public static final String CATEGORY_ID_POSITIVE = "PRODUCT_137:categoryId 는 양수여야 합니다.";
    public static final String CATEGORY_NAME_INVALID_CHARACTER = "PRODUCT_138:name 에는 제어 문자 · 서식 문자(개행 · 탭 · 제로폭 공백 등)를 쓸 수 없습니다.";

    // 어드민 상품 목록 (GET /api/v1/admin/products). page · size 는 PAGE_MIN · SIZE_RANGE, categoryId 는 CATEGORY_ID_POSITIVE 다
    public static final String PRODUCT_KEYWORD_SIZE_MAX = "PRODUCT_139:keyword 는 100자 이하여야 합니다.";

    // 운영자 온보딩 상품 사실 (GET /internal/v1/onboarding-facts/product-registration)
    public static final String ONBOARDING_MEMBER_ID_POSITIVE = "PRODUCT_140:memberId 는 양수여야 합니다.";

    // 어드민 상품 등록 (POST /api/v1/admin/products, #9 E1). 1xx 가 다 차서 074~099 · 169 를 쓴다(2026-11-01 승인 — 2026-10-20 의 131~ 은 먼저 쓰였다).
    // 금액 · 판매 상태는 칸이 여럿이라 한 코드를 함께 쓰고 fieldErrors.field 경로(skus[2].additionalPrice.amount)로 구분한다.
    // categoryId · categoryIds 원소 양수는 CATEGORY_ID_POSITIVE, initialStock.quantity 0 이상은 STOCK_QUANTITY_MIN 이다
    /** 한 줄 평문 — 제어 · 서식 문자(개행 · 탭 · 제로폭 공백 · 방향 제어 등) · 짝 없는 서로게이트 · 줄 구분자 금지. */
    public static final String PLAIN_TEXT = "[^\\p{Cc}\\p{Cf}\\p{Cs}\\p{Zl}\\p{Zp}]*";
    public static final String PRODUCT_NAME_REQUIRED = "PRODUCT_074:name 은 필수입니다.";
    public static final String PRODUCT_NAME_SIZE_MAX = "PRODUCT_075:name 은 100자 이하여야 합니다.";
    public static final String PRODUCT_NAME_INVALID_CHARACTER = "PRODUCT_076:name 에는 제어 · 서식 문자를 쓸 수 없습니다.";
    public static final String PRODUCT_SUMMARY_INVALID = "PRODUCT_077:summary 는 제어 · 서식 문자 없이 200자 이하여야 합니다.";
    public static final String PRODUCT_DESCRIPTION_HTML_SIZE_MAX = "PRODUCT_078:descriptionHtml 은 200000자 이하여야 합니다.";
    public static final String PRODUCT_CATEGORY_ID_REQUIRED = "PRODUCT_079:categoryId 는 필수입니다.";
    public static final String PRODUCT_CATEGORY_IDS_SIZE_MAX = "PRODUCT_080:categoryIds 는 최대 20개입니다.";
    public static final String PRODUCT_CATEGORY_IDS_ELEMENT_REQUIRED = "PRODUCT_081:categoryIds 의 원소는 비어 있을 수 없습니다.";
    public static final String PRODUCT_SALE_STATUS_REQUIRED = "PRODUCT_082:saleStatus 는 필수입니다.";
    public static final String PRODUCT_VISIBLE_REQUIRED = "PRODUCT_083:visible 은 필수입니다.";
    public static final String PRODUCT_PRICE_REQUIRED = "PRODUCT_084:가격은 필수입니다.";
    public static final String PRODUCT_MONEY_AMOUNT_REQUIRED = "PRODUCT_085:amount 는 필수입니다.";
    public static final String PRODUCT_MONEY_AMOUNT_MIN = "PRODUCT_086:amount 는 0 이상이어야 합니다.";
    public static final String PRODUCT_MONEY_CURRENCY_REQUIRED = "PRODUCT_087:currency 는 필수입니다.";
    public static final String PRODUCT_MAX_QUANTITY_PER_ORDER_RANGE = "PRODUCT_088:maxQuantityPerOrder 는 1 이상 999 이하여야 합니다.";
    public static final String PRODUCT_EXPECTED_SHIP_DATE_REQUIRES_PREORDER = "PRODUCT_089:expectedShipDate 는 preorder 가 true 일 때만 보낼 수 있습니다.";
    public static final String PRODUCT_OPTION_GROUPS_SIZE_MAX = "PRODUCT_090:optionGroups 는 최대 5개입니다.";
    public static final String PRODUCT_OPTION_GROUP_REQUIRED = "PRODUCT_091:optionGroups 의 원소는 비어 있을 수 없습니다.";
    public static final String PRODUCT_OPTION_GROUP_NAME_INVALID = "PRODUCT_092:optionGroups.name 은 제어 · 서식 문자 없이 1~30자여야 합니다.";
    public static final String PRODUCT_OPTION_VALUES_SIZE = "PRODUCT_093:optionGroups.values 는 1~50개여야 합니다.";
    public static final String PRODUCT_OPTION_VALUE_NAME_INVALID = "PRODUCT_094:optionGroups.values 의 원소는 제어 · 서식 문자 없이 1~50자여야 합니다.";
    public static final String PRODUCT_SKUS_SIZE = "PRODUCT_095:skus 는 1~100개여야 합니다.";
    public static final String PRODUCT_SKU_REQUIRED = "PRODUCT_096:skus 의 원소는 비어 있을 수 없습니다.";
    public static final String PRODUCT_SKU_OPTION_VALUES_INVALID = "PRODUCT_097:skus.optionValues 는 최대 5개이고 원소는 제어 · 서식 문자 없이 1~50자여야 합니다.";
    public static final String PRODUCT_SKU_WEIGHT_GRAMS_POSITIVE = "PRODUCT_098:skus.weightGrams 는 1 이상이어야 합니다.";
    public static final String PRODUCT_SKU_HS_CODE_INVALID = "PRODUCT_099:skus.hsCode 는 점 · 하이픈 · 공백을 뺀 뒤 숫자 6~10자리여야 합니다.";
    public static final String PRODUCT_INITIAL_STOCK_INVALID = "PRODUCT_169:skus.initialStock 은 stockManaged · quantity 를 모두 보내야 합니다.";
    // 위 대역이 다 차서 비어 있던 037 · 038 을 잇는다(2026-11-01)
    public static final String PRODUCT_MONEY_AMOUNT_MAX = "PRODUCT_037:amount 는 100000000 이하여야 합니다.";
    public static final String PRODUCT_EXPECTED_SHIP_DATE_PAST = "PRODUCT_038:expectedShipDate 는 오늘 이후여야 합니다.";

    private ProductValidationMessage() {
    }
}
