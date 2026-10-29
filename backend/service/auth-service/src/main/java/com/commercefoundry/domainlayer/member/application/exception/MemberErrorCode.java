package com.commercefoundry.domainlayer.member.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 회원 · 인증 오류 코드 ({@code coding-conventions.md} §8). 필드별 검증 코드(101~)는 {@link MemberValidationMessage} 에 둔다.
 *
 * <p>접두어는 서비스 단위 {@code AUTH_} 다 — 로그인 · 토큰(#4 A3 · A4)도 같은 코드 공간을 쓴다. 001~009 는 회원, 010~ 은 토큰, 030~ 은 관리자(#6) — 034~039 는 관리자 OTP(#238),
 * 040~049 는 시도 제한(#66 · OTP 시도 043) · OTP 잠금 대기 044 다.
 * 로그인 실패는 401 이 아니라 400 이다 — 401 은 토큰(access · refresh) 거절에만 쓴다 (#4 결정 5).
 */
@Getter
@RequiredArgsConstructor
public enum MemberErrorCode {

    /** 이메일이 없거나 · 비밀번호가 틀렸거나 · 고객 로그인에 관리자 계정이 왔다. 어느 쪽인지 밝히지 않는다 (#4 A3). */
    LOGIN_FAILED("AUTH_001", "이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    DUPLICATE_EMAIL("AUTH_002", "이미 가입된 이메일입니다.", HttpStatus.CONFLICT),
    NOT_FOUND_MEMBER("AUTH_003", "존재하지 않는 회원입니다.", HttpStatus.NOT_FOUND),
    /** 같은 회원을 다른 요청이 먼저 고쳤다 (낙관적 락). 다시 읽고 다시 보내면 된다. */
    MEMBER_UPDATE_CONFLICT("AUTH_004", "회원 정보가 다른 요청으로 먼저 변경되었습니다. 다시 시도해 주세요.", HttpStatus.CONFLICT),

    /** refresh 토큰이 발급 모양(base64url 43자)이 아니다. 지문을 계산하거나 저장소를 부르기 전에 거절한다. */
    REFRESH_TOKEN_MALFORMED("AUTH_010", "로그인 정보가 올바르지 않습니다. 다시 로그인해 주세요.", HttpStatus.UNAUTHORIZED),
    /** refresh 토큰이 저장소에 없다 — 만료 · 로그아웃 · family 폐기 · 회원 없음. 어느 쪽인지 밝히지 않는다. */
    REFRESH_TOKEN_INVALID("AUTH_011", "로그인이 만료되었습니다. 다시 로그인해 주세요.", HttpStatus.UNAUTHORIZED),
    /** 이미 회전한 refresh 토큰이 재사용 유예 밖에서 다시 왔다 — 탈취로 보고 그 family 를 모두 폐기했다. */
    REFRESH_TOKEN_REUSED("AUTH_012", "보안을 위해 로그아웃되었습니다. 다시 로그인해 주세요.", HttpStatus.UNAUTHORIZED),
    /** refresh 토큰 · 로그아웃 차단 목록 저장소(Redis)에 닿지 못했다. 로그인 상태는 그대로 두고 다시 시도하게 한다. */
    TOKEN_STORE_UNAVAILABLE("AUTH_013", "일시적으로 로그인을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),

    /**
     * 관리자 로그인 실패 (#6) — 이메일 없음 · 비밀번호 틀림 · 고객 계정을 구분하지 않는다. 문구 · 상태는 고객 로그인 실패({@link #LOGIN_FAILED})와 같고
     * 코드만 관리자 대역이다 — 어드민 콘솔이 고객 화면과 따로 다룰 수 있게. 어느 이유든 이 코드 하나라 계정 종류는 드러나지 않는다.
     */
    ADMIN_LOGIN_FAILED("AUTH_030", "이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    /** 활성 관리자가 한 명뿐이면 그 계정을 끄지 않는다 (#193). 가게 운영 계정이 0이 되면 어드민에 다시 들어갈 수 없다. */
    LAST_ACTIVE_ADMIN("AUTH_031", "마지막 관리자는 비활성화할 수 없습니다.", HttpStatus.CONFLICT),
    /** 로그인한 관리자가 자기 비밀번호를 바꿀 때 현재 비밀번호가 틀린 경우 (#193). */
    ADMIN_PASSWORD_MISMATCH("AUTH_032", "현재 비밀번호가 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    /** 없거나 고객이거나 이미 비활성화된 회원. 어느 쪽인지 나누지 않는다 (#193). */
    ADMIN_ACCOUNT_NOT_FOUND("AUTH_033", "관리자 계정을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),

    // ── 관리자 OTP (#238, AUTH_034~039 · AUTH_043 · AUTH_044)

    /** 로그인 2단계 챌린지가 없다 — 만료 · 이미 씀 · 위조. 어느 쪽인지 밝히지 않는다. 1단계(비밀번호)부터 다시 한다. */
    OTP_CHALLENGE_INVALID("AUTH_034", "인증 요청이 만료되었거나 올바르지 않습니다. 다시 로그인해 주세요.", HttpStatus.BAD_REQUEST),
    /** OTP 코드 · 복구 코드가 틀렸거나 이미 쓴 코드다. 어느 쪽인지 밝히지 않는다. */
    OTP_CODE_INVALID("AUTH_035", "인증 코드가 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    /** OTP 를 등록하지 않은 관리자가 해제 · 복구 코드 재발급을 요청했다. */
    OTP_NOT_ENROLLED("AUTH_036", "등록된 OTP 가 없습니다.", HttpStatus.NOT_FOUND),
    /** 이미 OTP 를 등록한 관리자가 다시 등록을 시작했다 — 바꾸려면 해제부터 한다. */
    OTP_ALREADY_ENROLLED("AUTH_037", "이미 OTP 가 등록되어 있습니다.", HttpStatus.CONFLICT),
    /** 등록 확인 요청인데 진행 중인 등록이 없다(만료 · 확인 완료). 등록을 다시 시작한다. */
    OTP_ENROLLMENT_NOT_FOUND("AUTH_038", "진행 중인 OTP 등록이 없습니다. 등록을 다시 시작해 주세요.", HttpStatus.BAD_REQUEST),
    /** OTP 비밀을 읽을 수 없다 — 맞는 암호화 키가 없거나(회전 실수) 암호문이 깨졌다. 운영 조치가 필요하다. */
    OTP_UNAVAILABLE("AUTH_039", "일시적으로 OTP 인증을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),

    // ── 시도 제한 (#66, AUTH_040~049) — 예외는 LoginAttemptLimitedException(Retry-After 를 싣는다)

    /**
     * 같은 이메일의 로그인 시도가 창의 허용 횟수를 넘었다 — 고객 · 관리자 로그인 모두. 비밀번호가 맞아도 거절하고, 계정이 있든 없든 같은 본문 · 헤더다
     * ({@code security-guide.md} §14). 창이 끝나면 다시 허용한다.
     */
    LOGIN_ATTEMPT_LIMITED("AUTH_040", "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.TOO_MANY_REQUESTS),
    /** 같은 이메일의 회원가입 시도가 창의 허용 횟수를 넘었다. 가입된 이메일이든 아니든 같은 본문 · 헤더다 (BCrypt · 중복 확인 전에 판정). */
    SIGN_UP_ATTEMPT_LIMITED("AUTH_041", "회원가입 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.TOO_MANY_REQUESTS),
    /** 같은 관리자의 OTP 코드 시도가 창의 허용 횟수를 넘었다 (#238). 코드가 맞아도 거절한다. AUTH_042 는 BCrypt bulkhead 예약이다. */
    OTP_ATTEMPT_LIMITED("AUTH_043", "인증 코드 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.TOO_MANY_REQUESTS),
    /**
     * 같은 관리자의 다른 OTP 쓰기(복구 코드 교체 · 해제)가 자격 행 잠금을 쥐고 있어 대기 상한 안에 얻지 못했다 (#238). 그 관리자 요청끼리 겹친 것이라
     * 503 이 아니라 재시도 가능한 409 다.
     */
    OTP_BUSY("AUTH_044", "다른 OTP 요청을 처리하고 있습니다. 잠시 뒤 다시 시도해 주세요.", HttpStatus.CONFLICT),

    /** 코드 접두어 없는 검증 메시지 · 역직렬화 실패(깨진 JSON, 본문 없음)의 폴백. */
    SOCIAL_PROVIDER_UNSUPPORTED("AUTH_050", "지원하지 않는 소셜 로그인 제공자입니다.", HttpStatus.BAD_REQUEST),
    SOCIAL_DISABLED("AUTH_051", "소셜 로그인을 사용할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    SOCIAL_STATE_INVALID("AUTH_052", "소셜 로그인 요청이 만료되었거나 올바르지 않습니다. 다시 시작해 주세요.", HttpStatus.BAD_REQUEST),
    SOCIAL_CODE_INVALID("AUTH_053", "소셜 인가 코드가 올바르지 않습니다. 다시 시작해 주세요.", HttpStatus.BAD_REQUEST),
    SOCIAL_PROVIDER_UNAVAILABLE("AUTH_054", "소셜 로그인 제공자에 연결할 수 없습니다. 잠시 후 다시 시작해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    SOCIAL_EMAIL_REQUIRED("AUTH_055", "가입에 사용할 수 있는 소셜 이메일이 필요합니다.", HttpStatus.BAD_REQUEST),
    SOCIAL_AGREEMENTS_REQUIRED("AUTH_056", "신규 가입에는 필수 약관 동의가 필요합니다.", HttpStatus.BAD_REQUEST),
    SOCIAL_IDENTITY_CONFLICT("AUTH_057", "소셜 계정 등록이 다른 요청과 겹쳤습니다. 다시 시작해 주세요.", HttpStatus.CONFLICT),

    SOCIAL_REAUTH_FAILED("AUTH_058", "현재 비밀번호를 확인할 수 없습니다.", HttpStatus.BAD_REQUEST),
    SOCIAL_REAUTH_LIMITED("AUTH_059", "비밀번호 확인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.TOO_MANY_REQUESTS),
    SOCIAL_REAUTH_UNAVAILABLE("AUTH_060", "지금은 계정 연결을 시작할 수 없습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),

    INVALID_REQUEST("AUTH_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_TYPE_INVALID("AUTH_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_REQUIRED("AUTH_199", "필수 요청 파라미터가 없습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
