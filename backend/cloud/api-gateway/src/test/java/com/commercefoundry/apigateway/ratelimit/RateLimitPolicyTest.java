package com.commercefoundry.apigateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
        "POST,   /api/v1/payments,          PAYMENT_WRITE",
        "POST,   /api/v1/payments/confirm,  PAYMENT_WRITE",
        "PUT,    /api/v1/payments/123,      PAYMENT_WRITE",
        "PATCH,  /api/v1/payments/123,      PAYMENT_WRITE",
        "DELETE, /api/v1/payments/123,      PAYMENT_WRITE",
        "POST,   /api/v1/payments/webhooks-other, PAYMENT_WRITE",
        "POST,   /api/v1/payments/webhooks-other/toss, PAYMENT_WRITE",
        "POST,   /api/v1/orders,             ORDER_WRITE",
        "POST,   /api/v1/orders/1/cancellation, ORDER_WRITE",
        "POST,   /api/v1/orders/quotes,      ORDER_QUOTE",
        // 미리보기 한도는 POST 미리보기에만 쓴다 — 같은 경로의 다른 쓰기 메서드 · 끝 슬래시는 주문 쓰기 한도로 센다(더 엄격한 order-write 로 세므로 우회가 아니다)
        "PUT,    /api/v1/orders/quotes,      ORDER_WRITE",
        "POST,   /api/v1/orders/quotes/,     ORDER_WRITE",
        "POST,   /api/v1/carts/%6De/merge,   CART_MERGE",
        // 끝 슬래시는 병합 패턴에 맞지 않아 일반 장바구니 쓰기 한도로 센다 — 하류 MVC 도 끝 슬래시를 맞추지 않아 404 라 병합 우회가 아니다
        "POST,   /api/v1/carts/me/merge/,    CART_WRITE"
    })
    @DisplayName("쓰기 요청은 처음 맞는 정책 하나에 걸린다 — 병합 · 주문서 미리보기는 일반 쓰기가 아니라 각자의 한도를 쓴다")
    void matchesWritePolicy(String method, String path, RateLimitPolicy expected) {
        assertThat(RateLimitPolicy.match(HttpMethod.valueOf(method), PathContainer.parsePath(path))).contains(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "/api/v1/support/guest-sessions,                SUPPORT_GUEST_SESSION",
        "/api/v1/support/%67uest-sessions,              SUPPORT_GUEST_SESSION",
        // 끝 슬래시 · 하위 경로는 발급 패턴에 맞지 않아 support-write catch-all 로 센다 — 어느 쪽이든 필수 보호라 우회가 아니다
        "/api/v1/support/guest-sessions/,               SUPPORT_WRITE",
        "/api/v1/support/guest-sessions/1,              SUPPORT_WRITE",
        "/api/v1/support/conversations,                 SUPPORT_WRITE",
        "/api/v1/support/conversations/123/messages,    SUPPORT_WRITE",
        "/api/v1/support/conversations/123,             SUPPORT_WRITE",
        "/api/v1/support,                               SUPPORT_WRITE"
    })
    @DisplayName("상담 POST — 비회원 발급이 support-write catch-all 보다 먼저 맞는다 (#470 B2b-1)")
    void supportPostsMatchGuestSessionBeforeCatchAll(String path, RateLimitPolicy expected) {
        assertThat(RateLimitPolicy.match(HttpMethod.POST, PathContainer.parsePath(path))).contains(expected);
    }

    @Test
    @DisplayName("필수 보호는 상담 두 정책과 방문 분석뿐이다 — 장바구니 · 주문 · 결제는 fail-open 을 유지한다")
    void onlySupportAndAnalyticsPoliciesAreMandatory() {
        assertThat(Arrays.stream(RateLimitPolicy.values()).filter(RateLimitPolicy::mandatory))
            .containsExactly(RateLimitPolicy.SUPPORT_GUEST_SESSION, RateLimitPolicy.SUPPORT_WRITE, RateLimitPolicy.ANALYTICS_EVENTS);
        assertThat(RateLimitPolicy.values()[0]).as("첫 매칭 1개 규칙 — 발급이 맨 앞").isEqualTo(RateLimitPolicy.SUPPORT_GUEST_SESSION);
        assertThat(RateLimitPolicy.SUPPORT_GUEST_SESSION.getId()).isEqualTo("support-guest-session");
        assertThat(RateLimitPolicy.SUPPORT_WRITE.getId()).as("Redis 키 일부 — 넓혀도 진행 중인 버킷을 잇는다").isEqualTo("support-write");
    }

    @ParameterizedTest
    @CsvSource({
        "GET,     /api/v1/payments/1",
        "HEAD,    /api/v1/payments/1",
        "OPTIONS, /api/v1/payments/confirm",
        "POST,    /api/v1/payments/webhooks",
        "POST,    /api/v1/payments/webhooks/",
        "POST,    /api/v1/payments/webhooks/toss",
        "POST,    /api/v1/payments/%77ebhooks/toss",
        "POST,    /api/v1/payments/webhooks;tenant=x/toss",
        "GET,     /api/v1/carts/me",
        "HEAD,    /api/v1/carts/me",
        "OPTIONS, /api/v1/carts/me/items",
        "GET,     /api/v1/carts/me/merge",
        "GET,     /api/v1/orders/1",
        "POST,    /api/v1/admin/orders/1/status",
        "POST,    /api/v1/auth/login",
        "POST,    /api/v1/products",
        "GET,     /api/v1/support/guest-sessions",
        "OPTIONS, /api/v1/support/guest-sessions",
        "GET,     /api/v1/support/conversations/123/messages",
        "POST,    /api/v1/admin/support/knowledge",
        "POST,    /api/v1/supporting/1"
    })
    @DisplayName("조회 · preflight · 어드민 · 그 밖의 경로는 세지 않는다 — 로그인 시도 제한은 auth-service 가 맡는다(#66)")
    void ignoresOtherRequests(String method, String path) {
        assertThat(RateLimitPolicy.match(HttpMethod.valueOf(method), PathContainer.parsePath(path))).isEqualTo(Optional.empty());
    }
}
