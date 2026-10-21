package com.commercefoundry.domainlayer.member.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 회원 · 인증 오류 코드 ({@code coding-conventions.md} §8). 필드별 검증 코드(101~)는 {@link MemberValidationMessage} 에 둔다.
 *
 * <p>접두어는 서비스 단위 {@code AUTH_} 다 — 로그인 · 토큰(#4 A3 · A4)도 같은 코드 공간을 쓴다. 001~009 는 회원, 010~ 은 토큰, 030~ 은 관리자(#6),
 * 040~049 는 시도 제한(#66)이다.
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

    // ── 시도 제한 (#66, AUTH_040~049) — 예외는 LoginAttemptLimitedException(Retry-After 를 싣는다)

    /**
     * 같은 이메일의 로그인 시도가 창의 허용 횟수를 넘었다 — 고객 · 관리자 로그인 모두. 비밀번호가 맞아도 거절하고, 계정이 있든 없든 같은 본문 · 헤더다
     * ({@code security-guide.md} §12). 창이 끝나면 다시 허용한다.
     */
    LOGIN_ATTEMPT_LIMITED("AUTH_040", "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.TOO_MANY_REQUESTS),
    /** 같은 이메일의 회원가입 시도가 창의 허용 횟수를 넘었다. 가입된 이메일이든 아니든 같은 본문 · 헤더다 (BCrypt · 중복 확인 전에 판정). */
    SIGN_UP_ATTEMPT_LIMITED("AUTH_041", "회원가입 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.TOO_MANY_REQUESTS),

    /** 코드 접두어 없는 검증 메시지 · 역직렬화 실패(깨진 JSON, 본문 없음)의 폴백. */
    INVALID_REQUEST("AUTH_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_TYPE_INVALID("AUTH_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_REQUIRED("AUTH_199", "필수 요청 파라미터가 없습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
