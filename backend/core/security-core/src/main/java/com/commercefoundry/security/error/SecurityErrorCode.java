package com.commercefoundry.security.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 인증·인가 실패 코드 ({@code security-guide.md} 오류 코드 표). 서비스와 게이트웨이가 같은 코드를 쓴다.
 *
 * <p>401 중 {@link #TOKEN_EXPIRED} 만 프론트가 재발급을 시도하는 신호다. 나머지 401 은 다시 로그인해야 한다.
 */
@Getter
@RequiredArgsConstructor
public enum SecurityErrorCode {

    UNAUTHORIZED("SECURITY_001", "로그인이 필요합니다.", HttpStatus.UNAUTHORIZED),
    TOKEN_EXPIRED("SECURITY_002", "인증이 만료되었습니다.", HttpStatus.UNAUTHORIZED),
    TOKEN_INVALID("SECURITY_003", "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.", HttpStatus.UNAUTHORIZED),
    TOKEN_REVOKED("SECURITY_004", "로그아웃된 인증 정보입니다. 다시 로그인해주세요.", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("SECURITY_005", "접근 권한이 없습니다.", HttpStatus.FORBIDDEN),
    TOKEN_VERIFICATION_UNAVAILABLE("SECURITY_006", "일시적으로 인증을 확인할 수 없습니다. 잠시 후 다시 시도해주세요.", HttpStatus.SERVICE_UNAVAILABLE);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
