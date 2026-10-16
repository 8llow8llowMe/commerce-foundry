package com.commercefoundry.security.resourceserver;

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
 */
@Slf4j
public final class SecurityErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final SecurityErrorEnvelope envelope;
    private final Function<AuthenticationException, SecurityErrorCode> authenticationFailureCode;

    public SecurityErrorResponder(SecurityErrorEnvelope envelope) {
        this(envelope, SecurityErrorResolver::resolve);
    }

    private SecurityErrorResponder(SecurityErrorEnvelope envelope, Function<AuthenticationException, SecurityErrorCode> authenticationFailureCode) {
        this.envelope = envelope;
        this.authenticationFailureCode = authenticationFailureCode;
    }

    /**
     * 서비스 간 인증 체인({@code /internal/**})의 응답기. 인증 실패는 원인과 무관하게 007, 검증 중 시스템 오류만 006 이다
     * ({@link SecurityErrorResolver#resolveServiceAuthentication}).
     */
    public static SecurityErrorResponder forServiceAuthentication(SecurityErrorEnvelope envelope) {
        return new SecurityErrorResponder(envelope, SecurityErrorResolver::resolveServiceAuthentication);
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws IOException {
        SecurityErrorCode errorCode = authenticationFailureCode.apply(exception);
        logAuthenticationRejected(request, errorCode, exception);
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
     * 만료 토큰은 흔하다 — debug 로 둔다. 검증 시스템 오류(006)는 운영이 봐야 하므로 warn 이다.
     * 서비스 인증 실패(007)도 warn 이다 — 정상 배포에서는 일어나지 않고, 키 · 설정 실수나 사설망 안의 위조 요청을 드러낸다.
     * 예외 메시지에는 토큰 조각이 섞일 수 있어 타입만 남긴다.
     */
    static void logAuthenticationRejected(HttpServletRequest request, SecurityErrorCode errorCode, AuthenticationException exception) {
        String reason = exception.getCause() == null
            ? exception.getClass().getSimpleName()
            : exception.getClass().getSimpleName() + "<-" + exception.getCause().getClass().getSimpleName();
        switch (errorCode) {
            case TOKEN_VERIFICATION_UNAVAILABLE ->
                log.warn("token verification unavailable. code={}, method={}, path={}, reason={}", errorCode.getCode(), request.getMethod(), request.getRequestURI(), reason);
            case SERVICE_UNAUTHORIZED ->
                log.warn("service authentication rejected. code={}, method={}, path={}, reason={}", errorCode.getCode(), request.getMethod(), request.getRequestURI(), reason);
            default ->
                log.debug("authentication rejected. code={}, method={}, path={}, reason={}", errorCode.getCode(), request.getMethod(), request.getRequestURI(), reason);
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
                errorCode.getCode(), request.getMethod(), request.getRequestURI(), member.memberId(), member.tokenId());
        } else if (principal instanceof ServicePrincipal service) {
            log.warn("service access denied. code={}, method={}, path={}, callerService={}, jti={}",
                errorCode.getCode(), request.getMethod(), request.getRequestURI(), service.serviceId(), service.tokenId());
        } else {
            log.debug("access denied. code={}, method={}, path={}", errorCode.getCode(), request.getMethod(), request.getRequestURI());
        }
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
