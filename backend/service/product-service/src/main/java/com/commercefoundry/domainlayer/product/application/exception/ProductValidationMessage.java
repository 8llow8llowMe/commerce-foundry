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
    public static final String CATEGORY_NAME_SIZE_MAX = "PRODUCT_133:name 은 50자 이하여야 합니다.";
    public static final String CATEGORY_DISPLAY_ORDER_REQUIRED = "PRODUCT_134:displayOrder 는 필수입니다.";
    public static final String CATEGORY_DISPLAY_ORDER_MIN = "PRODUCT_135:displayOrder 는 0 이상이어야 합니다.";
    public static final String CATEGORY_VISIBLE_REQUIRED = "PRODUCT_136:visible 은 필수입니다.";
    public static final String CATEGORY_ID_POSITIVE = "PRODUCT_137:categoryId 는 양수여야 합니다.";

    private ProductValidationMessage() {
    }
}
