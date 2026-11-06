package com.commercefoundry.domainlayer.legaldocument.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.legaldocument.application.port.in.LegalDocumentAdminUseCase;
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
 * 법적 문서 어드민 API 의 인가 계약 (#588) — 약관 · 개인정보처리방침은 상점 설정이라 조회는 {@code SETTINGS_READ}, 초안 저장 · 게시는 {@code SETTINGS_MANAGE} 이고
 * 둘 다 ADMIN · OWNER 만 연다. 저장 · 게시 흐름은 {@code LegalDocumentFlowTest} 가 본다. 테스트 값은 모두 가짜다.
 */
@WebMvcTest(LegalDocumentAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class LegalDocumentAdminControllerTest {

    private static final String DOC_PATH = "/api/v1/admin/store/legal-documents/TERMS/ko";
    private static final String DRAFT_BODY = "{\"expectedVersion\": null, \"title\": \"이용약관\", \"body\": \"본문\"}";
    private static final String PUBLISH_BODY = "{\"expectedVersion\": 0, \"revisionNumber\": 1, \"reviewConfirmed\": true}";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private LegalDocumentAdminUseCase useCase;

    private static String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }

    private MvcTestResult get(String uri, MemberRole role) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange();
    }

    private MvcTestResult saveDraft(MemberRole role) {
        return mvc.put().uri(DOC_PATH + "/draft").header(HttpHeaders.AUTHORIZATION, bearer(role))
            .contentType(MediaType.APPLICATION_JSON).content(DRAFT_BODY).exchange();
    }

    private MvcTestResult publish(MemberRole role) {
        return mvc.post().uri(DOC_PATH + "/publish").header(HttpHeaders.AUTHORIZATION, bearer(role))
            .contentType(MediaType.APPLICATION_JSON).content(PUBLISH_BODY).exchange();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"ADMIN", "OWNER"})
    @DisplayName("ADMIN · OWNER 는 조회 4종 · 초안 저장 · 게시가 유스케이스에 닿고 작성자는 토큰의 memberId 다 (#588)")
    void ownersMayReadAndManage(MemberRole role) {
        assertThat(get("/api/v1/admin/store/legal-documents", role)).hasStatusOk();
        assertThat(get(DOC_PATH, role)).hasStatusOk();
        assertThat(get(DOC_PATH + "/revisions", role)).hasStatusOk();
        assertThat(get("/api/v1/admin/store/legal-document-templates/TERMS", role)).hasStatusOk();
        assertThat(saveDraft(role)).hasStatusOk();
        assertThat(publish(role)).hasStatusOk();

        verify(useCase).save(eq(TestTokens.MEMBER_ID), any());
        verify(useCase).publish(eq(TestTokens.MEMBER_ID), any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"MANAGER", "STAFF", "CUSTOMER"})
    @DisplayName("MANAGER · STAFF · CUSTOMER 는 SETTINGS 권한이 없어 조회 · 초안 저장 · 게시(유효한 본문) 모두 403 · SECURITY_005 이고 유스케이스를 부르지 않는다 (#588)")
    void othersAreForbidden(MemberRole role) {
        SecurityEnvelopeAssertions.assertEnvelope(get(DOC_PATH, role).getResponse(), SecurityErrorCode.FORBIDDEN);
        SecurityEnvelopeAssertions.assertEnvelope(saveDraft(role).getResponse(), SecurityErrorCode.FORBIDDEN);
        SecurityEnvelopeAssertions.assertEnvelope(publish(role).getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("토큰이 없으면 조회 · 초안 저장 모두 401 · SECURITY_001 이다")
    void anonymousIsUnauthorized() {
        SecurityEnvelopeAssertions.assertEnvelope(mvc.get().uri(DOC_PATH).exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
        SecurityEnvelopeAssertions.assertEnvelope(
            mvc.put().uri(DOC_PATH + "/draft").contentType(MediaType.APPLICATION_JSON).content(DRAFT_BODY).exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
        verifyNoInteractions(useCase);
    }
}
