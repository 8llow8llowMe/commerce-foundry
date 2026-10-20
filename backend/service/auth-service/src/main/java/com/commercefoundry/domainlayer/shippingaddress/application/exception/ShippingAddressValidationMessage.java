package com.commercefoundry.domainlayer.shippingaddress.application.exception;

/**
 * 배송지 요청 검증 메시지 — {@code "AUTH_1xx:사용자 메시지"} ({@code coding-conventions.md} §8-2). DTO 와 검증기는 이 상수만 참조한다.
 *
 * <p>auth-service 의 1xx 대역을 컨텍스트별로 나눠 쓴다 — 101~115 회원, 116~119 예약 없음(비워 둔다), <b>120~129 배송지</b>, 130~ 관리자(#6).
 * 대역이 열 칸이라 한 필드의 필수 · 길이 · 형식을 한 코드로 묶은 곳이 있다 — 어느 칸인지는 {@code fieldErrors[].field} 가 밝힌다.
 * 대역 끝 198 · 199 · 폴백 100 은 {@code MemberErrorCode} 의 것을 같이 쓴다. 번호는 요청 DTO 의 필드 선언 순서를 따른다.
 */
public final class ShippingAddressValidationMessage {

    public static final String LABEL_LENGTH_INVALID = "AUTH_120:배송지 별칭은 30자 이하여야 합니다.";
    /** 비었거나(공백뿐 포함) 50자를 넘는다. */
    public static final String RECIPIENT_NAME_INVALID = "AUTH_121:받는 사람 이름은 1자 이상 50자 이하로 입력해 주세요.";
    public static final String RECIPIENT_PHONE_REQUIRED = "AUTH_122:받는 사람 연락처는 필수입니다.";
    public static final String RECIPIENT_PHONE_FORMAT_INVALID =
        "AUTH_123:연락처 형식이 올바르지 않습니다. 국내는 0으로 시작하는 9~11자리, 해외는 국가번호를 포함한 7~15자리 숫자로 입력해 주세요.";
    /** 비었거나 대문자 두 글자가 아니다. */
    public static final String COUNTRY_CODE_INVALID = "AUTH_124:국가 코드는 영문 대문자 2자리(ISO 3166-1 alpha-2)로 입력해 주세요.";
    public static final String POSTAL_CODE_REQUIRED = "AUTH_125:국내 주소는 우편번호가 필수입니다.";
    /** 국내(숫자 5자리) · 해외(영문 · 숫자 · 공백 · 하이픈 20자 이하) 형식 위반. */
    public static final String POSTAL_CODE_FORMAT_INVALID =
        "AUTH_126:우편번호 형식이 올바르지 않습니다. 국내는 숫자 5자리, 해외는 영문 · 숫자 · 공백 · 하이픈 20자 이하로 입력해 주세요.";
    public static final String ADDRESS_LINE1_REQUIRED = "AUTH_127:주소는 필수입니다.";
    /** addressLine1 · addressLine2 · city · stateProvince 길이 위반. */
    public static final String ADDRESS_LENGTH_INVALID = "AUTH_128:주소 · 상세 주소는 200자, 도시 · 주(도)는 100자 이하여야 합니다.";
    public static final String CITY_REQUIRED = "AUTH_129:해외 주소는 도시가 필수입니다.";

    private ShippingAddressValidationMessage() {
    }
}
