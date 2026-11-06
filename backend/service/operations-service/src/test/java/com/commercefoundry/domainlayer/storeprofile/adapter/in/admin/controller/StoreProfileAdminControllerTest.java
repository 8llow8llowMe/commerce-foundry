package com.commercefoundry.domainlayer.storeprofile.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.storeprofile.application.port.in.StoreProfileAdminUseCase;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * 상점 공개 정보 어드민 API 의 인가 계약 (#588) — 공개 사업자 정보라 조회는 {@code SETTINGS_READ}, 교체는 {@code SETTINGS_MANAGE} 이고 둘 다 ADMIN · OWNER 만 연다.
 * 저장 · 검증 · 감사 흐름은 {@code StoreProfileFlowTest} 가 본다. 테스트 값은 모두 가짜다.
 */
@WebMvcTest(StoreProfileAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class StoreProfileAdminControllerTest {

    private static final String PATH = "/api/v1/admin/store/profile";
    private static final String VALID_BODY = """
        {"expectedVersion":null,"storeName":"Example Store","companyName":"Example Company",
        "representativeName":"Example Representative","countryCode":"US","businessRegistrationNumber":"EXAMPLE-REG-A1",
        "mailOrderRegistrationNumber":null,"address":"Example public address","customerServiceEmail":"help@example.invalid",
        "customerServicePhone":null,"customerServiceHours":null}
        """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private StoreProfileAdminUseCase useCase;

    private static String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }

    private MvcTestResult get(MemberRole role) {
        return mvc.get().uri(PATH).header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange();
    }

    private MvcTestResult put(MemberRole role) {
        return mvc.put().uri(PATH).header(HttpHeaders.AUTHORIZATION, bearer(role)).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY).exchange();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"ADMIN", "OWNER"})
    @DisplayName("ADMIN · OWNER 는 조회 · 교체가 유스케이스에 닿고 교체한 운영자는 토큰의 memberId 다 (#588)")
    void ownersMayReadAndSave(MemberRole role) {
        assertThat(get(role)).hasStatusOk();
        assertThat(put(role)).hasStatusOk();

        verify(useCase).get();
        verify(useCase).save(eq(TestTokens.MEMBER_ID), any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"MANAGER", "STAFF", "CUSTOMER"})
    @DisplayName("MANAGER · STAFF · CUSTOMER 는 SETTINGS 권한이 없어 조회 · 교체(유효한 본문) 모두 403 · SECURITY_005 이고 유스케이스를 부르지 않는다 (#588)")
    void othersAreForbidden(MemberRole role) {
        SecurityEnvelopeAssertions.assertEnvelope(get(role).getResponse(), SecurityErrorCode.FORBIDDEN);
        SecurityEnvelopeAssertions.assertEnvelope(put(role).getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("토큰이 없으면 조회 · 교체 모두 401 · SECURITY_001 이다")
    void anonymousIsUnauthorized() {
        SecurityEnvelopeAssertions.assertEnvelope(mvc.get().uri(PATH).exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
        SecurityEnvelopeAssertions.assertEnvelope(
            mvc.put().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY).exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
        verifyNoInteractions(useCase);
    }
}
