package com.commercefoundry.domainlayer.shippingaddress.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 배송지 내용 — 받는 사람 · 연락처 · 주소. 국내(KR)와 해외 주소를 같은 모양으로 담는다.
 *
 * <p><b>검증은 쓰는 쪽에서만 한다.</b> {@link #of} 가 값을 정규화하고 국가별 규칙을 검사한다. 규칙을 벗어나면 {@link IllegalArgumentException} 이다
 * (요청 검증 AUTH_120~ 를 지나온 값이라 여기서 걸리면 호출 코드의 오류다). 저장된 행은 {@link #restore} 로 검증 없이 되살린다 —
 * 이관 데이터가 지금 규칙 밖이어도 목록 조회가 500 이 되지 않게.
 *
 * <ul>
 *   <li>국가 코드 — ISO 3166-1 alpha-2 대문자 형식만 본다. 지원 국가 목록은 검사하지 않는다</li>
 *   <li>우편번호 — KR 이면 숫자 5자리 필수, 그 밖은 선택(영문 · 숫자 · 공백 · 하이픈 20자 이하). delivery-service 견적(#92)과 같은 규칙이다</li>
 *   <li>연락처 — 하이픈 · 공백을 뺀 값으로 저장한다. KR 이면 0 으로 시작하는 숫자 9~11자리(휴대폰 01x 10~11자리 · 지역번호 유선),
 *       그 밖은 {@code +} 로 시작할 수 있는 숫자 7~15자리(E.164)다</li>
 *   <li>도시 — KR 이 아니면 필수다. 국내 주소는 도로명 주소(addressLine1)에 시 · 도가 들어 있다</li>
 *   <li>선택 칸(label · postalCode · addressLine2 · city · stateProvince)은 앞뒤 공백을 지우고, 비면 null 로 둔다</li>
 * </ul>
 *
 * @param label 별칭 (집 · 회사). 없으면 null
 * @param recipientPhone 하이픈 · 공백을 뺀 값
 * @param postalCode KR 이면 숫자 5자리, 그 밖은 없으면 null
 */
public record ShippingAddressContent(
    String label,
    String recipientName,
    String recipientPhone,
    String countryCode,
    String postalCode,
    String addressLine1,
    String addressLine2,
    String city,
    String stateProvince
) {

    public static final String DOMESTIC_COUNTRY_CODE = "KR";

    public static final int LABEL_MAX_LENGTH = 30;
    public static final int RECIPIENT_NAME_MAX_LENGTH = 50;
    public static final int RECIPIENT_PHONE_MAX_LENGTH = 20;
    public static final int POSTAL_CODE_MAX_LENGTH = 20;
    public static final int ADDRESS_LINE_MAX_LENGTH = 200;
    public static final int CITY_MAX_LENGTH = 100;
    public static final int STATE_PROVINCE_MAX_LENGTH = 100;

    /** ISO 3166-1 alpha-2 형식(대문자 두 글자). 요청 DTO 의 {@code @Pattern} 도 이 값을 쓴다. */
    public static final String COUNTRY_CODE_REGEX = "^[A-Z]{2}$";

    private static final Pattern COUNTRY_CODE = Pattern.compile(COUNTRY_CODE_REGEX);
    private static final Pattern DOMESTIC_POSTAL_CODE = Pattern.compile("^[0-9]{5}$");
    /** 해외 우편번호는 나라마다 모양이 달라 문자 종류 · 길이만 본다 (예: {@code 10001}, {@code SW1A 1AA}, {@code 100-0001}). */
    private static final Pattern INTERNATIONAL_POSTAL_CODE = Pattern.compile("^[A-Za-z0-9 -]{1,20}$");
    /** 국내 — 휴대폰(010 · 011 …)과 지역번호 유선(02 · 031 …)을 하나로 본다. 배송 연락처라 유선도 받는다. */
    private static final Pattern DOMESTIC_PHONE = Pattern.compile("^0[0-9]{8,10}$");
    /** 해외 — E.164 (국가번호 포함 최대 15자리). {@code +} 는 있어도 없어도 된다. */
    private static final Pattern INTERNATIONAL_PHONE = Pattern.compile("^\\+?[0-9]{7,15}$");
    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s-]");

    public ShippingAddressContent {
        Objects.requireNonNull(recipientName, "recipientName");
        Objects.requireNonNull(recipientPhone, "recipientPhone");
        Objects.requireNonNull(countryCode, "countryCode");
        Objects.requireNonNull(addressLine1, "addressLine1");
    }

    /**
     * 새로 쓰는 배송지 내용. 값을 정규화하고 국가별 규칙을 검사한다.
     *
     * @throws IllegalArgumentException 규칙을 벗어났다 — 어느 칸인지만 밝히고 값은 싣지 않는다(개인정보)
     */
    public static ShippingAddressContent of(
        String label, String recipientName, String recipientPhone, String countryCode, String postalCode,
        String addressLine1, String addressLine2, String city, String stateProvince
    ) {
        if (countryCode == null || !isValidCountryCode(countryCode)) {
            throw new IllegalArgumentException("countryCode 형식이 규칙을 벗어났다");
        }
        boolean domestic = isDomestic(countryCode);

        String normalizedPostalCode = optional(postalCode, POSTAL_CODE_MAX_LENGTH, "postalCode");
        if (domestic ? !isValidDomesticPostalCode(normalizedPostalCode) : !isValidInternationalPostalCode(normalizedPostalCode)) {
            throw new IllegalArgumentException("postalCode 형식이 규칙을 벗어났다");
        }
        String normalizedCity = optional(city, CITY_MAX_LENGTH, "city");
        if (!domestic && normalizedCity == null) {
            throw new IllegalArgumentException("city 는 해외 주소에서 필수다");
        }
        String normalizedPhone = normalizePhone(recipientPhone);
        if (!isValidPhone(countryCode, normalizedPhone)) {
            throw new IllegalArgumentException("recipientPhone 형식이 규칙을 벗어났다");
        }

        return new ShippingAddressContent(
            optional(label, LABEL_MAX_LENGTH, "label"),
            required(recipientName, RECIPIENT_NAME_MAX_LENGTH, "recipientName"),
            normalizedPhone,
            countryCode,
            normalizedPostalCode,
            required(addressLine1, ADDRESS_LINE_MAX_LENGTH, "addressLine1"),
            optional(addressLine2, ADDRESS_LINE_MAX_LENGTH, "addressLine2"),
            normalizedCity,
            optional(stateProvince, STATE_PROVINCE_MAX_LENGTH, "stateProvince")
        );
    }

    /** 저장된 행을 검증 없이 되살린다 — 영속성 매퍼만 쓴다. */
    public static ShippingAddressContent restore(
        String label, String recipientName, String recipientPhone, String countryCode, String postalCode,
        String addressLine1, String addressLine2, String city, String stateProvince
    ) {
        return new ShippingAddressContent(label, recipientName, recipientPhone, countryCode, postalCode, addressLine1, addressLine2, city, stateProvince);
    }

    public static boolean isDomestic(String countryCode) {
        return DOMESTIC_COUNTRY_CODE.equals(countryCode);
    }

    public static boolean isValidCountryCode(String countryCode) {
        return countryCode != null && COUNTRY_CODE.matcher(countryCode).matches();
    }

    /** @param postalCode 앞뒤 공백을 지운 값. null 이면 없는 값이다 */
    public static boolean isValidDomesticPostalCode(String postalCode) {
        return postalCode != null && DOMESTIC_POSTAL_CODE.matcher(postalCode).matches();
    }

    /** 해외 우편번호는 선택이다 — null 은 통과한다. @param postalCode 앞뒤 공백을 지운 값 */
    public static boolean isValidInternationalPostalCode(String postalCode) {
        return postalCode == null || INTERNATIONAL_POSTAL_CODE.matcher(postalCode).matches();
    }

    /**
     * 연락처가 그 국가 규칙에 맞는가. 하이픈 · 공백은 무시한다.
     *
     * @param countryCode 형식이 맞는 국가 코드
     */
    public static boolean isValidPhone(String countryCode, String phone) {
        if (phone == null) {
            return false;
        }
        String normalized = normalizePhone(phone);
        return (isDomestic(countryCode) ? DOMESTIC_PHONE : INTERNATIONAL_PHONE).matcher(normalized).matches();
    }

    /** 앞뒤 공백을 지우고 비면 null. 길이를 넘으면 거절한다. */
    private static String optional(String value, int maxLength, String field) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        if (stripped.length() > maxLength) {
            throw new IllegalArgumentException(field + " 길이가 규칙을 벗어났다");
        }
        return stripped;
    }

    private static String required(String value, int maxLength, String field) {
        String normalized = optional(value, maxLength, field);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " 는 필수다");
        }
        return normalized;
    }

    private static String normalizePhone(String phone) {
        Objects.requireNonNull(phone, "recipientPhone");
        return PHONE_SEPARATORS.matcher(phone).replaceAll("");
    }

    /** 개인정보(받는 사람 · 연락처 · 주소)를 싣지 않는다 — 로그 · 예외 메시지로 새지 않게 ({@code coding-conventions.md} §8). */
    @Override
    public String toString() {
        return "ShippingAddressContent[countryCode=" + countryCode + "]";
    }
}
