package com.commercefoundry;

import static com.commercefoundry.security.support.SecurityEnvelopeAssertions.assertEnvelope;
import static com.commercefoundry.security.support.SecurityEnvelopeAssertions.assertWwwAuthenticate;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.support.ResourceServerSmokeTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 인가 스모크 — security-core Resource Server 가 이 서비스에 실제로 걸렸는지 본다 ({@code security-guide.md} §5-3).
 * 애노테이션은 {@link OrderServiceApplicationTests} 와 같아 컨텍스트를 같이 쓴다.
 */
class OrderServiceSecuritySmokeTest extends ResourceServerSmokeTestSupport {

    /** 서비스의 첫 {@code isAuthenticated()} 핸들러(장바구니, #12). 서비스 URL 규칙은 열려 있어 메서드 보안이 막는다. */
    @Test
    @DisplayName("토큰 없이 장바구니를 조회하면 401 · SECURITY_001 봉투 · WWW-Authenticate: Bearer")
    void anonymousOnCart() throws Exception {
        MockHttpServletResponse response = perform(get("/api/v1/carts/me"));

        assertEnvelope(response, SecurityErrorCode.UNAUTHORIZED);
        assertWwwAuthenticate(response, "Bearer");
    }

    /** 주문서 미리보기(#13). 본문 검증이 메서드 보안보다 먼저라 올바른 본문으로 보낸다 ({@code api-design-guide.md} §5). */
    @Test
    @DisplayName("토큰 없이 주문서 금액을 미리 보면 401 · SECURITY_001 봉투 · WWW-Authenticate: Bearer")
    void anonymousOnOrderQuote() throws Exception {
        MockHttpServletResponse response = perform(post("/api/v1/orders/quotes").contentType(MediaType.APPLICATION_JSON)
            .content("{\"items\":[{\"skuId\":1,\"quantity\":1}],\"destination\":{\"countryCode\":\"KR\",\"postalCode\":\"63100\"}}"));

        assertEnvelope(response, SecurityErrorCode.UNAUTHORIZED);
        assertWwwAuthenticate(response, "Bearer");
    }
}
