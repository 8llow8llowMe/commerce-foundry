package com.commercefoundry;

import static com.commercefoundry.security.support.SecurityEnvelopeAssertions.assertEnvelope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.ResourceServerSmokeTestSupport;
import com.commercefoundry.security.support.TestServiceTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 인가 스모크 — security-core Resource Server 가 이 서비스에 실제로 걸렸는지 본다 ({@code security-guide.md} §5-3).
 * 애노테이션은 {@link PaymentServiceApplicationTests} 와 같아 컨텍스트를 같이 쓴다.
 *
 * <p>정산 대사(#219) 케이스는 실제 빈 구성(Facade · Processor)과 실제 서비스 토큰으로 본다. test 프로파일은 기능 플래그가 꺼져 있어(기본값) 허용 호출자는 도메인까지 닿아
 * 409 {@code SETTLEMENT_001} 이고, PG · DB 에 닿지 않는다.
 */
class PaymentServiceSecuritySmokeTest extends ResourceServerSmokeTestSupport {

    private static final String SETTLEMENT_RECONCILE_PATH = "/internal/v1/settlements/reconcile";

    private MockHttpServletResponse reconcile(String authorization, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(SETTLEMENT_RECONCILE_PATH).contentType(MediaType.APPLICATION_JSON).content(body);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return perform(request);
    }

    @Test
    @DisplayName("정산 대사 — operations-service 는 인증 · 인가를 통과하고, 플래그 OFF(기본)라 409 SETTLEMENT_001 이다 (#219)")
    void operationsReachesSettlementButFlagIsOff() throws Exception {
        MockHttpServletResponse response = reconcile(TestServiceTokens.bearer("operations-service", serviceId()), "{\"soldDate\":\"2026-11-20\"}");

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).contains("\"resultCode\":\"SETTLEMENT_001\"");
    }

    @Test
    @DisplayName("정산 대사 — 허용 목록에 없는 order-service 는 403 · SECURITY_005, 토큰 없음 · 사용자 토큰은 401 · SECURITY_007 이다 (#219)")
    void settlementRejectsOthers() throws Exception {
        String body = "{\"soldDate\":\"2026-11-20\"}";
        assertEnvelope(reconcile(TestServiceTokens.bearer("order-service", serviceId()), body), SecurityErrorCode.FORBIDDEN);
        assertEnvelope(reconcile(null, body), SecurityErrorCode.SERVICE_UNAUTHORIZED);
        assertEnvelope(reconcile(bearer(MemberRole.ADMIN), body), SecurityErrorCode.SERVICE_UNAUTHORIZED);
    }

    @Test
    @DisplayName("정산 대사 — 매출일 누락은 400 SETTLEMENT_101, 형식 오류는 400 PAYMENT_100 이다 (#219)")
    void settlementValidatesSoldDate() throws Exception {
        String operations = TestServiceTokens.bearer("operations-service", serviceId());

        MockHttpServletResponse missing = reconcile(operations, "{}");
        MockHttpServletResponse malformed = reconcile(operations, "{\"soldDate\":\"2026-02-30\"}");

        assertThat(missing.getStatus()).isEqualTo(400);
        assertThat(missing.getContentAsString()).contains("\"resultCode\":\"SETTLEMENT_101\"");
        assertThat(malformed.getStatus()).isEqualTo(400);
        assertThat(malformed.getContentAsString()).contains("\"resultCode\":\"PAYMENT_100\"");
    }
}
