package com.commercefoundry;

import static com.commercefoundry.security.support.SecurityEnvelopeAssertions.assertEnvelope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.ResourceServerSmokeTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;

/**
 * 인가 스모크 — security-core Resource Server 가 이 서비스에 실제로 걸렸는지 본다 ({@code security-guide.md} §5-3).
 * 애노테이션은 {@link ProductServiceApplicationTests} 와 같아 컨텍스트를 같이 쓴다.
 *
 * <p>공개 조회 API 케이스는 슬라이스가 아니라 실제 빈 구성(Facade · Processor · 어댑터 · H2)으로 {@code permitAll()} 이 걸렸는지 본다.
 * internal API 의 익명 응답은 단언하지 않는다 — 서비스 간 인증(#51)이 들어오면 바뀐다.
 */
class ProductServiceSecuritySmokeTest extends ResourceServerSmokeTestSupport {

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/categories", "/api/v1/products", "/api/v1/products?categoryId=1&sortType=PRICE_ASC"})
    @DisplayName("공개 조회 API 는 익명 · 회원 모두 200 이다 (permitAll)")
    void publicCatalogIsOpen(String path) throws Exception {
        assertThat(perform(get(path)).getStatus()).as("익명 " + path).isEqualTo(200);
        assertThat(perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))).getStatus()).as("회원 " + path).isEqualTo(200);
    }

    @Test
    @DisplayName("공개 상세는 익명이어도 인증 오류가 아니라 도메인 404 · PRODUCT_001 이다")
    void publicDetailReachesDomain() throws Exception {
        assertEnvelope(perform(get("/api/v1/products/1")), 404, "PRODUCT_001", "존재하지 않는 상품입니다.");
    }
}
