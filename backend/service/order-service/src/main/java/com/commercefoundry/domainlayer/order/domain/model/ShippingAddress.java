package com.commercefoundry.domainlayer.order.domain.model;

import com.commercefoundry.shared.commerce.ShippingScope;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 주문 시점의 배송지 스냅숏 — {@code orders.order_shipping_address} 한 행. 회원 주소록(#7)을 바꿔도 지난 주문의 배송지는 바뀌지 않는다 ({@code order-flow-guide.md} §4).
 * 칸 이름 · 길이 · 규칙은 #7 주소록(auth-service {@code ShippingAddressContent})과 같다 — 프론트가 주소록 값을 바꾸지 않고 싣는다.
 * 개인정보라 로그에 남기지 않는다 — {@link #toString()} 은 국가만 보이고, 규칙 위반 메시지에는 칸 이름만 싣는다.
 *
 * <p>만들 때 정규화하고 본다 — 요청 검증({@code ORDER_1xx})을 지나온 값이라 여기서 걸리면 조립 버그다({@link IllegalArgumentException}). 다시 읽어 조립할 때도 같은 규칙을
 * 지나므로 DB check({@code ck_order_shipping_address_*})가 같은 규칙을 막는다.
 * <ul>
 *   <li>국가 코드 — ISO 3166-1 alpha-2 대문자. 국내 판정은 shared-commerce {@link ShippingScope#ofCountry} 한 곳이다</li>
 *   <li>연락처 — 공백 · 하이픈을 뺀 값으로 둔다. 국내(KR)는 0 으로 시작하는 숫자 9~11자리, 그 밖은 {@code +} 로 시작할 수 있는 숫자 7~15자리(E.164) —
 *       최대 16자라 컬럼(20자)에 들어간다</li>
 *   <li>우편번호 — 국내는 필수. 형식(국내 숫자 5자리 · 해외 영문 · 숫자 · 공백 · 하이픈 20자 이하)은 요청 DTO 가 본다</li>
 *   <li>도시 · 주 — 앞뒤 공백을 지우고 비면 null, 각 {@value #CITY_MAX_LENGTH}자 이하. 해외는 도시가 필수다(국내 주소는 도로명 주소에 시 · 도가 들어 있다)</li>
 * </ul>
 *
 * @param recipientPhone 공백 · 하이픈을 뺀 연락처
 * @param countryCode 배송 국가 (ISO 3166-1 alpha-2 대문자) — 주문서 계산의 배송 국가와 같다
 * @param postalCode 우편번호. KR 이면 필수, 해외는 없으면 null
 * @param addressLine2 상세 주소. 없으면 null
 * @param city 도시. 해외는 필수, 국내는 없으면 null
 * @param stateProvince 주 · 도 (state / province). 없으면 null
 * @param deliveryMemo 배송 요청 사항. 없으면 null
 */
public record ShippingAddress(
    String recipientName, String recipientPhone, String countryCode, String postalCode, String addressLine1, String addressLine2,
    String city, String stateProvince, String deliveryMemo
) {

    public static final int CITY_MAX_LENGTH = 100;
    public static final int STATE_PROVINCE_MAX_LENGTH = 100;

    /** 국내 — 휴대폰(010 · 011 …)과 지역번호 유선(02 · 031 …)을 하나로 본다. 배송 연락처라 유선도 받는다. */
    private static final Pattern DOMESTIC_PHONE = Pattern.compile("^0[0-9]{8,10}$");
    /** 해외 — E.164 (국가번호 포함 최대 15자리). {@code +} 는 있어도 없어도 된다. */
    private static final Pattern INTERNATIONAL_PHONE = Pattern.compile("^\\+?[0-9]{7,15}$");
    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s-]");

    public ShippingAddress {
        Objects.requireNonNull(recipientName, "recipientName");
        Objects.requireNonNull(recipientPhone, "recipientPhone");
        Objects.requireNonNull(countryCode, "countryCode");
        Objects.requireNonNull(addressLine1, "addressLine1");
        ShippingScope scope = ShippingScope.ofCountry(countryCode)
            .orElseThrow(() -> new IllegalArgumentException("countryCode 형식이 규칙을 벗어났습니다"));

        recipientPhone = PHONE_SEPARATORS.matcher(recipientPhone).replaceAll("");
        if (!(scope == ShippingScope.DOMESTIC ? DOMESTIC_PHONE : INTERNATIONAL_PHONE).matcher(recipientPhone).matches()) {
            throw new IllegalArgumentException("recipientPhone 형식이 규칙을 벗어났습니다");
        }
        if (scope == ShippingScope.DOMESTIC && postalCode == null) {
            throw new IllegalArgumentException("postalCode 는 국내 배송지에서 필수입니다");
        }
        city = optional(city, CITY_MAX_LENGTH, "city");
        stateProvince = optional(stateProvince, STATE_PROVINCE_MAX_LENGTH, "stateProvince");
        if (scope == ShippingScope.INTERNATIONAL && city == null) {
            throw new IllegalArgumentException("city 는 해외 배송지에서 필수입니다");
        }
    }

    /** 앞뒤 공백을 지우고 비면 null. 길이를 넘으면 거절한다 — 값은 메시지에 싣지 않는다. */
    private static String optional(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw new IllegalArgumentException(field + " 는 " + maxLength + "자 이하입니다");
        }
        return stripped;
    }

    @Override
    public String toString() {
        return "ShippingAddress[countryCode=" + countryCode + "]";
    }
}
