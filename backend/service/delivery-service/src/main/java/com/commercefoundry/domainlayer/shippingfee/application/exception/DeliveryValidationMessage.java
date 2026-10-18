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
    public static final String WEIGHT_GRAMS_REQUIRED = "DELIVERY_118:해외(KR 외) 배송 견적에는 weightGrams 가 필수입니다.";
    public static final String WEIGHT_GRAMS_POSITIVE = "DELIVERY_119:weightGrams 는 1 이상이어야 합니다.";
    public static final String INTERNATIONAL_POSTAL_CODE_FORMAT = "DELIVERY_120:해외 우편번호는 영문 · 숫자 · 공백 · 하이픈으로 20자 이하여야 합니다.";
    public static final String CUSTOMS_NOTICE_LENGTH = "DELIVERY_121:관세 · 부가세 안내 문구는 1000자 이하여야 합니다.";
    public static final String ZONES_REQUIRED = "DELIVERY_122:zones 는 1개 이상이어야 합니다.";
    public static final String ZONES_SIZE_MAX = "DELIVERY_123:zones 는 최대 50개입니다.";
    public static final String ZONE_REQUIRED = "DELIVERY_124:zones 에 null 원소를 넣을 수 없습니다.";
    public static final String ZONE_NAME_REQUIRED = "DELIVERY_125:국가 그룹 이름은 필수입니다.";
    public static final String ZONE_NAME_LENGTH = "DELIVERY_126:국가 그룹 이름은 50자 이하여야 합니다.";
    public static final String ZONE_COUNTRY_CODES_REQUIRED = "DELIVERY_127:국가 그룹에는 국가(countryCodes)가 1개 이상 있어야 합니다.";
    public static final String ZONE_COUNTRY_CODES_SIZE_MAX = "DELIVERY_128:국가 그룹의 국가(countryCodes)는 최대 250개입니다.";
    public static final String ZONE_RATES_REQUIRED = "DELIVERY_129:국가 그룹에는 무게 구간(rates)이 1개 이상 있어야 합니다.";
    public static final String ZONE_RATES_SIZE_MAX = "DELIVERY_130:국가 그룹의 무게 구간(rates)은 최대 100개입니다.";
    public static final String ZONE_RATE_REQUIRED = "DELIVERY_131:rates 에 null 원소를 넣을 수 없습니다.";
    public static final String MAX_WEIGHT_GRAMS_REQUIRED = "DELIVERY_132:무게 구간 상한(maxWeightGrams)은 필수입니다.";
    public static final String MAX_WEIGHT_GRAMS_POSITIVE = "DELIVERY_133:무게 구간 상한(maxWeightGrams)은 1 이상이어야 합니다.";
    public static final String MAX_WEIGHT_GRAMS_MAX = "DELIVERY_134:무게 구간 상한(maxWeightGrams)은 1,000,000 이하여야 합니다.";
    public static final String RATE_FEE_REQUIRED = "DELIVERY_135:무게 구간 요금(fee)은 필수입니다.";
    public static final String REMOTE_AREAS_NOT_ALLOWED = "DELIVERY_136:해외 배송비 정책에는 도서산간 구간(remoteAreas)을 둘 수 없습니다.";

    private DeliveryValidationMessage() {
    }
}
