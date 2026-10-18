package com.commercefoundry.apigateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;

class RateLimitPolicyTest {

    @ParameterizedTest
    @CsvSource({
        "POST,   /api/v1/carts/me/merge,     CART_MERGE",
        "POST,   /api/v1/carts/me/items,     CART_WRITE",
        "PATCH,  /api/v1/carts/me/items/1,   CART_WRITE",
        "DELETE, /api/v1/carts/me/items/1,   CART_WRITE",
        "PUT,    /api/v1/carts/me,           CART_WRITE",
        "POST,   /api/v1/orders,             ORDER_WRITE",
        "POST,   /api/v1/orders/1/cancellation, ORDER_WRITE",
        "POST,   /api/v1/carts/%6De/merge,   CART_MERGE",
        // 끝 슬래시는 병합 패턴에 맞지 않아 일반 장바구니 쓰기 한도로 센다 — 하류 MVC 도 끝 슬래시를 맞추지 않아 404 라 병합 우회가 아니다
        "POST,   /api/v1/carts/me/merge/,    CART_WRITE"
    })
    @DisplayName("쓰기 요청은 처음 맞는 정책 하나에 걸린다 — 병합은 일반 장바구니 쓰기가 아니라 병합 한도를 쓴다")
    void matchesWritePolicy(String method, String path, RateLimitPolicy expected) {
        assertThat(RateLimitPolicy.match(HttpMethod.valueOf(method), PathContainer.parsePath(path))).contains(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "GET,     /api/v1/carts/me",
        "HEAD,    /api/v1/carts/me",
        "OPTIONS, /api/v1/carts/me/items",
        "GET,     /api/v1/carts/me/merge",
        "GET,     /api/v1/orders/1",
        "POST,    /api/v1/admin/orders/1/status",
        "POST,    /api/v1/auth/login",
        "POST,    /api/v1/products"
    })
    @DisplayName("조회 · preflight · 어드민 · 그 밖의 경로는 세지 않는다 — 로그인 시도 제한은 auth-service 가 맡는다(#66)")
    void ignoresOtherRequests(String method, String path) {
        assertThat(RateLimitPolicy.match(HttpMethod.valueOf(method), PathContainer.parsePath(path))).isEqualTo(Optional.empty());
    }
}
