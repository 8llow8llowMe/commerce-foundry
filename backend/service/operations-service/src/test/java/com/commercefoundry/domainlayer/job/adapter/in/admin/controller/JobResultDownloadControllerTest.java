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
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
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
    void adminIdentityComesFromAccessTokenAndResponseIsNoStore() {
        var expires = Instant.parse("2026-11-01T00:01:00Z");
        given(useCase.download(42L, TestTokens.MEMBER_ID)).willReturn(
            new JobResultDownloadResponse("https://private.example.test/file?signature=SECRET", expires, "report.csv", "text/csv", 3));
        var result = mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, token(MemberRole.ADMIN)).exchange();
        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(result).bodyJson().extractingPath("$.dataBody.fileName").isEqualTo("report.csv");
        verify(useCase).download(42L, TestTokens.MEMBER_ID);
    }

    @Test
    void expiredAndStorageFailuresUseDomainEnvelope() {
        given(useCase.download(42L, TestTokens.MEMBER_ID)).willThrow(new JobException(JobErrorCode.RESULT_FILE_EXPIRED));
        var expired = mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, token(MemberRole.ADMIN)).exchange();
        assertThat(expired).hasStatus(410);
        assertThat(expired).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("JOB_023");
        willThrow(new JobException(JobErrorCode.RESULT_STORAGE_UNAVAILABLE)).given(useCase).download(42L, TestTokens.MEMBER_ID);
        assertThat(mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, token(MemberRole.ADMIN)).exchange()).hasStatus(503);
    }

    private static String token(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }
}
