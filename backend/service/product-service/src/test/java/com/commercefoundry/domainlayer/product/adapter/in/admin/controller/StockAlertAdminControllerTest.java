package com.commercefoundry.domainlayer.product.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.product.application.port.in.StockAlertAdminUseCase;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 품절 임박 기준 · 조회의 역할별 권한 (#586) — 조회는 {@code AdminAuthority.INVENTORY_READ}, 설정 · 해제는 {@code INVENTORY_MANAGE} 다.
 * 업무 계약(기준 저장 · 품절 임박 목록)은 {@code StockAlertFlowIntegrationTest} 가 본다.
 *
 * <p>슬라이스라 {@code *BeansConfig} 가 빠지므로 보안 구성을 직접 import 하고, 테스트 키로 서명한 실제 access 토큰을 보낸다 ({@code security-guide.md} §5-3).
 */
@WebMvcTest(StockAlertAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class StockAlertAdminControllerTest {

    private static final String LOW_PATH = "/api/v1/admin/stocks/low";
    private static final String POLICY_PATH = "/api/v1/admin/stocks/1001/low-stock-policy";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private StockAlertAdminUseCase useCase;

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"ADMIN", "OWNER", "MANAGER"})
    @DisplayName("품절 임박 조회 · 기준 조회는 INVENTORY_READ 라 ADMIN · OWNER · MANAGER 는 200 이다")
    void inventoryReadersMayRead(MemberRole role) {
        assertThat(get(LOW_PATH, role)).hasStatusOk();
        assertThat(get(POLICY_PATH, role)).hasStatusOk();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"STAFF", "CUSTOMER"})
    @DisplayName("STAFF · CUSTOMER 는 INVENTORY_READ 가 없어 품절 임박 조회 · 기준 조회가 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
    void othersMayNotRead(MemberRole role) {
        SecurityEnvelopeAssertions.assertEnvelope(get(LOW_PATH, role).getResponse(), SecurityErrorCode.FORBIDDEN);
        SecurityEnvelopeAssertions.assertEnvelope(get(POLICY_PATH, role).getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"MANAGER", "STAFF"})
    @DisplayName("기준 설정 · 해제는 INVENTORY_MANAGE 라 MANAGER · STAFF 는 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
    void writesRequireInventoryManage(MemberRole role) {
        SecurityEnvelopeAssertions.assertEnvelope(put(role).getResponse(), SecurityErrorCode.FORBIDDEN);
        SecurityEnvelopeAssertions.assertEnvelope(
            mvc.delete().uri(POLICY_PATH).header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange().getResponse(), SecurityErrorCode.FORBIDDEN
        );
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"ADMIN", "OWNER"})
    @DisplayName("ADMIN · OWNER 는 INVENTORY_MANAGE 가 있어 기준 설정 · 해제가 200 이다")
    void inventoryManagersMayWrite(MemberRole role) {
        assertThat(put(role)).hasStatusOk();
        assertThat(mvc.delete().uri(POLICY_PATH).header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange()).hasStatusOk();
    }

    private MvcTestResult get(String uri, MemberRole role) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange();
    }

    private MvcTestResult put(MemberRole role) {
        return mvc.put().uri(POLICY_PATH).contentType(MediaType.APPLICATION_JSON).content("{\"thresholdQuantity\": 3}")
            .header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange();
    }

    private static String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }
}
