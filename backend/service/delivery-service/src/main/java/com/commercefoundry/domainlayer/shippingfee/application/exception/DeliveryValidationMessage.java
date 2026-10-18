package com.commercefoundry.domainlayer.shippingfee.application.exception;

/**
 * 배송 요청 검증 메시지 — {@code "DELIVERY_1xx:사용자 메시지"} ({@code coding-conventions.md} §8-2). DTO 는 이 상수만 참조한다.
 * 대역 끝 198 · 199 는 {@link DeliveryErrorCode} 의 타입 불일치 · 필수 파라미터 누락이다.
 *
 * <p>금액 · 우편번호는 여러 필드가 같은 규칙을 쓰므로 코드를 같이 쓴다 — 어느 필드인지는 {@code fieldErrors[].field}({@code baseFee.amount} 등)가 밝힌다.
 */
public final class DeliveryValidationMessage {

    public static final String ORDER_AMOUNT_REQUIRED = "DELIVERY_101:orderAmount 는 필수입니다.";
    public static final String AMOUNT_REQUIRED = "DELIVERY_102:금액(amount)은 필수입니다.";
    public static final String AMOUNT_NON_NEGATIVE = "DELIVERY_103:금액(amount)은 0 이상이어야 합니다.";
    public static final String CURRENCY_REQUIRED = "DELIVERY_104:통화(currency)는 필수입니다.";
    public static final String COUNTRY_CODE_REQUIRED = "DELIVERY_105:countryCode 는 필수입니다.";
    public static final String COUNTRY_CODE_FORMAT = "DELIVERY_106:countryCode 는 대문자 두 글자 국가 코드(ISO 3166-1 alpha-2)여야 합니다.";
    public static final String POSTAL_CODE_REQUIRED = "DELIVERY_107:우편번호는 필수입니다.";
    public static final String POSTAL_CODE_FORMAT = "DELIVERY_108:우편번호는 숫자 5자리여야 합니다.";
    public static final String BASE_FEE_REQUIRED = "DELIVERY_109:baseFee 는 필수입니다.";
    public static final String FREE_SHIPPING_THRESHOLD_POSITIVE = "DELIVERY_110:무료배송 기준 금액은 0 보다 커야 합니다.";
    public static final String REMOTE_AREAS_REQUIRED = "DELIVERY_111:remoteAreas 는 필수입니다. 도서산간 구간이 없으면 빈 배열을 보냅니다.";
    public static final String REMOTE_AREAS_SIZE_MAX = "DELIVERY_112:remoteAreas 는 최대 200개입니다.";
    public static final String REMOTE_AREA_REQUIRED = "DELIVERY_113:remoteAreas 에 null 원소를 넣을 수 없습니다.";
    public static final String REMOTE_AREA_NAME_REQUIRED = "DELIVERY_114:도서산간 구간 이름은 필수입니다.";
    public static final String REMOTE_AREA_NAME_LENGTH = "DELIVERY_115:도서산간 구간 이름은 50자 이하여야 합니다.";
    public static final String SURCHARGE_REQUIRED = "DELIVERY_116:도서산간 추가 배송비(surcharge)는 필수입니다.";
    public static final String AMOUNT_MAX = "DELIVERY_117:금액(amount)은 100,000,000 이하여야 합니다.";

    private DeliveryValidationMessage() {
    }
}
