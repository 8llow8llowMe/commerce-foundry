package com.commercefoundry.security.resourceserver;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.error.SecurityErrorEnvelope;
import com.commercefoundry.security.error.SecurityErrorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 컨트롤러 메서드에서 난 보안 예외({@code @PreAuthorize} 거부 — {@code AuthorizationDeniedException})를 봉투로 바꾼다.
 *
 * <p>이 예외는 필터가 아니라 MVC 예외 처리로 먼저 간다. 서비스의 advice 가 {@code Exception} 을 통째로 잡으면 403 이
 * 500 이 되므로 가장 높은 우선순위로 둔다. {@link ResourceServerSecurityConfigurer} 가 빈으로 등록한다(컴포넌트 스캔 아님).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityExceptionAdvice {

    /** 아직 로그인하지 않았으면(익명) 001(401), 로그인했는데 역할이 모자라면 005(403). */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Response<Void>> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        Authentication current = SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication();
        SecurityErrorCode errorCode = SecurityErrorResolver.resolveAccessDenied(current);
        SecurityErrorResponder.logAccessDenied(request, errorCode, current);
        return toResponse(errorCode);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Response<Void>> handleAuthentication(AuthenticationException exception, HttpServletRequest request) {
        SecurityErrorCode errorCode = SecurityErrorResolver.resolve(exception);
        SecurityErrorResponder.logAuthenticationRejected(request, errorCode, exception);
        return toResponse(errorCode);
    }

    private static ResponseEntity<Response<Void>> toResponse(SecurityErrorCode errorCode) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(errorCode.getHttpStatus());
        SecurityErrorEnvelope.wwwAuthenticate(errorCode).ifPresent(value -> builder.header(HttpHeaders.WWW_AUTHENTICATE, value));
        return builder.body(Response.fail(errorCode.getCode(), errorCode.getMessage()));
    }
}
