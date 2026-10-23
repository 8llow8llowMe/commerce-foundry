package com.commercefoundry;

import static com.commercefoundry.security.support.SecurityEnvelopeAssertions.assertEnvelope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.ResourceServerSmokeTestSupport;
import com.commercefoundry.security.support.TestServiceTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 인가 스모크 — security-core Resource Server 가 이 서비스에 실제로 걸렸는지 본다 ({@code security-guide.md} §5-3).
 * 애노테이션은 {@link DeliveryServiceApplicationTests} 와 같아 컨텍스트를 같이 쓴다.
 *
 * <p>배송비 API 케이스는 슬라이스가 아니라 실제 빈 구성(Facade · Processor · 어댑터 · H2)과 실제 토큰(사용자 JWT · 서비스 토큰)으로
 * 권한이 걸렸는지 본다. 정책 데이터는 넣지 않는다 — 공유 컨텍스트의 H2 에 상태를 남기지 않게, 도메인까지 닿았는지는 "정책 없음" 응답으로 확인한다.
 */
class DeliveryServiceSecuritySmokeTest extends ResourceServerSmokeTestSupport {

    private static final String POLICY_PATH = "/api/v1/shipping-fees/policy";
    private static final String ADMIN_POLICY_PATH = "/api/v1/admin/shipping-policies/domestic";
    private static final String ADMIN_POLICY_HISTORIES_PATH = "/api/v1/admin/shipping-policies/domestic/histories";
    private static final String INTERNATIONAL_POLICY_PATH = "/api/v1/shipping-fees/international-policy";
    private static final String ADMIN_INTERNATIONAL_POLICY_PATH = "/api/v1/admin/shipping-policies/international";
    private static final String QUOTE_PATH = "/internal/v1/shipping-fees/quotes";
    private static final String QUOTE_BODY = """
        {"orderAmount": {"amount": 99000, "currency": "KRW"}, "countryCode": "KR", "postalCode": "63100"}
        """;

