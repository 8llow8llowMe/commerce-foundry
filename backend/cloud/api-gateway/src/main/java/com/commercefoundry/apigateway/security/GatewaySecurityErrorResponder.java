package com.commercefoundry.apigateway.security;

import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.error.SecurityErrorEnvelope;
import com.commercefoundry.security.error.SecurityErrorResolver;
import com.commercefoundry.security.principal.MemberPrincipal;
import java.util.Arrays;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 게이트웨이의 JWT 거부 응답 — 토큰 없음 · 만료 · 위조 · 로그아웃(401), 역할 부족(403), 차단 목록 장애(503).
 * 서비스의 {@code SecurityErrorResponder} 와 같은 {@link SecurityErrorResolver} · {@link SecurityErrorEnvelope} 로 <b>같은 코드 · 같은 바이트 ·
 * 같은 {@code WWW-Authenticate}</b> 를 쓴다 ({@code api-design-guide.md} §2-1). {@code GATEWAY_*} 는 라우팅 오류에만 쓴다.
 *
 * <p>로그 수준 · 마스킹도 서비스와 같다 — 코드 · 메서드 · 앞 {@value #VISIBLE_PATH_SEGMENTS} 구간 경로 · 예외 타입만,
 * 로그인한 회원의 권한 거부는 memberId · jti 를 더한다. 토큰 원문과 예외 메시지(토큰 조각이 섞일 수 있다)는 남기지 않는다.
 */
@Slf4j
public final class GatewaySecurityErrorResponder implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    /** {@code SecurityErrorResponder.VISIBLE_PATH_SEGMENTS} 와 같은 값이다. 서블릿 패키지라 그 클래스를 가져오지 않는다. */
    static final int VISIBLE_PATH_SEGMENTS = 3;
    private static final String MASKED_REST = "/…";

    private final SecurityErrorEnvelope envelope;

    public GatewaySecurityErrorResponder(SecurityErrorEnvelope envelope) {
        this.envelope = envelope;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException exception) {
        SecurityErrorCode errorCode = SecurityErrorResolver.resolve(exception);
        logAuthenticationRejected(exchange.getRequest(), errorCode, exception);
        return write(exchange.getResponse(), errorCode);
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .flatMap(current -> {
                SecurityErrorCode errorCode = SecurityErrorResolver.resolveAccessDenied(current.orElse(null));
                logAccessDenied(exchange.getRequest(), errorCode, current.orElse(null));
                return write(exchange.getResponse(), errorCode);
            });
    }

    /** 만료 토큰은 흔하다 — debug. 차단 목록 장애(006)는 운영이 봐야 하므로 warn 이다. */
    private static void logAuthenticationRejected(ServerHttpRequest request, SecurityErrorCode errorCode, AuthenticationException exception) {
        if (errorCode == SecurityErrorCode.TOKEN_VERIFICATION_UNAVAILABLE) {
            log.warn("token verification unavailable. code={}, method={}, path={}, reason={}",
                errorCode.getCode(), request.getMethod(), maskedPath(request), reason(exception));
        } else {
            log.debug("authentication rejected. code={}, method={}, path={}, reason={}",
                errorCode.getCode(), request.getMethod(), maskedPath(request), reason(exception));
        }
    }

    /** 로그인한 회원의 권한 거부는 어드민 경로 탐색일 수 있어 info 로 memberId · jti 를 남긴다. */
    private static void logAccessDenied(ServerHttpRequest request, SecurityErrorCode errorCode, Authentication current) {
        Object principal = current == null ? null : current.getPrincipal();
        if (principal instanceof MemberPrincipal member) {
            log.info("access denied. code={}, method={}, path={}, memberId={}, jti={}",
                errorCode.getCode(), request.getMethod(), maskedPath(request), member.memberId(), member.tokenId());
        } else {
            log.debug("access denied. code={}, method={}, path={}", errorCode.getCode(), request.getMethod(), maskedPath(request));
        }
    }

    /**
     * 앞 {@value #VISIBLE_PATH_SEGMENTS} 구간만 남기고 나머지를 {@code /…} 로 가린다 — 서비스 {@code SecurityErrorResponder#maskPath} 와 같은 규칙.
     * {@code /api/v1/orders/4611686018427387904} → {@code /api/v1/orders/…}. 쿼리 문자열은 원래 싣지 않는다.
     */
    static String maskPath(String path) {
        if (path == null) {
            return null;
        }
        String[] parts = path.split("/", VISIBLE_PATH_SEGMENTS + 2);
        if (parts.length < VISIBLE_PATH_SEGMENTS + 2 || parts[VISIBLE_PATH_SEGMENTS + 1].isEmpty()) {
            return path;
        }
        return String.join("/", Arrays.copyOf(parts, VISIBLE_PATH_SEGMENTS + 1)) + MASKED_REST;
    }

    private static String maskedPath(ServerHttpRequest request) {
        return maskPath(request.getPath().value());
    }

    private static String reason(AuthenticationException exception) {
        return exception.getCause() == null
            ? exception.getClass().getSimpleName()
            : exception.getClass().getSimpleName() + "<-" + exception.getCause().getClass().getSimpleName();
    }

    private Mono<Void> write(ServerHttpResponse response, SecurityErrorCode errorCode) {
        if (response.isCommitted()) {
            log.warn("security error response skipped since response is committed. code={}", errorCode.getCode());
            return Mono.empty();
        }
        byte[] body = envelope.body(errorCode);
        response.setStatusCode(errorCode.getHttpStatus());
        SecurityErrorEnvelope.wwwAuthenticate(errorCode).ifPresent(value -> response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, value));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        // 보안 헤더(headers)를 꺼 두었으므로 게이트웨이가 직접 쓰는 이 응답에만 붙인다. 프록시한 서비스 응답은 서비스가 정한 값 그대로다.
        // 인증 상태에 따라 갈리는 응답이라 중간 캐시 · 브라우저가 저장해 다른 요청에 돌려주지 않게 한다
        response.getHeaders().setCacheControl(CacheControl.noStore());
        response.getHeaders().setContentLength(body.length);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
