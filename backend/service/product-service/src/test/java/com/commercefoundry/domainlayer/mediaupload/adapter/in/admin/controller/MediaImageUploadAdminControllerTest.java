package com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.dto.response.MediaImageUploadResponse;
import com.commercefoundry.domainlayer.mediaupload.application.port.in.MediaImageUploadAdminUseCase;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 전시 · 룩 공용 이미지 업로드 발급 · 확정의 역할별 권한 (#586) — 둘 다 {@code AdminAuthority.CATALOG_MANAGE} 다.
 * 업로드 흐름 전체(발급 → PUT → 확정)는 {@code MediaImageUploadFlowIntegrationTest} 가 본다.
 *
 * <p>슬라이스라 {@code *BeansConfig} 가 빠지므로 보안 구성을 직접 import 하고, 테스트 키로 서명한 실제 access 토큰을 보낸다 ({@code security-guide.md} §5-3).
 * 403 케이스도 유효한 본문 · 키를 보낸다 — 검증 400 이 인가 403 을 가리지 않게 한다.
 */
@WebMvcTest(MediaImageUploadAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class MediaImageUploadAdminControllerTest {

    private static final String PATH = "/api/v1/admin/displays/image-uploads";
    private static final String UPLOAD_KEY = "123e4567-e89b-12d3-a456-426614174000.jpg";
    private static final String CONFIRM_PATH = PATH + "/" + UPLOAD_KEY + "/confirm";
    private static final String VALID_BODY = """
        {"contentType": "image/jpeg", "contentLength": 1024}
        """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private MediaImageUploadAdminUseCase useCase;

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"MANAGER", "STAFF", "CUSTOMER"})
    @DisplayName("발급 · 확정은 CATALOG_MANAGE 라 MANAGER · STAFF · CUSTOMER 는 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
    void uploadRequiresCatalogManage(MemberRole role) {
        SecurityEnvelopeAssertions.assertEnvelope(issue(role).getResponse(), SecurityErrorCode.FORBIDDEN);
        SecurityEnvelopeAssertions.assertEnvelope(confirm(role).getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"ADMIN", "OWNER"})
    @DisplayName("ADMIN · OWNER 는 CATALOG_MANAGE 가 있어 발급 201 · 확정 200 이다")
    void catalogManagersMayUpload(MemberRole role) {
        given(useCase.issue(anyLong(), anyString(), anyLong())).willReturn(
            new MediaImageUploadResponse.Ticket(UPLOAD_KEY, "https://storage.example.com/upload", "PUT", Map.of(), Instant.EPOCH));
        given(useCase.confirm(anyLong(), anyString())).willReturn(
            new MediaImageUploadResponse.Confirmed("https://cdn.example.com/media/a.jpg", "image/jpeg", 1024L));

        assertThat(issue(role)).hasStatus(HttpStatus.CREATED);
        assertThat(confirm(role)).hasStatusOk();
    }

    @Test
    @DisplayName("토큰이 없으면 401 · SECURITY_001 이고 유스케이스를 부르지 않는다")
    void anonymousIsUnauthorized() {
        SecurityEnvelopeAssertions.assertEnvelope(
            mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY).exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED
        );
        verifyNoInteractions(useCase);
    }

    private MvcTestResult issue(MemberRole role) {
        return mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY).header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange();
    }

    private MvcTestResult confirm(MemberRole role) {
        return mvc.post().uri(CONFIRM_PATH).header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange();
    }

    private static String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }
}
