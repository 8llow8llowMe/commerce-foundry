package com.commercefoundry.apigateway.ratelimit;

import com.commercefoundry.apigateway.properties.GatewayRateLimitProperties;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.Getter;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 회원 · 클라이언트 IP 단위로 요청 수를 세는 고객 쓰기 API ({@code security-guide.md} §7-3). 요청 하나는 <b>선언 순서상 처음 맞는 정책 하나</b>에만 걸린다 —
 * 병합이 일반 장바구니 쓰기보다, 주문서 미리보기가 일반 주문 쓰기보다 앞이라 각자의 한도만 쓴다. 정책마다 버킷이 따로다.
 *
 * <p>조회(GET)는 세지 않는다. 어드민 경로({@code /api/v1/admin/**})는 대상이 아니다. 로그인 · 회원가입 시도 제한은 auth-service 가 맡는다(#66).
 *
 * <p>{@link #mandatory()} 정책(상담 · 방문 분석)은 보호 없이 하류로 보내지 않는다 — 설정 부재 · IP 해석 꺼짐 · 주체 불명 · 저장소 장애면 503 이다. 그 밖의 정책은 fail-open 이다.
 */
@Getter
public enum RateLimitPolicy {

    // 비회원 상담 자격 발급(#470 B2b-1). support-write catch-all 보다 앞이라 발급만 이 한도로 센다
    SUPPORT_GUEST_SESSION("support-guest-session", "/api/v1/support/guest-sessions", Set.of(HttpMethod.POST), true,
        GatewayRateLimitProperties::supportGuestSession),
    // 그 밖의 상담 POST 전부 — 메시지 저장과 앞으로 생기는 상담 POST(대화 생성 등)도 보호 없이 열리지 않게 넓게 잡는다
    SUPPORT_WRITE("support-write", "/api/v1/support/**", Set.of(HttpMethod.POST), true, GatewayRateLimitProperties::supportWrite),
    // 방문 분석 수집(#226) — 회원이어도 클라이언트 IP 버킷으로 센다
    ANALYTICS_EVENTS("analytics-events", "/api/v1/analytics/events", Set.of(HttpMethod.POST), true, AnalyticsLimit::select),
    CART_MERGE("cart-merge", "/api/v1/carts/*/merge", Set.of(HttpMethod.POST), false, GatewayRateLimitProperties::cartMerge),
    CART_WRITE("cart-write", "/api/v1/carts/**", WriteMethods.ALL, false, GatewayRateLimitProperties::cartWrite),
    // 주문서 미리보기는 수량 · 배송지를 바꿀 때마다 나간다 — 주문 생성과 한도를 나누지 않으면 몇 번 바꾼 뒤 "주문하기" 가 429 를 받는다 (#158)
    ORDER_QUOTE("order-quote", "/api/v1/orders/quotes", Set.of(HttpMethod.POST), false, GatewayRateLimitProperties::orderQuote),
    ORDER_WRITE("order-write", "/api/v1/orders/**", WriteMethods.ALL, false, GatewayRateLimitProperties::orderWrite),
    PAYMENT_WRITE("payment-write", "/api/v1/payments/**", WriteMethods.ALL, false, GatewayRateLimitProperties::paymentWrite);

    private static final PathPattern PAYMENT_WEBHOOKS = PathPatternParser.defaultInstance.parse("/api/v1/payments/webhooks/**");

    /** Redis 키의 일부다 ({@code request_rate_limiter.{<id>.<id>:member:<memberId>}.tokens} · {@code ...{<id>.<id>:ip:<HMAC>}...}). 바꾸면 진행 중인 버킷이 새로 시작된다. */
    private final String id;
    private final PathPattern pathPattern;
    private final Set<HttpMethod> methods;
    @Getter(AccessLevel.NONE)
    private final boolean mandatory;
    private final Function<GatewayRateLimitProperties, GatewayRateLimitProperties.Limit> limitSelector;

    RateLimitPolicy(
        String id, String pathPattern, Set<HttpMethod> methods, boolean mandatory,
        Function<GatewayRateLimitProperties, GatewayRateLimitProperties.Limit> limitSelector
    ) {
        this.id = id;
        this.pathPattern = PathPatternParser.defaultInstance.parse(pathPattern);
        this.methods = methods;
        this.mandatory = mandatory;
        this.limitSelector = limitSelector;
    }

    public GatewayRateLimitProperties.Limit limit(GatewayRateLimitProperties properties) {
        return limitSelector.apply(properties);
    }

    /** 필수 보호 — 세지 못하면(전역 끔 · IP 해석 끔 · 주체 불명 · 저장소 오류/타임아웃) 하류로 보내지 않고 503 이다. false 면 fail-open. */
    public boolean mandatory() {
        return mandatory;
    }

    /** 경로는 라우트 매칭과 같은 {@code PathPattern} 으로 본다 — 비예약 문자 인코딩도 같은 경로다. */
    public static Optional<RateLimitPolicy> match(HttpMethod method, PathContainer path) {
        return Arrays.stream(values())
            .filter(policy -> policy.methods.contains(method) && policy.pathPattern.matches(path)
                && (policy != PAYMENT_WRITE || !PAYMENT_WEBHOOKS.matches(path)))
            .findFirst();
    }

    private static final class AnalyticsLimit {
        private static final GatewayRateLimitProperties.Limit LIMIT = new GatewayRateLimitProperties.Limit(60, 10);

        private static GatewayRateLimitProperties.Limit select(GatewayRateLimitProperties properties) {
            return LIMIT;
        }
    }

    private static final class WriteMethods {
        static final Set<HttpMethod> ALL = Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);
    }
}
