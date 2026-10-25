package com.commercefoundry.security.resourceserver;

import com.commercefoundry.common.log.LogPathMask;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.error.SecurityErrorEnvelope;
import com.commercefoundry.security.error.SecurityErrorResolver;
import com.commercefoundry.security.internal.ServicePrincipal;
import com.commercefoundry.security.principal.MemberPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 필터 단계에서 끊긴 인증·인가 실패를 {@code Response.fail} 봉투로 쓴다 — 토큰 없음·만료·위조(401), 역할 부족(403).
 * 컨트롤러 메서드의 {@code @PreAuthorize} 거부는 {@link SecurityExceptionAdvice} 가 같은 코드로 답한다.
 *
 * <p>사용자 체인은 {@link #SecurityErrorResponder(SecurityErrorEnvelope)}(001~006), 서비스 간 인증 체인은
 * {@link #forServiceAuthentication}(인증 실패 007)을 쓴다. 권한 거부(005)는 둘이 같다.
 *
 * <p>로그에는 코드 · 경로 · 예외 타입만 남긴다. 토큰 원문과 예외 메시지(토큰 조각이 섞일 수 있다)는 남기지 않는다.
 * 경로는 앞 {@value LogPathMask#VISIBLE_PATH_SEGMENTS} 구간만 남기고 나머지를 가린다({@link LogPathMask}) — 주문번호 같은 식별자가 경로에 들어간다.
 */
@Slf4j
public final class SecurityErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final SecurityErrorEnvelope envelope;
    private final Function<AuthenticationException, SecurityErrorCode> authenticationFailureCode;
    private final boolean serviceAuthentication;

    public SecurityErrorResponder(SecurityErrorEnvelope envelope) {
        this(envelope, SecurityErrorResolver::resolve, false);
    }

    private SecurityErrorResponder(
        SecurityErrorEnvelope envelope, Function<AuthenticationException, SecurityErrorCode> authenticationFailureCode, boolean serviceAuthentication
    ) {
        this.envelope = envelope;
        this.authenticationFailureCode = authenticationFailureCode;
        this.serviceAuthentication = serviceAuthentication;
    }

    /**
     * 서비스 간 인증 체인({@code /internal/**})의 응답기. 인증 실패는 원인과 무관하게 007, 검증 중 시스템 오류만 006 이다
     * ({@link SecurityErrorResolver#resolveServiceAuthentication}).
     */
    public static SecurityErrorResponder forServiceAuthentication(SecurityErrorEnvelope envelope) {
        return new SecurityErrorResponder(envelope, SecurityErrorResolver::resolveServiceAuthentication, true);
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws IOException {
        SecurityErrorCode errorCode = authenticationFailureCode.apply(exception);
        if (serviceAuthentication) {
            logServiceAuthenticationRejected(request, errorCode, exception);
        } else {
            logAuthenticationRejected(request, errorCode, exception);
        }
        write(response, errorCode);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception) throws IOException {
        Authentication current = SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication();
        SecurityErrorCode errorCode = SecurityErrorResolver.resolveAccessDenied(current);
        logAccessDenied(request, errorCode, current);
        write(response, errorCode);
    }

    /**
     * 사용자 체인. 만료 토큰은 흔하다 — debug 로 둔다. 검증 시스템 오류(006)는 운영이 봐야 하므로 warn 이다(#4 의 검증 저장소 장애).
     * 예외 메시지에는 토큰 조각이 섞일 수 있어 타입만 남긴다.
     */
    static void logAuthenticationRejected(HttpServletRequest request, SecurityErrorCode errorCode, AuthenticationException exception) {
        if (errorCode == SecurityErrorCode.TOKEN_VERIFICATION_UNAVAILABLE) {
            log.warn("token verification unavailable. code={}, method={}, path={}, reason={}",
                errorCode.getCode(), request.getMethod(), maskedPath(request), reason(exception));
        } else {
            log.debug("authentication rejected. code={}, method={}, path={}, reason={}",
                errorCode.getCode(), request.getMethod(), maskedPath(request), reason(exception));
        }
    }

    /**
     * 서비스 간 인증 체인. 인증 실패(007)는 warn 이다 — 정상 배포에서는 일어나지 않고, 키 · 설정 실수나 사설망 안의 위조 요청을 드러낸다.
     * 006 은 error 다 — 서비스 토큰 검증은 메모리의 비밀만 쓰므로 저장소 장애가 있을 수 없다. 나면 서버 결함 신호다.
     */
    static void logServiceAuthenticationRejected(HttpServletRequest request, SecurityErrorCode errorCode, AuthenticationException exception) {
        if (errorCode == SecurityErrorCode.TOKEN_VERIFICATION_UNAVAILABLE) {
            log.error("service token verification failed by a server-side error. code={}, method={}, path={}, reason={}",
                errorCode.getCode(), request.getMethod(), maskedPath(request), reason(exception));
        } else {
            log.warn("service authentication rejected. code={}, method={}, path={}, reason={}",
                errorCode.getCode(), request.getMethod(), maskedPath(request), reason(exception));
        }
    }

    /**
     * 로그인한 회원의 권한 거부는 어드민 경로 탐색일 수 있어 info 로 memberId · jti 를 남긴다.
     * 서비스의 권한 거부(허용 목록 밖의 호출자)는 배포 · 설정 실수거나 침해 신호라 warn 으로 호출 서비스 · jti 를 남긴다.
     */
    static void logAccessDenied(HttpServletRequest request, SecurityErrorCode errorCode, Authentication current) {
        Object principal = current == null ? null : current.getPrincipal();
        if (principal instanceof MemberPrincipal member) {
            log.info("access denied. code={}, method={}, path={}, memberId={}, jti={}",
                errorCode.getCode(), request.getMethod(), maskedPath(request), member.memberId(), member.tokenId());
        } else if (principal instanceof ServicePrincipal service) {
            log.warn("service access denied. code={}, method={}, path={}, callerService={}, jti={}",
                errorCode.getCode(), request.getMethod(), maskedPath(request), service.serviceId(), service.tokenId());
        } else {
            log.debug("access denied. code={}, method={}, path={}", errorCode.getCode(), request.getMethod(), maskedPath(request));
        }
    }

    /** {@link LogPathMask} 로 가린다. 쿼리 문자열은 원래 싣지 않는다({@code getRequestURI}). */
    static String maskPath(String path) {
        return LogPathMask.mask(path);
    }

    private static String maskedPath(HttpServletRequest request) {
        return maskPath(request.getRequestURI());
    }

    private static String reason(AuthenticationException exception) {
        return exception.getCause() == null
            ? exception.getClass().getSimpleName()
            : exception.getClass().getSimpleName() + "<-" + exception.getCause().getClass().getSimpleName();
    }

    private void write(HttpServletResponse response, SecurityErrorCode errorCode) throws IOException {
        if (response.isCommitted()) {
            log.warn("security error response skipped since response is committed. code={}", errorCode.getCode());
            return;
        }
        byte[] body = envelope.body(errorCode);
        response.setStatus(errorCode.getHttpStatus().value());
        SecurityErrorEnvelope.wwwAuthenticate(errorCode).ifPresent(value -> response.setHeader(HttpHeaders.WWW_AUTHENTICATE, value));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
