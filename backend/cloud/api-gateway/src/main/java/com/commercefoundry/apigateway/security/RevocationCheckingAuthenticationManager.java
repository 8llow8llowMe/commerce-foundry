package com.commercefoundry.apigateway.security;

import com.commercefoundry.common.log.LogPathMask;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 서명 · 만료 검증(위임 매니저)을 통과한 토큰이 로그아웃으로 차단됐는지 본다 ({@code security-guide.md} §7).
 *
 * <ul>
 *   <li>차단됨 → {@code OAuth2AuthenticationException(SECURITY_004)} — 리졸버가 원인 사슬의 004 를 따른다 (§4-1)</li>
 *   <li>차단 목록 저장소 오류 · 타임아웃 → {@link FailurePolicy} 대로. 어드민 경로는 fail-closed(006, 503),
 *       그 밖은 fail-open(통과) — 사용자 확정 결정 (#4 결정 2)</li>
 * </ul>
 *
 * <p>validator 에 넣지 않는다 — validator 의 오류는 {@code JwtValidationException} 안에 들어가 003 이 된다.
 * 디코더 · 변환기는 두 정책이 같은 인스턴스를 나눠 쓴다. 요청마다 새로 만드는 것은 경로 로그용 람다 하나뿐이다.
 */
@Slf4j
public final class RevocationCheckingAuthenticationManager {

    /**
     * 차단 목록 조회의 상한. Lettuce 명령 타임아웃({@code spring.data.redis.timeout}, 1s)이 먼저 끊는다.
     * 이 값은 그 설정이 빠지거나 커졌을 때 요청이 붙잡히지 않게 하는 뒤쪽 방어다.
     */
    static final Duration CHECK_TIMEOUT = Duration.ofSeconds(2);

    /**
     * fail-open 통과 로그의 warn 간격. 장애 동안 토큰이 붙은 요청마다 warn 을 쓰면 로그가 폭주한다 —
     * 간격마다 한 번만 warn 으로 알리고 나머지는 debug 로 둔다. 장애가 계속된다는 신호는 warn 이 간격마다 이어지는 것으로 충분하다.
     */
    static final Duration FAIL_OPEN_WARN_INTERVAL = Duration.ofMinutes(1);

    public enum FailurePolicy {
        /** 저장소 장애 시 통과시킨다. 장애 동안 로그아웃한 access 토큰은 만료까지 유효하다 — {@code access-token-ttl} + {@code clock-skew} (기본 31분, 상한 61분). */
        FAIL_OPEN,
        /** 저장소 장애 시 503({@code SECURITY_006})으로 끊는다. */
        FAIL_CLOSED
    }

    private final ReactiveAuthenticationManager tokenAuthenticationManager;
    private final AccessTokenRevocationChecker revocationChecker;
    private final FailurePolicy failurePolicy;
    private final Clock clock;
    private final AtomicLong nextWarnAtMillis = new AtomicLong(Long.MIN_VALUE);

    public RevocationCheckingAuthenticationManager(
        ReactiveAuthenticationManager tokenAuthenticationManager, AccessTokenRevocationChecker revocationChecker, FailurePolicy failurePolicy, Clock clock
    ) {
        this.tokenAuthenticationManager = tokenAuthenticationManager;
        this.revocationChecker = revocationChecker;
        this.failurePolicy = failurePolicy;
        this.clock = clock;
    }

    /** 이 요청의 매니저. 거부 · 통과 로그에 이 요청의 메서드 · 마스킹 경로를 남긴다. */
    public ReactiveAuthenticationManager forExchange(ServerWebExchange exchange) {
        return authentication -> tokenAuthenticationManager.authenticate(authentication)
            .flatMap(authenticated -> rejectIfRevoked(authenticated, exchange));
    }

    private Mono<Authentication> rejectIfRevoked(Authentication authenticated, ServerWebExchange exchange) {
        if (!(authenticated.getPrincipal() instanceof MemberPrincipal member)) {
            // MemberJwtAuthenticationConverter 가 항상 MemberPrincipal 을 만든다. 어긋나면 통과시키지 않는다
            return Mono.error(new InvalidBearerTokenException("access token principal is not a member"));
        }
        String jti = member.tokenId();
        return lookup(jti)
            .single()
            .timeout(CHECK_TIMEOUT)
            .onErrorResume(failure -> !(failure instanceof AuthenticationException), failure -> onStorageFailure(jti, exchange, failure))
            .flatMap(revoked -> revoked
                ? Mono.error(new OAuth2AuthenticationException(new OAuth2Error(SecurityErrorCode.TOKEN_REVOKED.getCode())))
                : Mono.just(authenticated));
    }

    /** 키 규칙에 맞지 않는 jti 는 저장소 장애가 아니라 잘못된 토큰(003)이다 — fail-open 으로 통과시키지 않는다. */
    private Mono<Boolean> lookup(String jti) {
        try {
            return revocationChecker.isRevoked(jti);
        } catch (IllegalArgumentException malformed) {
            return Mono.error(new InvalidBearerTokenException("access token id is malformed", malformed));
        } catch (RuntimeException failure) {
            return Mono.error(failure);
        }
    }

    private Mono<Boolean> onStorageFailure(String jti, ServerWebExchange exchange, Throwable failure) {
        if (failurePolicy == FailurePolicy.FAIL_CLOSED) {
            // 응답 로그(006, warn)는 진입점이 남긴다
            return Mono.error(new OAuth2AuthenticationException(new OAuth2Error(SecurityErrorCode.TOKEN_VERIFICATION_UNAVAILABLE.getCode()), failure));
        }
        logFailOpen(jti, exchange, failure);
        return Mono.just(false);
    }

    /** 토큰 원문 · 예외 메시지는 남기지 않는다 — jti · 마스킹 경로 · 예외 타입만. */
    private void logFailOpen(String jti, ServerWebExchange exchange, Throwable failure) {
        String method = exchange.getRequest().getMethod().name();
        String path = LogPathMask.mask(exchange.getRequest().getPath().value());
        String reason = failure.getClass().getSimpleName();
        if (claimWarnSlot()) {
            log.warn("revocation list unavailable, token passed (fail-open). jti={}, method={}, path={}, reason={}, nextWarnIn={}",
                jti, method, path, reason, FAIL_OPEN_WARN_INTERVAL);
        } else {
            log.debug("revocation list unavailable, token passed (fail-open). jti={}, method={}, path={}, reason={}", jti, method, path, reason);
        }
    }

    /** 간격마다 한 요청만 warn 자리를 얻는다. 동시에 들어온 요청은 CAS 로 하나만 이긴다. */
    boolean claimWarnSlot() {
        long now = clock.millis();
        long next = nextWarnAtMillis.get();
        return now >= next && nextWarnAtMillis.compareAndSet(next, now + FAIL_OPEN_WARN_INTERVAL.toMillis());
    }
}
