package com.commercefoundry.domainlayer.job.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.job.adapter.in.admin.dto.response.JobResultDownloadResponse;
import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.port.in.JobResultDownloadUseCase;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.AdminPermission;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(JobResultDownloadController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class JobResultDownloadControllerTest {
    private static final String PATH = "/api/v1/admin/jobs/42/result-download";
    @Autowired private MockMvcTester mvc;
    @MockitoBean private JobResultDownloadUseCase useCase;

    @Test
    void anonymousAndCustomerCannotIssueDownload() {
        assertThat(mvc.post().uri(PATH).exchange()).hasStatus(401);
        assertThat(mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, token(MemberRole.CUSTOMER)).exchange()).hasStatus(403);
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("결과 다운로드는 REPORT_READ 라 MANAGER 는 유스케이스에 닿고 STAFF 는 403 · SECURITY_005 다 (#588)")
    void reportReadersMayIssueDownload() {
        SecurityEnvelopeAssertions.assertEnvelope(
            mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, token(MemberRole.STAFF)).exchange().getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(useCase);

        given(useCase.download(42L, TestTokens.MEMBER_ID, AdminPermission.grantedTo(MemberRole.MANAGER))).willReturn(
            new JobResultDownloadResponse("https://private.example.test/file?signature=SECRET", Instant.parse("2026-11-01T00:01:00Z"), "report.csv", "text/csv", 3));
        assertThat(mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, token(MemberRole.MANAGER)).exchange()).hasStatusOk();
        verify(useCase).download(42L, TestTokens.MEMBER_ID, AdminPermission.grantedTo(MemberRole.MANAGER));
    }

    @Test
    void adminIdentityComesFromAccessTokenAndResponseIsNoStore() {
        var expires = Instant.parse("2026-11-01T00:01:00Z");
        given(useCase.download(42L, TestTokens.MEMBER_ID, AdminPermission.grantedTo(MemberRole.ADMIN))).willReturn(
            new JobResultDownloadResponse("https://private.example.test/file?signature=SECRET", expires, "report.csv", "text/csv", 3));
        var result = mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, token(MemberRole.ADMIN)).exchange();
        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(result).bodyJson().extractingPath("$.dataBody.fileName").isEqualTo("report.csv");
        verify(useCase).download(42L, TestTokens.MEMBER_ID, AdminPermission.grantedTo(MemberRole.ADMIN));
    }

    @Test
    void expiredAndStorageFailuresUseDomainEnvelope() {
        given(useCase.download(42L, TestTokens.MEMBER_ID, AdminPermission.grantedTo(MemberRole.ADMIN))).willThrow(new JobException(JobErrorCode.RESULT_FILE_EXPIRED));
        var expired = mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, token(MemberRole.ADMIN)).exchange();
        assertThat(expired).hasStatus(410);
        assertThat(expired).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("JOB_023");
        willThrow(new JobException(JobErrorCode.RESULT_STORAGE_UNAVAILABLE)).given(useCase).download(42L, TestTokens.MEMBER_ID, AdminPermission.grantedTo(MemberRole.ADMIN));
        assertThat(mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, token(MemberRole.ADMIN)).exchange()).hasStatus(503);
    }

    @Test
    void hiddenJobUsesNotFoundEnvelope() {
        given(useCase.download(42L, TestTokens.MEMBER_ID, AdminPermission.grantedTo(MemberRole.MANAGER)))
            .willThrow(new JobException(JobErrorCode.NOT_FOUND_JOB));
        var result = mvc.post().uri(PATH + "?role=OWNER&permissions=SETTLEMENT_READ")
            .header(HttpHeaders.AUTHORIZATION, token(MemberRole.MANAGER)).exchange();
        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("JOB_001");
        verify(useCase).download(42L, TestTokens.MEMBER_ID, AdminPermission.grantedTo(MemberRole.MANAGER));
    }

    private static String token(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }
}
