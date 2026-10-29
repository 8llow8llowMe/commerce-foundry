package com.commercefoundry.domainlayer.skucost.application.exception;

/**
 * SKU 원가 요청 DTO 의 필드별 검증 메시지 ({@code coding-conventions.md} §8-2). 접두어는 컨텍스트 단위 {@code SKU_COST_} 이고 101~ 을 쓴다
 * ({@code PRODUCT_1xx} 대역이 다 찼다 — {@link SkuCostErrorCode}). 봉투는 서비스 공용 검증 처리({@code ProductExceptionHandler})가 만든다 —
 * 메시지 앞의 코드를 읽으므로 컨텍스트를 몰라도 된다. 폴백(역직렬화 실패)은 서비스 공용 {@code PRODUCT_100} 이다. 컨트롤러 · DTO 는 이 상수만 참조한다.
 */
public final class SkuCostValidationMessage {

    // 어드민 원가 추가 (POST /api/v1/admin/skus/{skuId}/costs)
    public static final String COST_TYPE_REQUIRED = "SKU_COST_101:costType 은 필수입니다.";
    public static final String AMOUNT_REQUIRED = "SKU_COST_102:amount 는 필수입니다.";
    public static final String AMOUNT_VALUE_REQUIRED = "SKU_COST_103:amount.amount 는 필수입니다.";
    public static final String AMOUNT_VALUE_NON_NEGATIVE = "SKU_COST_104:amount.amount 는 0 이상이어야 합니다.";
    public static final String AMOUNT_CURRENCY_REQUIRED = "SKU_COST_105:amount.currency 는 필수입니다.";
    public static final String REASON_REQUIRED = "SKU_COST_106:reason 은 필수입니다.";
    public static final String REASON_SIZE_MAX = "SKU_COST_107:reason 은 앞뒤 공백을 뺀 뒤 200자 이하여야 합니다.";
    public static final String REASON_INVALID_CHARACTER = "SKU_COST_108:reason 에는 제어 문자 · 서식 문자(개행 · 탭 · 제로폭 공백 등)를 쓸 수 없습니다.";
    public static final String EFFECTIVE_FROM_MIN = "SKU_COST_109:effectiveFrom 은 1970-01-01T00:00:00Z 이후여야 합니다.";

    // 경로 변수 (/api/v1/admin/skus/{skuId}/costs/**) — 룩의 lookId(LOOK_118)처럼 컨텍스트 자기 코드다
    public static final String SKU_ID_POSITIVE = "SKU_COST_110:skuId 는 양수여야 합니다.";

    // 원가 이력 목록 (GET /api/v1/admin/skus/{skuId}/costs/histories) — page · size 는 서비스 공용 PRODUCT_101 · 102 다 (api-design-guide.md §4, 선례: LookValidationMessage)
    public static final String PAGE_MIN = "PRODUCT_101:page 는 0 이상이어야 합니다.";
    public static final String SIZE_RANGE = "PRODUCT_102:size 는 1 이상 100 이하여야 합니다.";

    private SkuCostValidationMessage() {
    }
}
