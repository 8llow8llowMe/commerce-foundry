package com.commercefoundry.security.error;

import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/**
 * Spring Security 예외를 {@link SecurityErrorCode} 로 바꾼다. 서블릿 진입점·advice 와 게이트웨이(#4)가 같이 쓴다.
 *
 * <p>판정은 예외 타입과 {@link OAuth2Error#getErrorCode()} 로만 한다. 예외 메시지 문자열을 비교하지 않는다 —
 * 라이브러리 문구가 바뀌면 만료가 조용히 "위조" 로 바뀐다.
 */
public final class SecurityErrorResolver {

    private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

    private SecurityErrorResolver() {
    }

    /**
     * 인증 실패의 코드를 고른다.
     *
     * <ul>
     *   <li>토큰이 없다 ({@link InsufficientAuthenticationException}, {@link AuthenticationCredentialsNotFoundException}) → 001</li>
     *   <li>원인 사슬의 {@link OAuth2AuthenticationException} 오류 코드가 {@code SECURITY_xxx} 다 → 그 코드 (#4 의 로그아웃 토큰 004 · 저장소 장애 006)</li>
     *   <li>검증 위반이 <b>만료 하나뿐</b>이다 → 002. 다른 위반이 하나라도 섞이면 003 — 만료 신호로 재발급을 시도하게 두지 않는다</li>
     *   <li>인증 처리 중 시스템 오류 ({@link AuthenticationServiceException}) → 006 — 토큰 잘못이 아니므로 401 로 로그아웃시키지 않는다</li>
     *   <li>그 밖의 bearer 오류 (서명 · 형식 · alg · typ · iss · aud · claim) → 003</li>
     * </ul>
     */
    public static SecurityErrorCode resolve(AuthenticationException exception) {
        if (exception instanceof InsufficientAuthenticationException || exception instanceof AuthenticationCredentialsNotFoundException) {
            return SecurityErrorCode.UNAUTHORIZED;
        }
        Optional<SecurityErrorCode> declared = declaredCode(exception);
        if (declared.isPresent()) {
            return declared.get();
        }
        JwtValidationException validation = findCause(exception, JwtValidationException.class);
        if (validation != null && onlyExpired(validation.getErrors())) {
            return SecurityErrorCode.TOKEN_EXPIRED;
        }
        if (exception instanceof AuthenticationServiceException) {
            return SecurityErrorCode.TOKEN_VERIFICATION_UNAVAILABLE;
        }
        return SecurityErrorCode.TOKEN_INVALID;
    }

    /** 권한 거부의 코드를 고른다. 아직 로그인하지 않았으면(익명) 001, 로그인했는데 역할이 모자라면 005 다. */
    public static SecurityErrorCode resolveAccessDenied(Authentication current) {
        return TRUST_RESOLVER.isAuthenticated(current) ? SecurityErrorCode.FORBIDDEN : SecurityErrorCode.UNAUTHORIZED;
    }

    private static Optional<SecurityErrorCode> declaredCode(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof OAuth2AuthenticationException oauth2) {
                String errorCode = oauth2.getError().getErrorCode();
                Optional<SecurityErrorCode> matched = Arrays.stream(SecurityErrorCode.values()).filter(code -> code.getCode().equals(errorCode)).findFirst();
                if (matched.isPresent()) {
                    return matched;
                }
            }
        }
        return Optional.empty();
    }

    private static boolean onlyExpired(Collection<OAuth2Error> errors) {
        return !errors.isEmpty() && errors.stream().allMatch(error -> SecurityErrorCode.TOKEN_EXPIRED.getCode().equals(error.getErrorCode()));
    }

    private static <T extends Throwable> T findCause(Throwable exception, Class<T> type) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        return null;
    }
}
