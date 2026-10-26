package com.commercefoundry.domainlayer.member.application.exception;

/**
 * 회원 요청 검증 메시지 — {@code "AUTH_1xx:사용자 메시지"} ({@code coding-conventions.md} §8-2). DTO 는 이 상수만 참조한다.
 * 101~ 은 고객, 130~ 은 관리자다. 대역 끝 198 · 199 는 {@link MemberErrorCode} 의 타입 불일치 · 필수 파라미터 누락이다.
 * 로그인 요청은 같은 뜻의 101 · 102 · 104 를 다시 쓴다. 토큰 요청의 refresh 토큰은 필드 검증 대신 401(AUTH_010)로 거절한다.
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

    /*
     * 130~ 은 관리자(#6)다 — 관리자 로그인 요청 · 어드민 회원 조회 파라미터. 고객 DTO 와 공유하지 않으므로 같은 뜻이어도 코드를 따로 둔다.
     * 관리자 로그인도 비밀번호 형식은 보지 않는다 — 틀리면 AUTH_030 이다.
     */
    public static final String ADMIN_EMAIL_REQUIRED = "AUTH_130:이메일은 필수입니다.";
    public static final String ADMIN_EMAIL_LENGTH_INVALID = "AUTH_131:이메일은 254자 이하여야 합니다.";
    public static final String ADMIN_PASSWORD_REQUIRED = "AUTH_132:비밀번호는 필수입니다.";
    public static final String ADMIN_PAGE_MIN = "AUTH_133:페이지는 0 이상이어야 합니다.";
    public static final String ADMIN_SIZE_RANGE = "AUTH_134:페이지 크기는 1 이상 100 이하여야 합니다.";
    public static final String ADMIN_AUDIT_ACTOR_ID_POSITIVE = "AUTH_135:actorMemberId 는 양수여야 합니다.";
    public static final String ADMIN_AUDIT_TARGET_INVALID = "AUTH_136:target 은 공백 없이 1자 이상 200자 이하의 ASCII 여야 합니다.";

    /** 서비스 간 회원 표시 이름 벌크 조회 ({@code /internal/v1/member-display-names}, {@code api-design-guide.md} §1-3). */
    public static final String DISPLAY_NAME_IDS_REQUIRED = "AUTH_137:ids 는 필수입니다.";
    public static final String DISPLAY_NAME_IDS_SIZE_MAX = "AUTH_138:ids 는 최대 100개입니다.";
    public static final String DISPLAY_NAME_ID_POSITIVE = "AUTH_139:id 는 양수여야 합니다.";

    /** 관리자 계정 추가 · 비밀번호 변경 (#193). */
    public static final String ADMIN_ACCOUNT_EMAIL_FORMAT = "AUTH_140:이메일 형식이 올바르지 않습니다.";
    public static final String ADMIN_ACCOUNT_PASSWORD_LENGTH = "AUTH_141:비밀번호는 12자 이상 64자 이하여야 합니다.";
    public static final String ADMIN_ACCOUNT_PASSWORD_FORMAT = "AUTH_142:비밀번호는 영문과 숫자를 1자 이상씩 포함하고, 공백 없이 영문 · 숫자 · 특수문자로만 입력해 주세요.";
    public static final String ADMIN_ACCOUNT_NAME_REQUIRED = "AUTH_143:이름은 필수입니다.";
    public static final String ADMIN_ACCOUNT_NAME_LENGTH = "AUTH_144:이름은 1자 이상 50자 이하여야 합니다.";
    public static final String ADMIN_ACCOUNT_CURRENT_PASSWORD_REQUIRED = "AUTH_145:현재 비밀번호는 필수입니다.";
    public static final String ADMIN_ACCOUNT_ID_POSITIVE = "AUTH_146:관리자 아이디는 양수여야 합니다.";

    private MemberValidationMessage() {
    }
}
