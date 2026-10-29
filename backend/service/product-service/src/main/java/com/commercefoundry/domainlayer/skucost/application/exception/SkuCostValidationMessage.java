package com.commercefoundry.domainlayer.skucost.application.exception;

/**
 * SKU 원가 요청 검증 메시지 — {@code "PRODUCT_xxx:사용자 메시지"} ({@code coding-conventions.md} §8-2). 컨트롤러 · DTO 는 이 상수만 참조한다.
 *
 * <p>product-service 의 {@code 1xx} 대역은 101~197 이 모두 찼다(상품 101~139 · 전시 140~168 · 사이즈표 170~197, 169 한 칸만 빈다).
 * 원가 필드 검증은 대역을 이어 {@code 200~208} 을 쓴다. 대역 끝 198 · 199 는 그대로 {@code ProductErrorCode} 의 타입 불일치 · 필수 파라미터 누락이다.
 * 목록 page · size 는 서비스 공용 {@code PRODUCT_101} · {@code PRODUCT_102}, 경로 skuId 양수는 재고 조정과 같은 {@code PRODUCT_123} 이다 — 이름 · 뜻이 같아 코드를 그대로 쓴다.
 */
public final class SkuCostValidationMessage {

    public static final String PAGE_MIN = "PRODUCT_101:page 는 0 이상이어야 합니다.";
    public static final String SIZE_RANGE = "PRODUCT_102:size 는 1 이상 100 이하여야 합니다.";
    public static final String SKU_ID_POSITIVE = "PRODUCT_123:skuId 는 양수여야 합니다.";

    public static final String COST_TYPE_REQUIRED = "PRODUCT_200:costType 은 필수입니다.";
    public static final String AMOUNT_REQUIRED = "PRODUCT_201:amount 는 필수입니다.";
    public static final String AMOUNT_VALUE_REQUIRED = "PRODUCT_202:amount.amount 는 필수입니다.";
    public static final String AMOUNT_VALUE_NON_NEGATIVE = "PRODUCT_203:amount.amount 는 0 이상이어야 합니다.";
    public static final String AMOUNT_CURRENCY_REQUIRED = "PRODUCT_204:amount.currency 는 필수입니다.";
    public static final String REASON_REQUIRED = "PRODUCT_205:reason 은 필수입니다.";
    public static final String REASON_SIZE_MAX = "PRODUCT_206:reason 은 앞뒤 공백을 뺀 뒤 200자 이하여야 합니다.";
    public static final String REASON_INVALID_CHARACTER = "PRODUCT_207:reason 에는 제어 문자 · 서식 문자(개행 · 탭 · 제로폭 공백 등)를 쓸 수 없습니다.";
    public static final String EFFECTIVE_FROM_MIN = "PRODUCT_208:effectiveFrom 은 1970-01-01T00:00:00Z 이후여야 합니다.";

    private SkuCostValidationMessage() {
    }
}
