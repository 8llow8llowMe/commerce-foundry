package com.commercefoundry.domainlayer.order.application.exception;

/**
 * 주문 요청 검증 메시지 — {@code "ORDER_1xx:사용자 메시지"} ({@code coding-conventions.md} §8-2). 컨트롤러 · DTO 는 이 상수만 참조한다.
 * 대역 끝 198 · 199 는 {@link OrderErrorCode} 의 타입 불일치 · 필수 파라미터 누락이다 — {@code Idempotency-Key} 헤더도 그 둘로 답한다.
 */
public final class OrderValidationMessage {

    public static final String ITEMS_REQUIRED = "ORDER_101:items 는 1개 이상이어야 합니다.";
    public static final String ITEMS_SIZE_MAX = "ORDER_102:items 는 최대 50개입니다.";
    public static final String ITEM_REQUIRED = "ORDER_103:items 의 항목은 비어 있을 수 없습니다.";
    public static final String SKU_ID_REQUIRED = "ORDER_104:skuId 는 필수입니다.";
    public static final String SKU_ID_POSITIVE = "ORDER_105:skuId 는 양수여야 합니다.";
    public static final String QUANTITY_REQUIRED = "ORDER_106:quantity 는 필수입니다.";
    public static final String QUANTITY_RANGE = "ORDER_107:quantity 는 1 이상 99 이하여야 합니다.";
    public static final String CUSTOM_OPTIONS_SIZE_MAX = "ORDER_108:customOptions 는 최대 20개입니다.";
    public static final String CUSTOM_OPTION_REQUIRED = "ORDER_109:customOptions 의 항목은 비어 있을 수 없습니다.";
    public static final String CUSTOM_OPTION_KEY_DUPLICATED = "ORDER_110:customOptions 의 fieldKey 가 중복되었습니다.";
    public static final String FIELD_KEY_REQUIRED = "ORDER_111:fieldKey 는 필수입니다.";
    public static final String FIELD_KEY_PATTERN = "ORDER_112:fieldKey 는 영문 소문자로 시작하는 영문 · 숫자 · 밑줄 50자 이하여야 합니다.";
    public static final String VALUE_REQUIRED = "ORDER_113:value 는 필수입니다.";
    public static final String VALUE_LENGTH_MAX = "ORDER_114:value 는 500자 이하여야 합니다.";
    public static final String DESTINATION_REQUIRED = "ORDER_116:배송지는 필수입니다.";
    public static final String RECIPIENT_NAME_REQUIRED = "ORDER_117:recipientName 은 필수입니다.";
    public static final String RECIPIENT_NAME_LENGTH_MAX = "ORDER_118:recipientName 은 50자 이하여야 합니다.";
    public static final String RECIPIENT_PHONE_REQUIRED = "ORDER_119:recipientPhone 은 필수입니다.";
    public static final String RECIPIENT_PHONE_FORMAT = "ORDER_120:recipientPhone 은 숫자 · + · - · ( ) · 공백으로 된 7~20자여야 합니다.";
    public static final String COUNTRY_CODE_REQUIRED = "ORDER_121:countryCode 는 필수입니다.";
    public static final String COUNTRY_CODE_FORMAT = "ORDER_122:countryCode 는 ISO 3166-1 alpha-2 대문자 2자여야 합니다.";
    public static final String POSTAL_CODE_REQUIRED = "ORDER_123:국내(KR) 배송지는 postalCode 가 필수입니다.";
    public static final String POSTAL_CODE_FORMAT = "ORDER_124:postalCode 형식이 올바르지 않습니다. 국내(KR)는 숫자 5자리, 해외는 영문 · 숫자 · 공백 · 하이픈 20자 이하입니다.";
    public static final String ADDRESS_LINE1_REQUIRED = "ORDER_125:addressLine1 은 필수입니다.";
    public static final String ADDRESS_LINE1_LENGTH_MAX = "ORDER_126:addressLine1 은 200자 이하여야 합니다.";
    public static final String ADDRESS_LINE2_LENGTH_MAX = "ORDER_127:addressLine2 는 200자 이하여야 합니다.";
    public static final String DELIVERY_MEMO_LENGTH_MAX = "ORDER_128:deliveryMemo 는 200자 이하여야 합니다.";
    public static final String EXPECTED_TOTAL_AMOUNT_REQUIRED = "ORDER_129:expectedTotalAmount 를 보낸다면 amount 는 필수입니다.";
    public static final String EXPECTED_TOTAL_AMOUNT_NON_NEGATIVE = "ORDER_130:expectedTotalAmount.amount 는 0 이상이어야 합니다.";
    public static final String EXPECTED_TOTAL_CURRENCY_REQUIRED = "ORDER_131:expectedTotalAmount 를 보낸다면 currency 는 필수입니다.";
    /** 맞춤 제작 필수 항목이 요청에 없다. 입력값은 싣지 않는다. */
    public static final String CUSTOM_ORDER_FIELD_REQUIRED = "ORDER_132:필수 입력입니다.";
    /** 상품 항목 정의에 없는 fieldKey. */
    public static final String CUSTOM_ORDER_FIELD_UNKNOWN = "ORDER_133:정의되지 않은 입력 항목입니다.";
    /** NUMBER 항목인데 값이 숫자가 아니다. */
    public static final String CUSTOM_ORDER_NUMBER_FORMAT = "ORDER_134:숫자를 입력해 주세요.";
    /** NUMBER 최소 · 최대를 벗어났다. */
    public static final String CUSTOM_ORDER_NUMBER_RANGE = "ORDER_135:허용 범위를 벗어났습니다.";
    /** NUMBER 증분에 맞지 않는다. */
    public static final String CUSTOM_ORDER_NUMBER_STEP = "ORDER_136:증분 단위에 맞지 않습니다.";
    /** TEXT 최대 글자 수를 넘었다. */
    public static final String CUSTOM_ORDER_TEXT_LENGTH = "ORDER_137:최대 글자 수를 넘었습니다.";

    private OrderValidationMessage() {
    }
}
