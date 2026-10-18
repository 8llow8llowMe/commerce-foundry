package com.commercefoundry.domainlayer.member.application.exception;

/**
 * 회원 요청 검증 메시지 — {@code "AUTH_1xx:사용자 메시지"} ({@code coding-conventions.md} §8-2). DTO 는 이 상수만 참조한다.
 * 대역 끝 198 · 199 는 {@link MemberErrorCode} 의 타입 불일치 · 필수 파라미터 누락이다. 로그인 요청은 같은 뜻의 101 · 102 · 104 를 다시 쓴다. 토큰 요청의 refresh 토큰은 필드 검증 대신 401(AUTH_010)로 거절한다.
 */
public final class MemberValidationMessage {

    public static final String EMAIL_REQUIRED = "AUTH_101:이메일은 필수입니다.";
    public static final String EMAIL_LENGTH_INVALID = "AUTH_102:이메일은 254자 이하여야 합니다.";
    public static final String EMAIL_FORMAT_INVALID = "AUTH_103:이메일 형식이 올바르지 않습니다.";
    public static final String PASSWORD_REQUIRED = "AUTH_104:비밀번호는 필수입니다.";
    public static final String PASSWORD_LENGTH_INVALID = "AUTH_105:비밀번호는 8자 이상 64자 이하여야 합니다.";
    public static final String PASSWORD_FORMAT_INVALID = "AUTH_106:비밀번호는 영문과 숫자를 1자 이상씩 포함하고, 공백 없이 영문 · 숫자 · 특수문자로만 입력해 주세요.";
    public static final String NAME_REQUIRED = "AUTH_107:이름은 필수입니다.";
    public static final String NAME_LENGTH_INVALID = "AUTH_108:이름은 1자 이상 50자 이하여야 합니다.";
    public static final String PHONE_FORMAT_INVALID = "AUTH_109:휴대폰 번호 형식이 올바르지 않습니다.";
    public static final String AGREEMENTS_REQUIRED = "AUTH_110:약관 동의 정보는 필수입니다.";
    public static final String TERMS_OF_SERVICE_REQUIRED = "AUTH_111:이용약관에 동의해야 가입할 수 있습니다.";
    public static final String PRIVACY_POLICY_REQUIRED = "AUTH_112:개인정보 수집 · 이용에 동의해야 가입할 수 있습니다.";
    public static final String MARKETING_REQUIRED = "AUTH_113:마케팅 정보 수신 동의 여부는 필수입니다.";
    public static final String NAME_BLANK = "AUTH_114:이름은 공백만으로 입력할 수 없습니다.";
    public static final String PROFILE_UPDATE_EMPTY = "AUTH_115:변경할 항목이 없습니다.";

    private MemberValidationMessage() {
    }
}
