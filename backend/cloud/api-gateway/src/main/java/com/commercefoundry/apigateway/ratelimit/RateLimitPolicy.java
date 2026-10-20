package com.commercefoundry.apigateway.ratelimit;

import com.commercefoundry.apigateway.properties.GatewayRateLimitProperties;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import lombok.Getter;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 회원 단위로 요청 수를 세는 고객 쓰기 API ({@code security-guide.md} §7-3). 요청 하나는 <b>선언 순서상 처음 맞는 정책 하나</b>에만 걸린다 —
 * 병합이 일반 장바구니 쓰기보다 앞이라 병합은 병합 한도만 쓴다. 정책마다 버킷이 따로다.
 *
 * <p>조회(GET)는 세지 않는다. 어드민 경로({@code /api/v1/admin/**})는 대상이 아니다. 로그인 · 회원가입 시도 제한은 auth-service 가 맡는다(#66).
 */
@Getter
public enum RateLimitPolicy {

    CART_MERGE("cart-merge", "/api/v1/carts/*/merge", Set.of(HttpMethod.POST), GatewayRateLimitProperties::cartMerge),
    CART_WRITE("cart-write", "/api/v1/carts/**", WriteMethods.ALL, GatewayRateLimitProperties::cartWrite),
    ORDER_WRITE("order-write", "/api/v1/orders/**", WriteMethods.ALL, GatewayRateLimitProperties::orderWrite);

    /** Redis 키의 일부다 ({@code request_rate_limiter.{<id>.<id>:member:<memberId>}.tokens}). 바꾸면 진행 중인 버킷이 새로 시작된다. */
    private final String id;
    private final PathPattern pathPattern;
    private final Set<HttpMethod> methods;
    private final Function<GatewayRateLimitProperties, GatewayRateLimitProperties.Limit> limitSelector;

    RateLimitPolicy(String id, String pathPattern, Set<HttpMethod> methods, Function<GatewayRateLimitProperties, GatewayRateLimitProperties.Limit> limitSelector) {
        this.id = id;
        this.pathPattern = PathPatternParser.defaultInstance.parse(pathPattern);
        this.methods = methods;
        this.limitSelector = limitSelector;
    }

    public GatewayRateLimitProperties.Limit limit(GatewayRateLimitProperties properties) {
        return limitSelector.apply(properties);
    }

    /** 경로는 라우트 매칭과 같은 {@code PathPattern} 으로 본다 — 비예약 문자 인코딩도 같은 경로다. */
    public static Optional<RateLimitPolicy> match(HttpMethod method, PathContainer path) {
        return Arrays.stream(values())
            .filter(policy -> policy.methods.contains(method) && policy.pathPattern.matches(path))
            .findFirst();
    }

    private static final class WriteMethods {
        static final Set<HttpMethod> ALL = Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);
    }
}
