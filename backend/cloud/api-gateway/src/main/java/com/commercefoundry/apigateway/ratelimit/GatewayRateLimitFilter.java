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
 * 고객 쓰기 API 를 주체 단위로 센다 ({@code security-guide.md} §7-3, 정책 {@link RateLimitPolicy}). 한도를 넘으면 하류로 보내지 않고
 * 429 {@code GATEWAY_006} + {@code Retry-After} 로 끊는다.
 *
 * <ul>
 *   <li><b>라우트 전역 필터(GlobalFilter)다.</b> 라우트에 걸린 요청만 오고, 보안 체인(토큰 검증 · 차단 목록 · 어드민 인가)이 끝난 뒤라 검증된 회원 id 를 쓴다.</li>
 *   <li><b>로그인한 요청은 회원 id, 토큰 없는 요청은 클라이언트 IP</b>({@link ClientIpResolver} — 신뢰 프록시만 {@code X-Forwarded-For} 를 믿는다)의 HMAC 으로 센다.
 *       같은 정책 한도를 쓰되 버킷이 따로다. 원격 주소조차 모르면 세지 않는다.</li>
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

    private final BucketRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final ClientIpHasher clientIpHasher;
    private final GatewayRateLimitProperties properties;
    private final GatewayErrorResponseWriter errorResponseWriter;
    private final Clock clock;
    private final AtomicLong nextWarnAtMillis = new AtomicLong(Long.MIN_VALUE);

    @Autowired
    public GatewayRateLimitFilter(
        BucketRateLimiter rateLimiter, ClientIpResolver clientIpResolver, ClientIpHasher clientIpHasher,
        GatewayRateLimitProperties properties, GatewayErrorResponseWriter errorResponseWriter
    ) {
        this(rateLimiter, clientIpResolver, clientIpHasher, properties, errorResponseWriter, Clock.systemUTC());
    }

    GatewayRateLimitFilter(
        BucketRateLimiter rateLimiter, ClientIpResolver clientIpResolver, ClientIpHasher clientIpHasher,
        GatewayRateLimitProperties properties, GatewayErrorResponseWriter errorResponseWriter, Clock clock
    ) {
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.clientIpHasher = clientIpHasher;
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
            .map(member -> RateLimitSubject.member(member.memberId()))
            .switchIfEmpty(Mono.defer(() -> Mono.justOrEmpty(clientSubject(exchange))))
            .flatMap(subject -> acquire(policy.get(), subject, exchange))
            .defaultIfEmpty(true)
            .flatMap(allowed -> allowed ? chain.filter(exchange) : reject(policy.get(), exchange));
    }

    /** 토큰 없는 요청의 주체 — 클라이언트 IP 의 HMAC. IP 원문은 이 메서드 밖으로 나가지 않는다(키 · 로그 모두). */
    private Optional<RateLimitSubject> clientSubject(ServerWebExchange exchange) {
        Optional<RateLimitSubject> subject = clientIpResolver.resolve(exchange.getRequest())
            .map(ClientIpResolver::bucketOf)
            .map(clientIpHasher::hash)
            .map(RateLimitSubject::client);
        if (subject.isEmpty()) {
            log.debug("gateway rate limit skipped since client address is unknown. method={}", exchange.getRequest().getMethod());
        }
        return subject;
    }

    private Mono<Boolean> acquire(RateLimitPolicy policy, RateLimitSubject subject, ServerWebExchange exchange) {
        return Mono.defer(() -> rateLimiter.tryAcquire(policy, subject))
            .single()
            .timeout(CHECK_TIMEOUT)
            .onErrorResume(failure -> {
                logFailOpen(policy, subject, exchange, failure);
                return Mono.just(true);
            })
            .doOnNext(allowed -> {
                if (!allowed) {
                    // 주체는 내부 회원 id 또는 IP 의 HMAC 이라 남긴다. 남용 추적 신호지만 요청마다 쌓이므로 debug 다
                    log.debug("gateway rate limited. policy={}, subject={}, method={}", policy.getId(), subject.value(), exchange.getRequest().getMethod());
                }
            });
    }

    /** {@code Retry-After} 는 쓰기가 시작되기 전에 넣는다. CORS 헤더는 보안 체인의 CORS 필터가 이미 실었다. */
    private Mono<Void> reject(RateLimitPolicy policy, ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(policy.limit(properties).retryAfterSeconds()));
        return errorResponseWriter.write(exchange.getResponse(), GatewayErrorCode.TOO_MANY_REQUESTS.getHttpStatus(), GatewayErrorCode.TOO_MANY_REQUESTS);
    }

    /** 예외 메시지는 남기지 않는다 — 정책 · 주체(회원 id 또는 IP HMAC) · 메서드 · 예외 타입만. */
    private void logFailOpen(RateLimitPolicy policy, RateLimitSubject subject, ServerWebExchange exchange, Throwable failure) {
        String reason = failure.getClass().getSimpleName();
        if (claimWarnSlot()) {
            log.warn("rate limit storage unavailable, request passed (fail-open). policy={}, subject={}, method={}, reason={}, nextWarnIn={}",
                policy.getId(), subject.value(), exchange.getRequest().getMethod(), reason, FAIL_OPEN_WARN_INTERVAL);
        } else {
            log.debug("rate limit storage unavailable, request passed (fail-open). policy={}, subject={}, reason={}", policy.getId(), subject.value(), reason);
        }
    }

    /** 간격마다 한 요청만 warn 자리를 얻는다. */
    boolean claimWarnSlot() {
        long now = clock.millis();
        long next = nextWarnAtMillis.get();
        return now >= next && nextWarnAtMillis.compareAndSet(next, now + FAIL_OPEN_WARN_INTERVAL.toMillis());
    }
}
