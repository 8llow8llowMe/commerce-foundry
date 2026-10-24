package com.commercefoundry.domainlayer.cart.application.exception;

/**
 * 장바구니 요청 검증 메시지 — {@code "CART_1xx:사용자 메시지"} ({@code coding-conventions.md} §8-2). 컨트롤러 · DTO 는 이 상수만 참조한다.
 * 대역 끝 198 · 199 는 {@link CartErrorCode} 의 타입 불일치 · 필수 파라미터 누락이다.
 */
public final class CartValidationMessage {

    public static final String SKU_ID_REQUIRED = "CART_101:skuId 는 필수입니다.";
    public static final String SKU_ID_POSITIVE = "CART_102:skuId 는 양수여야 합니다.";
    public static final String QUANTITY_REQUIRED = "CART_103:quantity 는 필수입니다.";
    public static final String QUANTITY_RANGE = "CART_104:quantity 는 1 이상 99 이하여야 합니다.";
    public static final String CUSTOM_OPTIONS_SIZE_MAX = "CART_105:customOptions 는 최대 20개입니다.";
    public static final String CUSTOM_OPTION_REQUIRED = "CART_106:customOptions 의 항목은 비어 있을 수 없습니다.";
    public static final String CUSTOM_OPTION_KEY_DUPLICATED = "CART_107:customOptions 의 fieldKey 가 중복되었습니다.";
    public static final String FIELD_KEY_REQUIRED = "CART_108:fieldKey 는 필수입니다.";
    public static final String FIELD_KEY_PATTERN = "CART_109:fieldKey 는 영문 소문자로 시작하는 영문 · 숫자 · 밑줄 50자 이하여야 합니다.";
    public static final String VALUE_REQUIRED = "CART_110:value 는 필수입니다.";
    public static final String VALUE_LENGTH_MAX = "CART_111:value 는 500자 이하여야 합니다.";
    public static final String ITEMS_REQUIRED = "CART_112:items 는 필수입니다.";
    public static final String ITEMS_SIZE_MAX = "CART_113:items 는 최대 50개입니다.";
    public static final String ITEM_REQUIRED = "CART_114:items 의 항목은 비어 있을 수 없습니다.";
    public static final String VALUE_INVALID_CHARACTER = "CART_115:value 에는 제어 문자(NUL · 개행 · 탭 등)나 깨진 문자를 쓸 수 없습니다.";

    private CartValidationMessage() {
    }
}
