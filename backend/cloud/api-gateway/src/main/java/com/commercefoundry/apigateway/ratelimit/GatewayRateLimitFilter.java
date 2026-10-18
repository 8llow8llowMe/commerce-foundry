package com.commercefoundry.apigateway.ratelimit;

import com.commercefoundry.apigateway.exception.GatewayErrorCode;
import com.commercefoundry.apigateway.handler.GatewayErrorResponseWriter;
import com.commercefoundry.apigateway.properties.GatewayRateLimitProperties;
import com.commercefoundry.security.principal.MemberPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 고객 쓰기 API 를 회원 단위로 센다 ({@code security-guide.md} §7-3, 정책 {@link RateLimitPolicy}). 한도를 넘으면 하류로 보내지 않고
 * 429 {@code GATEWAY_006} + {@code Retry-After} 로 끊는다.
 *
 * <ul>
 *   <li><b>라우트 전역 필터(GlobalFilter)다.</b> 라우트에 걸린 요청만 오고, 보안 체인(토큰 검증 · 차단 목록 · 어드민 인가)이 끝난 뒤라 검증된 회원 id 를 쓴다.</li>
 *   <li><b>토큰 없는 요청은 세지 않는다.</b> 대상 API 가 모두 로그인 필수라 서비스가 401 로 끊는다(쓰기 없음). IP 단위 제한은 신뢰할 프록시 목록과 함께 후속 이슈에서 정한다.</li>
 *   <li><b>저장소 장애 · 응답 없음이면 통과(fail-open)한다.</b> 제한은 남용 완화 수단이라 장애가 쇼핑 장애가 되지 않게 한다 — 차단 목록 일반 경로와 같은 판단(§7-1).
 *       warn 은 간격마다 한 번만 남긴다.</li>
 * </ul>
 */
@Slf4j
@Component
public class GatewayRateLimitFilter implements GlobalFilter, Ordered {

    /** 제한 조회의 상한. Lettuce 명령 타임아웃(1s)이 먼저 끊고, 이 값은 그 설정이 빠지거나 커졌을 때의 뒤쪽 방어다 (차단 목록과 같은 값). */
    static final Duration CHECK_TIMEOUT = Duration.ofSeconds(2);
    static final Duration FAIL_OPEN_WARN_INTERVAL = Duration.ofMinutes(1);

    private final MemberRateLimiter rateLimiter;
    private final GatewayRateLimitProperties properties;
    private final GatewayErrorResponseWriter errorResponseWriter;
    private final Clock clock;
    private final AtomicLong nextWarnAtMillis = new AtomicLong(Long.MIN_VALUE);

    @Autowired
    public GatewayRateLimitFilter(MemberRateLimiter rateLimiter, GatewayRateLimitProperties properties, GatewayErrorResponseWriter errorResponseWriter) {
        this(rateLimiter, properties, errorResponseWriter, Clock.systemUTC());
    }

    GatewayRateLimitFilter(MemberRateLimiter rateLimiter, GatewayRateLimitProperties properties, GatewayErrorResponseWriter errorResponseWriter, Clock clock) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.errorResponseWriter = errorResponseWriter;
        this.clock = clock;
    }

    /** 라우팅(Netty 전송, 가장 낮은 우선순위)보다 한참 앞이다. 다른 전역 필터가 하류 요청을 꾸미기 전에 끊는다. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.enabled()) {
            return chain.filter(exchange);
        }
        Optional<RateLimitPolicy> policy = RateLimitPolicy.match(exchange.getRequest().getMethod(), exchange.getRequest().getPath().pathWithinApplication());
        if (policy.isEmpty()) {
            return chain.filter(exchange);
        }
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .map(Authentication::getPrincipal)
            .ofType(MemberPrincipal.class)
            .flatMap(member -> acquire(policy.get(), member, exchange))
            .defaultIfEmpty(true)
            .flatMap(allowed -> allowed ? chain.filter(exchange) : reject(policy.get(), exchange));
    }

    private Mono<Boolean> acquire(RateLimitPolicy policy, MemberPrincipal member, ServerWebExchange exchange) {
        return Mono.defer(() -> rateLimiter.tryAcquire(policy, member.memberId()))
            .single()
            .timeout(CHECK_TIMEOUT)
            .onErrorResume(failure -> {
                logFailOpen(policy, member, exchange, failure);
                return Mono.just(true);
            })
            .doOnNext(allowed -> {
                if (!allowed) {
                    // 회원 id 는 내부 id 라 남긴다. 남용 추적 신호지만 요청마다 쌓이므로 debug 다
                    log.debug("gateway rate limited. policy={}, memberId={}, method={}", policy.getId(), member.memberId(), exchange.getRequest().getMethod());
                }
            });
    }

    /** {@code Retry-After} 는 쓰기가 시작되기 전에 넣는다. CORS 헤더는 보안 체인의 CORS 필터가 이미 실었다. */
    private Mono<Void> reject(RateLimitPolicy policy, ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(policy.limit(properties).retryAfterSeconds()));
        return errorResponseWriter.write(exchange.getResponse(), GatewayErrorCode.TOO_MANY_REQUESTS.getHttpStatus(), GatewayErrorCode.TOO_MANY_REQUESTS);
    }

    /** 예외 메시지는 남기지 않는다 — 정책 · 회원 id · 메서드 · 예외 타입만. */
    private void logFailOpen(RateLimitPolicy policy, MemberPrincipal member, ServerWebExchange exchange, Throwable failure) {
        String reason = failure.getClass().getSimpleName();
        if (claimWarnSlot()) {
            log.warn("rate limit storage unavailable, request passed (fail-open). policy={}, memberId={}, method={}, reason={}, nextWarnIn={}",
                policy.getId(), member.memberId(), exchange.getRequest().getMethod(), reason, FAIL_OPEN_WARN_INTERVAL);
        } else {
            log.debug("rate limit storage unavailable, request passed (fail-open). policy={}, memberId={}, reason={}", policy.getId(), member.memberId(), reason);
        }
    }

    /** 간격마다 한 요청만 warn 자리를 얻는다. */
    boolean claimWarnSlot() {
        long now = clock.millis();
        long next = nextWarnAtMillis.get();
        return now >= next && nextWarnAtMillis.compareAndSet(next, now + FAIL_OPEN_WARN_INTERVAL.toMillis());
    }
}
