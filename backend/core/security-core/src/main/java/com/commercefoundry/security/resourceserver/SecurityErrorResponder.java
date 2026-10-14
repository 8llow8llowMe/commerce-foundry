package com.commercefoundry.security.resourceserver;

import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.error.SecurityErrorEnvelope;
import com.commercefoundry.security.error.SecurityErrorResolver;
import com.commercefoundry.security.principal.MemberPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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
 * <p>로그에는 코드 · 경로 · 예외 타입만 남긴다. 토큰 원문과 예외 메시지(토큰 조각이 섞일 수 있다)는 남기지 않는다.
 */
@Slf4j
public final class SecurityErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final SecurityErrorEnvelope envelope;

    public SecurityErrorResponder(SecurityErrorEnvelope envelope) {
        this.envelope = envelope;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws IOException {
        SecurityErrorCode errorCode = SecurityErrorResolver.resolve(exception);
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

    /** 만료 토큰은 흔하다 — debug 로 둔다. 예외 메시지에는 토큰 조각이 섞일 수 있어 타입만 남긴다. */
    static void logAuthenticationRejected(HttpServletRequest request, SecurityErrorCode errorCode, AuthenticationException exception) {
        log.debug("authentication rejected. code={}, method={}, path={}, reason={}",
            errorCode.getCode(), request.getMethod(), request.getRequestURI(), exception.getClass().getSimpleName());
    }

    /** 로그인한 회원의 권한 거부는 어드민 경로 탐색일 수 있어 info 로 memberId · jti 를 남긴다. */
    static void logAccessDenied(HttpServletRequest request, SecurityErrorCode errorCode, Authentication current) {
        if (current != null && current.getPrincipal() instanceof MemberPrincipal member) {
            log.info("access denied. code={}, method={}, path={}, memberId={}, jti={}",
                errorCode.getCode(), request.getMethod(), request.getRequestURI(), member.memberId(), member.tokenId());
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
