package com.commercefoundry.security.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 인증·인가 실패 코드 ({@code security-guide.md} 오류 코드 표). 서비스와 게이트웨이가 같은 코드를 쓴다.
 *
 * <p>코드는 거부 <b>원인</b>을 알린다. 프론트(BFF)는 재발급 여부를 코드로 가르지 않는다 — 401 이면 refresh 로 한 번 재발급 후 재시도하고,
 * 403 은 권한 없음 안내, 503({@link #TOKEN_VERIFICATION_UNAVAILABLE})은 로그아웃시키지 않고 재시도한다 ({@code api-design-guide.md} §2-1).
 * {@link #TOKEN_EXPIRED} 는 보낸 토큰이 만료됐다는 뜻이고, access 쿠키가 만료로 사라져 토큰 없이 온 요청은 {@link #UNAUTHORIZED} 다.
 *
 * <p>{@link #SERVICE_UNAUTHORIZED} 는 서비스 간 internal API({@code /internal/**})에서만 쓴다. 호출자는 다른 서비스라 프론트가 보지 않는다
 * ({@code security-guide.md} §9).
 */
@Getter
@RequiredArgsConstructor
public enum SecurityErrorCode {

    UNAUTHORIZED("SECURITY_001", "로그인이 필요합니다.", HttpStatus.UNAUTHORIZED),
    TOKEN_EXPIRED("SECURITY_002", "인증이 만료되었습니다.", HttpStatus.UNAUTHORIZED),
    TOKEN_INVALID("SECURITY_003", "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.", HttpStatus.UNAUTHORIZED),
    TOKEN_REVOKED("SECURITY_004", "로그아웃된 인증 정보입니다. 다시 로그인해주세요.", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("SECURITY_005", "접근 권한이 없습니다.", HttpStatus.FORBIDDEN),
    TOKEN_VERIFICATION_UNAVAILABLE("SECURITY_006", "일시적으로 인증을 확인할 수 없습니다. 잠시 후 다시 시도해주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    SERVICE_UNAUTHORIZED("SECURITY_007", "서비스 인증에 실패했습니다.", HttpStatus.UNAUTHORIZED);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
