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

    private ProductValidationMessage() {
    }
}
