package com.commercefoundry.domainlayer.member.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.adapter.in.internal.presenter.AdminNotificationRecipientInternalPresenter;
import com.commercefoundry.domainlayer.member.application.port.out.AdminNotificationRecipientQueryPort;
import com.commercefoundry.domainlayer.member.application.service.AdminNotificationRecipientInternalFacade;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminNotificationRecipientQueryProcessor;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.internal.InternalTokenPropertiesConfig;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestServiceTokens;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import com.nimbusds.jose.JOSEObjectType;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** 실제 보안 체인 · JWT와 Controller→Facade→Processor→Presenter를 통과한다. DB 포트만 대체한다. */
@WebMvcTest(AdminNotificationRecipientInternalController.class)
@Import({
    InternalResourceServerConfigurer.class, InternalTokenPropertiesConfig.class,
    JwtVerificationPropertiesConfig.class, ResourceServerSecurityConfigurer.class,
    AdminNotificationRecipientInternalFacade.class, AdminNotificationRecipientQueryProcessor.class,
    AdminNotificationRecipientInternalPresenter.class
})
class AdminNotificationRecipientInternalControllerTest {

    private static final String PATH = "/internal/v1/admin-notification-recipients";
    private static final String CALLER = "operations-service";
    private static final String AUDIENCE = "auth-service";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private AdminNotificationRecipientQueryPort queryPort;

    @Test
    @DisplayName("operations 서비스 토큰은 기본 0/100으로 조회하고 Snowflake 문자열 ID만 받는다")
    void defaultsAndIdsOnly() {
        given(queryPort.findActiveAdminIdsAfter(0L, 100)).willReturn(List.of(10L, 9007199254740993L));

        var result = mvc.get().uri(PATH).header(HttpHeaders.AUTHORIZATION, serviceBearer()).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody")
            .isEqualTo(Map.of("memberIds", List.of("10", "9007199254740993")));
        verify(queryPort).findActiveAdminIdsAfter(0L, 100);
    }

    @Test
    @DisplayName("마지막 ID를 afterId로 넘기는 다음 페이지가 비었으면 200 빈 배열이다")
    void emptyNextPage() {
        given(queryPort.findActiveAdminIdsAfter(Long.MAX_VALUE, 1)).willReturn(List.of());

        var result = mvc.get().uri(PATH).param("afterId", String.valueOf(Long.MAX_VALUE)).param("size", "1")
            .header(HttpHeaders.AUTHORIZATION, serviceBearer()).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody").isEqualTo(Map.of("memberIds", List.of()));
        verify(queryPort).findActiveAdminIdsAfter(Long.MAX_VALUE, 1);
    }

    @ParameterizedTest
    @CsvSource({
        "afterId, -1, AUTH_157", "size, 0, AUTH_158", "size, 101, AUTH_158",
        "afterId, text, AUTH_198", "afterId, 9223372036854775808, AUTH_198", "size, 2147483648, AUTH_198"
    })
    @DisplayName("잘못된 커서·크기는 400이고 DB 조회가 없다")
    void invalidParameters(String parameter, String value, String code) {
        var result = mvc.get().uri(PATH).param(parameter, value).header(HttpHeaders.AUTHORIZATION, serviceBearer()).exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        verifyNoInteractions(queryPort);
    }

    @Test
    @DisplayName("무토큰 · 사용자 ADMIN/CUSTOMER JWT는 internal 인증에서 401 SECURITY_007이다")
    void rejectsAnonymousAndUserTokens() {
        assertServiceAuthenticationFailure(null);
        for (MemberRole role : MemberRole.values()) {
            String accessToken = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
            assertServiceAuthenticationFailure("Bearer " + accessToken);
        }
        verifyNoInteractions(queryPort);
    }

    @Test
    @DisplayName("잘못된 audience/typ · 만료 · 위조 서명인 서비스 JWT는 401이고 조회하지 않는다")
    void rejectsInvalidServiceTokens() {
        Instant now = Instant.now();
        var claims = TestServiceTokens.claims(CALLER, AUDIENCE).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(60))).build();
        assertServiceAuthenticationFailure(TestServiceTokens.bearer(CALLER, "payment-service"));
        assertServiceAuthenticationFailure("Bearer " + TestServiceTokens.sign(TestServiceTokens.SECRET,
            TestServiceTokens.header().type(new JOSEObjectType("at+jwt")).build(), claims));
        assertServiceAuthenticationFailure("Bearer " + TestServiceTokens.issuer(CALLER, Instant.now().minusSeconds(3600)).issue(AUDIENCE));
        assertServiceAuthenticationFailure("Bearer " + TestServiceTokens.sign(TestServiceTokens.OTHER_SECRET,
            claims));
        verifyNoInteractions(queryPort);
    }

    @Test
    @DisplayName("다른 서비스 · 미등록 호출자의 유효 JWT는 403 SECURITY_005이고 조회하지 않는다")
    void rejectsOtherService() {
        for (String caller : List.of("order-service", "unknown-service")) {
            var result = mvc.get().uri(PATH).header(HttpHeaders.AUTHORIZATION, TestServiceTokens.bearer(caller, AUDIENCE)).exchange();
            SecurityEnvelopeAssertions.assertEnvelope(result.getResponse(), SecurityErrorCode.FORBIDDEN);
        }
        verifyNoInteractions(queryPort);
    }

    private void assertServiceAuthenticationFailure(String authorization) {
        var request = mvc.get().uri(PATH);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        SecurityEnvelopeAssertions.assertEnvelope(request.exchange().getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
    }

    private String serviceBearer() {
        return TestServiceTokens.bearer(CALLER, AUDIENCE);
    }
}