    private MockHttpServletResponse quoteAs(String caller) throws Exception {
        MockHttpServletRequestBuilder request = post(QUOTE_PATH)
            .header(HttpHeaders.AUTHORIZATION, TestServiceTokens.bearer(caller, serviceId()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(QUOTE_BODY);
        return perform(request);
    }

    /** 해외 정책 안내는 test 프로파일에서 해외 배송 플래그가 꺼져 있어(기본값) 정책 유무와 무관하게 dataBody null 이다. */
    @ParameterizedTest
    @ValueSource(strings = {POLICY_PATH, INTERNATIONAL_POLICY_PATH})
    @DisplayName("고객 배송비 정책(국내 · 해외)은 익명 · 회원 모두 200 이다 (permitAll) — 정책이 없으면 dataBody null")
    void publicPolicyIsOpen(String path) throws Exception {
        MockHttpServletResponse anonymous = perform(get(path));

        assertThat(anonymous.getStatus()).isEqualTo(200);
        assertThat(anonymous.getContentAsString()).contains("\"dataBody\":null");
        assertThat(perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))).getStatus()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {ADMIN_POLICY_PATH, ADMIN_INTERNATIONAL_POLICY_PATH, ADMIN_POLICY_HISTORIES_PATH})
    @DisplayName("어드민 배송비 정책(국내 · 해외 · 국내 교체 이력)은 ADMIN 만 200 이고, 토큰 없음 401 · SECURITY_001 · CUSTOMER 403 · SECURITY_005 다")
    void adminPolicyRequiresAdmin(String path) throws Exception {
        assertThat(perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))).getStatus()).isEqualTo(200);
        assertEnvelope(perform(get(path)), SecurityErrorCode.UNAUTHORIZED);
        assertEnvelope(perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))), SecurityErrorCode.FORBIDDEN);
    }

    /** 실제 빈 구성 · 실제 서비스 토큰으로 기능 플래그가 닫힌 쪽이 기본값인지 본다 — 해외 정책을 읽기 전에 거절한다. */
    @Test
    @DisplayName("해외 배송이 꺼진 기본 설정에서 KR 외 국가 견적은 400 · DELIVERY_002 다")
    void internationalQuoteIsClosedByDefault() throws Exception {
        MockHttpServletResponse response = perform(post(QUOTE_PATH)
            .header(HttpHeaders.AUTHORIZATION, TestServiceTokens.bearer("order-service", serviceId()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"orderAmount": {"amount": 10000, "currency": "USD"}, "countryCode": "US", "weightGrams": 500}
                """));

        assertEnvelope(response, 400, "DELIVERY_002", "배송할 수 없는 국가입니다.");
    }

    /** 순서 고정 — 본문 검증(118 · 119 · 120)이 플래그 확인(002)보다 먼저다. order-flow-guide §2 "검증 순서" 와 같다. */
    @Test
    @DisplayName("해외 배송이 꺼져 있어도 무게 없는 KR 외 견적은 002 가 아니라 400 · DELIVERY_118 이다")
    void bodyValidationRunsBeforeFlag() throws Exception {
        MockHttpServletResponse response = perform(post(QUOTE_PATH)
            .header(HttpHeaders.AUTHORIZATION, TestServiceTokens.bearer("order-service", serviceId()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"orderAmount": {"amount": 10000, "currency": "USD"}, "countryCode": "US"}
                """));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("\"resultCode\":\"DELIVERY_118\"");
    }

    @Test
    @DisplayName("order-service 서비스 토큰의 견적은 인증 · 인가를 통과해 도메인에 닿는다 — 정책이 없어 409 · DELIVERY_001")
    void orderServiceReachesQuote() throws Exception {
        assertEnvelope(quoteAs("order-service"), 409, "DELIVERY_001", "배송비 정책이 설정되지 않았습니다.");
    }

    @Test
    @DisplayName("허용 목록에 없는 payment-service 서비스 토큰의 견적은 403 · SECURITY_005 다")
    void paymentServiceIsForbidden() throws Exception {
        assertEnvelope(quoteAs("payment-service"), SecurityErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("서비스 토큰 없이 견적을 부르면 401 · SECURITY_007 이다")
    void quoteWithoutServiceToken() throws Exception {
        assertEnvelope(perform(post(QUOTE_PATH).contentType(MediaType.APPLICATION_JSON).content(QUOTE_BODY)), SecurityErrorCode.SERVICE_UNAUTHORIZED);
    }

    @Test
    @DisplayName("사용자 access 토큰(ADMIN)으로 견적을 부르면 401 · SECURITY_007 이다 — internal 은 서비스 토큰만 받는다")
    void quoteWithUserToken() throws Exception {
        MockHttpServletResponse response = perform(post(QUOTE_PATH)
            .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))
            .contentType(MediaType.APPLICATION_JSON)
            .content(QUOTE_BODY));

        assertEnvelope(response, SecurityErrorCode.SERVICE_UNAUTHORIZED);
    }

    @Test
    @DisplayName("aud 가 다른 서비스(product-service)인 order-service 서비스 토큰은 401 · SECURITY_007 이다")
    void quoteWithOtherAudience() throws Exception {
        MockHttpServletResponse response = perform(post(QUOTE_PATH)
            .header(HttpHeaders.AUTHORIZATION, TestServiceTokens.bearer("order-service", "product-service"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(QUOTE_BODY));

        assertEnvelope(response, SecurityErrorCode.SERVICE_UNAUTHORIZED);
    }

    /** 본문을 일부러 비워 저장까지 가지 않게 한다 — 공유 컨텍스트의 H2 에 정책을 남기지 않는다. 400 이면 인증 · 인가를 통과해 본문 검증에 닿은 것이다. */
    @Test
    @DisplayName("ADMIN 토큰의 어드민 정책 교체(PUT)는 인증 · 인가를 통과한다 — 빈 본문이라 400 · DELIVERY_109")
    void adminCanReachReplace() throws Exception {
        MockHttpServletResponse response = perform(put(ADMIN_POLICY_PATH)
            .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"remoteAreas\": []}"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("\"resultCode\":\"DELIVERY_109\"");
    }
}
